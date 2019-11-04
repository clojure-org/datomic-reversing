;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;      http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS-IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns datomic.client.impl.cloud
  (:require
   [clojure.core.async :refer (<! <!! go promise-chan)]
   [clojure.edn :as edn]
   [cognitect.s3-creds.cache :as cache]
   [cognitect.s3-creds.keyfile :as keyfile]
   [cognitect.s3-creds.store :as store]
   [datomic.client.impl.shared :as shared]
   [datomic.client.impl.shared.io :as cio]
   [datomic.client.impl.shared.spi :as spi]
   [datomic.client.impl.shared.protocols :as p]
   [datomic.s3-access-keys :as ak])
  (:import
    [com.amazonaws.auth DefaultAWSCredentialsProviderChain]
    [java.net URI]))

(def ^:private DEFAULT_HTTP_PORT 80)
(def ^:private DEFAULT_HTTPS_PORT 443)

(defn- anom
  [x]
  (when (:cognitect.anomalies/category x)
    x))

(defn- explain-sign-params-anom
  "Returns an explanation of x if anomaly, else nil."
  [x keyfile-name]
  (when-let [cat (:cognitect.anomalies/category x)]
    (cond
     (= cat :cognitect.anomalies/forbidden)
     (assoc x :cognitect.anomalies/message
            (str "Forbidden to read keyfile at " keyfile-name ". Make sure that your endpoint is correct, and that your ambient AWS credentials allow you to GetObject on the keyfile."))

     (= cat :cognitect.anomalies/not-found)
     (assoc x :cognitect.anomalies/message
            (str "Unable to find keyfile at " keyfile-name ". Make sure that your endpoint and db-name are correct."))

     :default x)))

(defn- parse-endpoint
  [s]
  (when s
    (let [uri (URI. s)
          host (.getHost uri)
          scheme (.getScheme uri)
          port (.getPort uri)
          port (cond (> port 0) port
                     (= "http" scheme) DEFAULT_HTTP_PORT
                     (= "https" scheme) DEFAULT_HTTPS_PORT)
          header-host (if (or (= port DEFAULT_HTTP_PORT)
                              (= port DEFAULT_HTTPS_PORT))
                        host
                        (str host ":" port))]
      (if (and scheme host port)
        {:headers {"host" header-host}
         :scheme scheme
         :server-name host
         :server-port port}
        (throw (ex-info (str "Invalid endpoint")
                        {:endpoint s}))))))

(defn- build-routing-map
  [cfg]
  (assoc (:endpoint-map cfg) :uri (str "/group/" (:query-group cfg))))

(def ^:private op->access-key-type
  {:datomic.catalog/list-dbs :catalog-read
   :datomic.catalog/create-db :admin
   :datomic.catalog/delete-db :admin
   :datomic.client.protocol/q :db-read
   :datomic.client.protocol/next :db-read
   :datomic.catalog/resolve-db :db-read
   :datomic.client.protocol/status :db-read
   :datomic.client.protocol/datoms :db-read
   :datomic.client.protocol/index-range :db-read
   :datomic.client.protocol/pull :db-read
   :datomic.client.protocol/db-stats :db-read
   :datomic.client.protocol/tx-range :db-read
   :datomic.client.protocol/transact :db-write
   :datomic.client.protocol/with-db :db-read
   :datomic.client.protocol/with :db-read})

(defn- base-access-key-id
  [cfg request-context http-req]
  (when-let [type (-> http-req :headers (get "x-nano-op") keyword op->access-key-type)]
    (ak/create-access-key-id (merge {:type type
                                     :prefix (str "s3://" (:s3-auth-path cfg))
                                     :system (:system cfg)
                                     :key-name "dummy"}
                                    (when-let [db-name (:db-name request-context)]
                                      {:db-name db-name})))))

(defn- sign-params
  [cfg {:keys [access-key-id creds]}]
  (merge {:service "datomic"
          :region (:region cfg)
          :access-key-id access-key-id
          :secret (:secret creds)}))

(deftype Spi [cfg cache store routing-map]
  spi/Spi
  (-add-routing
   [spi req]
   (merge-with merge req routing-map))
  (-get-sign-params
   [spi req addr]
   (let [bakid (base-access-key-id cfg addr req)]
     (when-let [creds-result (cache/get cache bakid)]
       (sign-params cfg creds-result))))
  (-refresh-sign-params
   [spi req addr]
   (go
    (let [bakid (base-access-key-id cfg addr req)
          pakid (keyfile/parse-access-key-id bakid)
          keyfile (<! (store/get-keyfile store (:keyfile-name pakid)))]
      (or (explain-sign-params-anom keyfile (:keyfile-name pakid))
          (if-let [creds-result (keyfile/current-creds pakid keyfile)]
            (do
              (cache/put cache bakid creds-result)
              (sign-params cfg creds-result))
            {:cognitect.anomalies/category :cognitect.anomalies/not-found
             :cognitect.anomalies/message "No current key"}))))))

(declare ^:skip-wiki ->Spi)

(defn- valid-config?
  [config]
  (let [{:keys [endpoint system region query-group]} config]
    (and (string? endpoint) (string? system) (string? query-group) (string? region))))

(defn- create-spi
  [cfg]
  (->Spi cfg (cache/create) (store/create-store {:creds-provider (:creds-provider cfg)}) (build-routing-map cfg)))

(deftype Client [base]
  p/Client
  (connect [this m] (p/connect base m))
  (list-databases [this m] (p/list-databases base m))
  (create-database
   [this m]
   (shared/-async-op base nil :datomic.catalog/create-db {} m))
  (delete-database
   [this m]
   (shared/-async-op base nil :datomic.catalog/delete-db {} m)))

(declare ^:skip-wiki ->SystemConnection)

(defn- get-s3-auth-path
  [http-client {:keys [endpoint-map] :as config}]
  (let [resp (-> (<!! (shared/send-request http-client
                                           (merge endpoint-map
                                                  {:request-method :get
                                                   :uri "/"})
                                           60000))
                 cio/http-resp->client-resp)]
    (when (anom resp)
      (throw (ex-info (str "Unable to connect to system: " resp)
                      {:config config})))
    (let [edn-response (edn/read-string resp)]
      (if (and (map? edn-response)
               (contains? edn-response :s3-auth-path))
        (:s3-auth-path edn-response)
        (throw (ex-info (str "Invalid response received connecting to system, expected to find s3-auth-path in response: " resp)
                        {:config config}))))))

(defn create-client
  "Create client for a Datomic cloud system. Required config keys:

  :region          - AWS region, e.g. \"us-east-1\"
  :system          - your system name
  :query-group     - your query group name
  :endpoint        - IP address of your system
  :creds-provider  - optional, instance of com.amazonaws.auth.AWSCredentialsProvider. Defaults to DefaultAWSCredentialsProviderChain

Returns a connection or an error map."
  [config]
  (if (valid-config? config)
    (let [http-client (shared/create-http-client config)
          config (assoc config :endpoint-map (parse-endpoint (:endpoint config)))
          config (assoc config :s3-auth-path (get-s3-auth-path http-client config))
          base (shared/create-client (create-spi config) http-client)]
      (->Client base))
    (throw (ex-info (str "Invalid connection config: " config) {:config config}))))