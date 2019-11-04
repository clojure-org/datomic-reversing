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

(ns datomic.client.impl.shared
  (:require
   [clojure.core.async :as a :refer (<! >! >!! chan close! go promise-chan put! take!)]
   [cognitect.hmac-authn :as hmac]
   [cognitect.http-client :as http]
   [datomic.client.impl.shared.exceptions :as ex :refer (anom)]
   [datomic.client.impl.shared.io :as cio]
   [datomic.client.impl.shared.spi :as spi]
   [datomic.client.impl.shared.protocols :as p]
   [datomic.client.impl.shared.trust :as trust]
   [datomic.client.impl.shared.validator :as v]))

(set! *warn-on-reflection* true)

(def db-context-keys [:db-name :database-id :t :next-t :as-of :since :history :next-token])
(def state-keys [:t :next-t])

(defn result-chan
  [result]
  (doto (promise-chan)
    (>!! result)))

(defn exp-backoff
  "Returns a backoff function that will return increasing backoffs from
start up to max by multiplicative factor, then nil."
  [start max factor]
  (let [a (atom (/ start factor))]
    #(let [backoff (long (swap! a * factor))]
       (when (<= backoff max)
         backoff))))

(defn linear-backoff
  [backoff ct]
  (let [a (atom ct)]
    #(let [ct (swap! a dec)]
       (when (<= 0 ct)
         backoff))))

(defn send-request
  "Send the request"
  [http-client req timeout]
  (let [c (chan)
        post (cond-> req
                     timeout (assoc ::http/timeout-msec timeout))]
    (http/submit http-client post c)
    c))

(def ^:const DEFAULT_TIMEOUT 60000)

