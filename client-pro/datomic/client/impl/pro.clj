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

(ns datomic.client.impl.pro
  (:require
   [clojure.core.async :refer (>!! promise-chan)]
   [datomic.client.impl.shared :as shared]
   [datomic.client.impl.shared.spi :as spi]
   [datomic.client.impl.shared.validator :as v]))

(def ^:private DEFAULT_HTTPS_PORT 443)

(defn- result-chan
  [result]
  (doto (promise-chan)
    (>!! result)))

(defn- endpoint->routing-map
  [s]
  (when s
    (let [[_ host _ port] (re-find #"^([^:]+)(:(\d+)?)?$" s)
          port (if port (Long/parseLong port) DEFAULT_HTTPS_PORT)]
      (when (and host port)
        {:headers {"host" host}
         :scheme "https"
         :server-name host
         :server-port port
         :uri "/"}))))

(deftype Spi
  [sign-params routing-map]
  spi/Spi
  (-add-routing
   [spi req]
   (merge-with merge req routing-map))
  (-get-sign-params
   [_ _ _]
   sign-params)
  (-refresh-sign-params
   [_ _ _]
   (result-chan sign-params)))

(def client-requirements
  {:access-key string?
   :secret string?
   :endpoint string?})

(defn- create-spi
  [{:keys [access-key endpoint secret] :as config}]
  (when-let [err (v/require-keys :client config client-requirements)]
    (throw (ex-info (:cognitect.anomalies/message err) err)))
  (let [routing-map (endpoint->routing-map (:endpoint config))]
    (if (and access-key endpoint routing-map)
      (->Spi {:access-key-id access-key
              :secret secret
              :service "peer-server"
              :region "none"}
             routing-map)
      (let [details (assoc config :secret "<ELIDED>")]
        (throw (ex-info (str "Invalid connection config: " details)
                        {:config details}))))))

(defn create-client
  "Creates a client."
  [config]
  (shared/create-client (create-spi config) (shared/create-http-client config)))
