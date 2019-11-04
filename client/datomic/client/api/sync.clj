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

(ns datomic.client.api.sync
  (:require [clojure.core.async :as a]
            [datomic.client.api :as api]
            [datomic.client.api.async :as async]
            [datomic.client.api.impl :as impl]
            [datomic.client.api.protocols :as protocols]
            [datomic.client.impl.shared :as shared])
  (:import java.util.Iterator))

(set! *warn-on-reflection* true)

(def ^:private ares @#'async/ares)

;; Clojure 1.8 back compat
(defn- halt-when*
  ([pred] (halt-when pred nil))
  ([pred retf]
     (fn [rf]
       (fn
         ([] (rf))
         ([result]
            (if (and (map? result) (contains? result ::halt))
              (::halt result)
              (rf result)))
         ([result input]
            (if (pred input)
              (reduced {::halt (if retf (retf (rf result) input) input)})
              (rf result input)))))))

(defn- unchunk
  [ch]
  (ares (a/transduce (halt-when* :cognitect.anomalies/category) into [] ch)))

(defn- unchunk-iterable
  "Returns an iteratable on chunked results in channel returned by
no-arg fn f. f will be called once per call to .iterator. Iterators
are not thread-safe."
  ^Iterable [f]
  (reify
   Iterable
   (iterator
    [_]
    (let [ch (f)
          next-chunk-iter! #(let [^Iterable it (or (ares ch) [])]
                              (.iterator it))
          vol (volatile! (next-chunk-iter!))
          it (fn [] (let [^Iterator it @vol]
                      (if (.hasNext it) it (vreset! vol (next-chunk-iter!)))))]
      (reify Iterator
             (hasNext [_] (.hasNext ^Iterator (it)))
             (next [_] (.next ^Iterator (it))))))))

(deftype Client [aclient]
  protocols/Client
  (administer-system [_ arg-map] (ares (async/administer-system aclient arg-map)))
  (list-databases [_ arg-map] (ares (async/list-databases aclient arg-map)))
  (connect [_ db-name] (ares (async/connect aclient db-name)))
  (create-database [_ arg-map] (ares (async/create-database aclient arg-map)))
  (delete-database [_ arg-map] (ares (async/delete-database aclient arg-map))))

(defn client
  [arg-map]
  (Client. (async/client arg-map)))

(extend-protocol protocols/Connection
  datomic.client.impl.shared.Connection
  (db [conn] (async/db conn))
  (sync [conn t] (ares (async/sync conn t)))
  (transact [conn arg-map] (ares (async/transact conn arg-map)))
  (tx-range [conn arg-map] (unchunk-iterable #(async/tx-range conn arg-map)))
  (with-db [conn] (ares (async/with-db conn))))

(extend-type datomic.client.impl.shared.Db 
  protocols/Db
  (pull ([db arg-map] (ares (async/pull db arg-map)))
        ([db selector eid] (api/pull db {:selector selector :eid eid})))
  (as-of [db time-point] (async/as-of db time-point))
  (datoms [db arg-map] (unchunk-iterable #(async/datoms db arg-map)))
  (db-stats [db] (ares (async/db-stats db)))
  (history [db] (async/history db))
  (index-range [db arg-map] (unchunk-iterable #(async/index-range db arg-map)))
  (since [db t] (async/since db t))
  (with [db arg-map] (ares (async/with db arg-map)))

  impl/Queryable
  (q [db arg-map] (unchunk (async/q (merge {:limit -1} arg-map)))))