(def idempotent-ops
  (let [catalog-ops #{"administer-system"
                      "resolve-db"
                      "list-dbs"}
        client-ops #{"datoms"
                     "db-stats"
                     "index-range"
                     "next"
                     "pull"
                     "q"
                     "status"
                     "tx-range"
                     "with"
                     "with-db"}]
    (into #{} (concat (map #(str "datomic.catalog/" %) catalog-ops)
                      (map #(str "datomic.client.protocol/" %) client-ops)))))

(defn retry-anom?
  "Returns true if combo of anom-catetgory and op should be retried.
  anom-category is an anomaly category keyword
  op is the string per io/qualified-op"
  [anom-category op]
  (let [retriable-categories #{:cognitect.anomalies/unavailable
                               :cognitect.anomalies/interrupted}]
    (and (contains? idempotent-ops op)
         (contains? retriable-categories anom-category))))

(defn send-with-retry
  [http-req request-context http-client spi timeout http->client]
  (let [timeout (or timeout DEFAULT_TIMEOUT)
        b-backoff (exp-backoff 100 400 2)
        u-backoff (linear-backoff 3000 20)
        to (a/timeout timeout)]
    (go
     (loop [sign-params (or (spi/-get-sign-params spi http-req request-context)
                            (<! (spi/-refresh-sign-params spi http-req request-context)))
            to-result {:cognitect.anomalies/category :cognitect.anomalies/interrupted
                       :cognitect.anomalies/message "Datomic Client Timeout"}]
       (or (anom sign-params)
           (let [signed (hmac/sign http-req sign-params)
                 [http-result port] (a/alts! [to (send-request http-client signed timeout)])]
             (if (= port to)
               to-result
               (let [result (http->client http-result)
                     cat (:cognitect.anomalies/category result)]
                 (cond
                   (= cat :cognitect.anomalies/busy)
                   (if-let [msec (b-backoff)]
                     (if (= to (second (a/alts! [to (a/timeout msec)])))
                       to-result
                       (recur sign-params result))
                     result)

                   (= cat :cognitect.anomalies/forbidden)
                   (let [new-params (<! (spi/-refresh-sign-params spi http-req request-context))]
                     (if (= new-params sign-params)
                       result
                       (recur new-params to-result)))

                   (retry-anom? cat (:op http-req))
                   (if-let [msec (u-backoff)]
                     (if (= to (second (a/alts! [to (a/timeout msec)])))
                       to-result
                       (recur sign-params result))
                     result)

                   :default
                   result)))))))))

(defn add-routing
  [req spi]
  (spi/-add-routing spi req))

(defn advance-t*
  [state new-state]
  (if (> (:t new-state) (or (:t state) -1))
    (select-keys new-state state-keys)
    state))

(defprotocol TTracking
  (-advance-t [conn db] "Advance conn's notion of t to match db's, returning
a conn.")
  (-status [conn] "Calls wire protocol 'status' fn, returning channel of result.")
  (-stale? [conn] "Returns true if connection is stale"))

(defn update-state
  "Update state of conn based on server response."
  [conn {:keys [dbs]}]
  (when conn
    (doseq [db dbs]
      (if (= (:database-id db) (:database-id conn))
        (-advance-t conn db)))))

(defn convert-response
  [conn c f]
  (let [retc (promise-chan)]
    (take!
     c
     (fn [resp]
       (update-state conn resp)
       (try
        (if-let [x (or (anom resp) (f resp))]
          (put! retc x)
          (close! retc))
        (catch Throwable t
          (put! retc (ex/throwable->anomaly t))))))
    retc))

(defn convert-chunked-responses
  "Convert chunked responses on c, transforming with f and
placing results on retc. Stays 1 + retc's buffer size ahead of
consumer."
  [conn c f http-client spi request-context timeout retc]
  (go
   (let [resp (<! c)]
     (update-state conn resp)
     (try
      (when-let [x (or (anom resp) (f resp))]
        (>! retc x)
        (if (:next-offset resp)
          (let [{:keys [next-offset next-token chunk]} resp
                cnext (-> (assoc request-context
                            :op :next
                            :next-token next-token
                            :offset next-offset
                            :chunk chunk)
                          cio/client-req->http-req
                          (add-routing spi)
                          (send-with-retry request-context http-client spi timeout cio/http-resp->client-resp))]
            (convert-chunked-responses conn cnext f http-client spi request-context timeout retc))
          (close! retc)))
      (catch Throwable t
        (put! retc (ex/throwable->anomaly t))
        (close! retc))))))

;; conn is backpointer to conn for updating state, can be nil
(defprotocol AsyncOps
  (-async-op [_ conn op requester m])
  (-chunked-async-op [_ conn op requester m]))

(defprotocol Requester
  (-get-client [_] "Returns the client, can be nil")
  (-get-conn [_] "Returns the client, can be nil")
  (-get-state [_] "Returns a map with the state-keys.")
  (-get-request-context [_] "properties of requester that ops need to add to
the explicit params in the request map."))

(extend-protocol Requester
  java.util.Map
  (-get-client [_] nil)
  (-get-conn [_] nil)
  (-get-state [this] this)
  (-get-request-context [this] this))

(defprotocol QueryArg
  (->query-arg [_] "Convert data structure to form marshalable as an
:arg to query."))

(extend-protocol QueryArg
  Object
  (->query-arg [this] this))

(def ^:private index->idxvec
  {:eavt [:e :a :v]
   :aevt [:a :e :v]
   :avet [:a :v :e]
   :vaet [:v :a :e]})

(defmulti api->client-req* (fn [op _] op))
(def op-requirements
  {:datomic.catalog/administer-system {:db-name string?
                                       :action keyword?}
   :datomic.catalog/create-db {:db-name string?}
   :datomic.catalog/delete-db {:db-name string?}
   :connect {:db-name string?}
   :transact {:tx-data coll?}
   :pull {:eid identity :selector identity}
   :datoms {:index v/index?}
   :with {:tx-data coll?}
   })
(defn api-arg-error
  [op arg]
  (v/require-keys op arg (get op-requirements op)))
(defn api->client-req
  "Convert the argument map m for op into the format needed by the
wire protocol, merging in context from requester."
  [op requester m]
  (when-let [err (api-arg-error op m)]
    (throw (ex-info (:cognitect.anomalies/message err) err)))
  (merge (-get-request-context requester) (api->client-req* op m) {:op op}))
(defmulti client-resp->api* (fn [op & _] op))
(defn client-resp->api
  "Convert the map m by a call to op into the format expected by
the API, possibly using context from requester."
  [op requester m]
  (client-resp->api* op requester m))

;; use create-db
(declare create-db)
(deftype Db
  [client conn info]
  clojure.lang.ILookup
  (valAt [this k] (get info k))
  (valAt [this k not-found] (get info k not-found))

  QueryArg
  (->query-arg [this] (select-keys info db-context-keys))
  Requester
  (-get-client [_] client)
  (-get-conn [_] conn)
  (-get-request-context [this] (select-keys info db-context-keys))
  (-get-state [this] (select-keys info state-keys))
  p/ParentConnection
  (-conn [_] conn)
  p/Db
  (as-of [this t] (create-db client conn (assoc info :as-of t)))
  (datoms [this m] (-chunked-async-op client conn :datoms this m))
  (db-stats [this] (-async-op client conn :db-stats this nil))
  ;; yes, both :history and :history?
  ;; only :history? works with ions, :history still supported for legacy compat
  ;; :history also flows straight through to the wire protocol
  (history [this] (create-db client conn (assoc info :history true :history? true)))
  (index-range [this m] (-chunked-async-op client conn :index-range this m))
  (pull [this m] (-async-op client conn :pull this m))
  (since [this t] (create-db client conn (assoc info :since t)))
  (with [this m] (-async-op client conn :with this m)))

(defmethod print-method Db [db ^java.io.Writer w]
  (.write w (-> (.info ^Db db)
                (assoc :type :datomic.client/db)
                (dissoc :history) ;; only show history? key
                str)))

(defmethod print-dup Db [o w]
  (print-method o w))

(defn create-db
  [client conn info]
  (->Db client conn info))

;; use create-connection
(deftype Connection
  [client state-ref info refresh-interval last-refresh]
  clojure.lang.ILookup
  (valAt [this k] (get info k))
  (valAt [this k not-found] (get info k not-found))

  TTracking
  (-advance-t
   [conn db]
   (swap! state-ref advance-t* db)
   conn)
  (-status
   [_]
   (go
    (<! (-async-op client nil :status info info))))
  (-stale?
   [_]
   (< (+ @last-refresh refresh-interval)
      (System/currentTimeMillis)))
  
  Requester
  (-get-client [_] client)
  (-get-conn [this] this)
  (-get-request-context
   [_]
   (merge @state-ref info))
  (-get-state [_] @state-ref)
  p/Connection
  (recent-db
   [this]
   (go
    (if (-stale? this)
      (let [status (<! (-status this))]
        (or (anom status)
            (do
              (-advance-t this status)
              (reset! last-refresh (System/currentTimeMillis))
              (p/db this))))
      (p/db this))))
  (db
   [this]
   (create-db client this (-get-request-context this)))
  (log [_] {:log (:database-id info)})
  (q [this m] (-chunked-async-op client this :q this m))
  (sync [this t]
    (assert (int? t))
    (-advance-t this {:t t})
    (result-chan (p/db this)))
  (tx-range [this m] (-chunked-async-op client this :tx-range this m))
  (transact [this m] (-async-op client this :transact this m))
  (with-db [this] (-async-op client this :with-db this nil)))

(defmethod print-method Connection [o ^java.io.Writer w]
  (.write w (str (merge (.info ^Connection o)
                        (-get-state o)
                        {:type :datomic.client/conn}))))

(defmethod print-dup Connection [o w]
  (print-method o w))

(defn create-connection
  [client state info]
  (->Connection client (atom state) info 2000 (atom (System/currentTimeMillis))))

(deftype Client
  [http-client spi]

  AsyncOps
  (-async-op
   [_ conn op requester m]
   (let [request-context (-get-request-context requester)
         convert (partial convert-response conn)]
     (-> (api->client-req op request-context m)
         cio/client-req->http-req
         (add-routing spi)
         (send-with-retry request-context http-client spi (:timeout m) cio/http-resp->client-resp)
         (convert #(client-resp->api op requester %)))))

  (-chunked-async-op
   [_ conn op requester m]
   (let [request-context (-get-request-context requester)
         retc (chan)
         convert (partial convert-chunked-responses conn)]
     (-> (api->client-req op request-context m)
         cio/client-req->http-req
         (add-routing spi)
         (send-with-retry request-context http-client spi (:timeout m) cio/http-resp->client-resp)
         (convert #(client-resp->api op requester %) http-client spi request-context (:timeout m) retc))
     retc))

  p/Client
  (connect
   [this m]
   (if-let [err (api-arg-error :connect m)]
     (throw (ex-info (:cognitect.anomalies/message err) err))
     (let [retc (promise-chan)
           address (select-keys m [:db-name])]
       (go
        (let [resolved (<! (-async-op this nil :datomic.catalog/resolve-db address m))]
          (if (anom resolved)
            (>! retc resolved)
            (let [status (<! (-async-op this nil :status address (merge m resolved)))]
              (if (anom status)
                (>! retc status)
                (do
                  (>! retc (create-connection this
                                              (select-keys status state-keys)
                                              {:db-name (:db-name m)
                                               :database-id (:database-id resolved)}))))))))
       retc)))
  (list-databases
   [this m]
   (-async-op this nil :datomic.catalog/list-dbs m nil)))

(defmethod api->client-req* :datomic.catalog/resolve-db [_ m] (select-keys m [:db-name]))
(defmethod api->client-req* :status [_ m] (select-keys m [:database-id]))
(defmethod api->client-req* :datomic.catalog/administer-system [_ m] m)
(defmethod api->client-req* :datomic.catalog/list-dbs [_ m] nil)
(defmethod api->client-req* :datomic.catalog/create-db [_ m] m)
(defmethod api->client-req* :datomic.catalog/delete-db [_ m] m)
(defmethod api->client-req* :transact
  [_ m]
  (merge {:tx-id (java.util.UUID/randomUUID)} m))
(defmethod api->client-req* :q
  [_ {:keys [args] :as m}]
  (let [query-args (mapv ->query-arg args)
        next-token (some :next-token query-args)]
    (merge {:offset 0}
           (assoc m :args query-args)
           (when next-token
             {:next-token next-token}))))
(defmethod api->client-req* :tx-range
  [_ m]
  (merge {:offset 0} m))
(defmethod api->client-req* :with-db [_ m] m)
(defmethod api->client-req* :with [_ m] m)
(defmethod api->client-req* :datoms
  [_ m]
  (let [{:keys [index components]} m]
    (merge (zipmap (index->idxvec index) components)
           {:offset 0}
           (dissoc m :components))))
(defmethod api->client-req* :index-range [_ m]
  (merge {:offset 0} m))
(defmethod api->client-req* :pull [_ m] m)
(defmethod api->client-req* :db-stats [_ m] nil)
(defn response-db
  [wire-db requester]
  (create-db (-get-client requester)
             (-get-conn requester)
             (merge (select-keys (-get-request-context requester) [:database-id :db-name])
                    (select-keys wire-db db-context-keys))))
(defn client-tx-resp->api
  [requester m]
  (-> (select-keys m [:db-before :db-after :tx-data :tempids])
      (update :db-before response-db requester)
      (update :db-after response-db requester)))
(defmethod client-resp->api* :transact [_ requester m] (client-tx-resp->api requester m))
(defmethod client-resp->api* :datoms [_ _ m] (:data m))
(defmethod client-resp->api* :q [_ _ m] (:data m))
(defmethod client-resp->api* :pull [_ _ m] (or (:result m) {}))
(defmethod client-resp->api* :tx-range [_ _ m] (:data m))
(defmethod client-resp->api* :db-stats [_ _ m] (:result m))
(defmethod client-resp->api* :index-range [_ _ m] (:data m))
(defmethod client-resp->api* :datomic.catalog/resolve-db [_ _ m] m)
(defmethod client-resp->api* :status [_ _ m] m)
(defmethod client-resp->api* :datomic.catalog/administer-system [_ _ m] m)
(defmethod client-resp->api* :datomic.catalog/list-dbs [_ _ m] (:result m))
(defmethod client-resp->api* :datomic.catalog/create-db [_ _ m] true)
(defmethod client-resp->api* :datomic.catalog/delete-db [_ _ m] true)
(defmethod client-resp->api* :with-db [_ db m] (response-db m db))
(defmethod client-resp->api* :with [_ requester m] (client-tx-resp->api requester m))

(def create-http-client
  (memoize
   (fn [{:keys [trust-certs validate-hostnames] :as config
         :or {validate-hostnames true}}]
     (http/create
       (merge (dissoc config :trust-certs)
              {:validate-hostnames validate-hostnames
               :trust-store (trust/trust-store trust-certs)})))))

(defn create-client
  [spi http-client]
  (->Client http-client spi))
