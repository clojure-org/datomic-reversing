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

(ns datomic.client.api.async
  "Async client API for Datomic. See also datomic.client.api
for the corresponding synchronous API.

Functions in this namespace that communicate with a separate process
take an arg-map with the following optional keys:

  :timeout      timeout in msec

Asynchronous functions return a core.async channel. In the case of an
error, the channel will get an error map with contents specified by
cognitect.anomalies. See https://github.com/cognitect-labs/anomalies.

Functions that return chunked results will return a succession of
vectors of values in a channel. The channel will be closed when the
results are exhausted.  If there is an error it will be in the channel
instead of the chunk. Chunked functions support the following optional
keys:

  :chunk     Optional. Maximum number of results that will be returned
             for each chunk. Defaults to 1000.
  :offset    Number of results to omit from the beginning
             of the returned data.
  :limit     Maximum total number of results to return.
             Specify -1 for no limit. Defaults to 1000.

Functions that return datoms return values of a type that supports
indexed (count/nth) access of [e a v t added] as well as
lookup (keyword) access via :e :a :v :t :added."
  (:import java.io.FileNotFoundException)
  (:refer-clojure :exclude [sync])
  (:require
   [clojure.core.async :refer (<!!) :as async]
   [datomic.client.api.impl :as impl]
   [datomic.client.impl.shared.protocols :as p]
   [datomic.query.support :as qs])
  (:import datomic.query.support.MapOnIndexed))

(defn- ares
  [ch]
  (let [result (<!! ch)]
    (if (:cognitect.anomalies/category result)
      (throw (ex-info (or (:cognitect.anomalies/message result)
                          (-> result :datomic.client/http-error-body :cause)
                          "Datomic Client Exception")
                      result))
      result)))

(defn client
  "Identical to datomic.client.api/client."
  [arg-map]
  (case (:server-type arg-map)
        :cloud (impl/dynacall 'com.datomic/client-impl-cloud
                              'datomic.client.impl.cloud/create-client
                              arg-map)
        :peer-server (impl/dynacall 'com.datomic/client-impl-pro
                                    'datomic.client.impl.pro/create-client
                                    arg-map)
        (throw (impl/incorrect ":server-type must be :cloud or :peer-server"))))

(defn administer-system
  "Async version of datomic.api.client/administer-system.
Returns a channel. See namespace doc for timeout/error handling."
  [client arg-map]
  (p/administer-system client arg-map))

(defn list-databases
  "Async version of datomic.api.async/list-databases.
Returns a channel. See namespace doc for timeout/error handling."
  [client arg-map]
  (p/list-databases client arg-map))

(defn connect
  "Async version of datomic.client.api/connect.
Returns a channel. See namespace doc for timeout/error handling."
  [client db-name]
  (p/connect client db-name))

(defn create-database
  "Async version of datomic.api.client/create-database.
Returns a channel. See namespace doc for timeout/error handling."
  [client arg-map]
  (p/create-database client arg-map))

(defn delete-database
  "Async version of datomic.api.client/delete-database.
Returns a channel. See namespace doc for timeout/error handling."
  [client arg-map]
  (p/delete-database client arg-map))

(defn db
  "Identical to datomic.api.client/db."
  [conn]
  (ares (p/recent-db conn)))

(defn sync
  "Async version of datomic.api.client/sync.
Returns a channel"
  [conn t]
  (p/sync conn t))

(defn- find-parent-conn
  [arg-map]
  (some p/-conn (:args arg-map)))

(defn q
  "Async and chunked version of datomic.api.client/q.
Returns a channel. See namespace doc for chunking, timeout, and
error handling."
  [arg-map]
  (if-let [conn (find-parent-conn arg-map)]
    (let [[nq as] (qs/parse-as (:query arg-map))]
      (if as
        (let [res (p/q conn (assoc arg-map :query nq))
              mapify (fn [chunk]
                       (if (:cognitect.anomalies/category chunk)
                         chunk
                         (mapv #(MapOnIndexed. as %) chunk)))]
          (async/pipe res (async/chan 1 (map mapify)))) 
        (p/q conn arg-map)))
    (doto (async/promise-chan) (async/put! {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                                    :cognitect.anomalies/message "Query args must include a database"}))))

(defn pull
 "Async version of datomic.api.client/pull.
Returns a channel. See namespace doc for timeout/error handling."
  [db arg-map]
  (p/pull db arg-map))

(defn tx-range
 "Async and chunked version of datomic.api.client/tx-range.
Returns a channel. See namespace doc for chunking, timeout, and
error handling."
  [conn arg-map]
  (p/tx-range conn arg-map))

(defn transact
  "Async version of datomic.api.client/transact.
Returns a channel.  See namespace doc for timeout/error handling."
  [conn arg-map]
  (p/transact conn arg-map))

(defn with-db
  "Identical to datomic.api.client/with-db."
  [conn]
  (p/with-db conn))

(defn as-of
  "Identical to datomic.api.client/as-of."
  [db t]
  (p/as-of db t))

(defn datoms
 "Async and chunked version of datomic.api.client/datoms.
Returns a channel. See namespace doc for chunking, timeout, and
error handling."
  [db arg-map]
  (p/datoms db arg-map))

(defn db-stats
  "Async version of datomic.api.client/db-stats.
Returns a channel.  See namespace doc for timeout/error handling.."
  [db]
  (p/db-stats db))

(defn history
  "Identical to datomic.api.client/history."
  [db]
  (p/history db))

(defn index-range
 "Async and chunked version of datomic.api.client/index-range.
Returns a channel. See namespace doc for chunking, timeout, and
error handling."
  [db arg-map]
  (p/index-range db arg-map))

(defn since
  "Identical to datomic.api.client/since."
  [db t]
  (p/since db t))

(defn with
  "Async version of datomic.api.client/with.
Returns a channel.  See namespace doc for timeout/error handling."
  [db m]
  (p/with db m))





