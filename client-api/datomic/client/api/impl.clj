;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns datomic.client.api.impl
  (:require [clojure.edn :as edn])
  (:import [java.io FileNotFoundException]))

(defn- -ident?
  "Return true if x is a symbol or keyword"
  [x] (or (keyword? x) (symbol? x)))

(defn dynaload
  [gaid fsym]
  (try
   (require (symbol (namespace fsym)))
   (catch FileNotFoundException fnf
     (throw (IllegalArgumentException.
             (str "Unable to load client, make sure " gaid " is on your classpath")
             fnf)))))

(defn dynarun
  [gaid fsym arg-map]
  (if-let [s (resolve fsym)]
    (s arg-map)
    (throw (IllegalArgumentException.
            (str "Unable to resolve entry point, make sure you have the correct version of " gaid " on your classpath")))))

(defn dynacall
  [gaid fsym arg-map]
  (dynaload gaid fsym)
  (dynarun gaid fsym arg-map))

(defprotocol Queryable
  (q [_ arg-map]))

(defn find-queryable
  [arg-map]
  (some #(and (satisfies? Queryable %) %) arg-map))

(defn incorrect
  [msg]
  (ex-info msg {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                :cognitect.anomalies/message msg}))

(defn ion-server-type
  "Autodetect :cloud or :local based on the classpath."
  []
  (if (resolve 'datomic.client.impl.local/create-client)
    :local
    :cloud))

(defn eid->part
  "Returns the partition part of an eid, handles temp ids"
  ^long [^long eid]
  (bit-shift-right (bit-and eid 0x3fffffffffffffff) 42))

(defn maybe-eid [x]
  (cond
   (and (instance? Long x) (pos? (eid->part x)))
    x

    (and (map? x) (= 1 (count (keys x))))
    (:db/id x)))

(def reverse-key
     (memoize (fn [k]
                (let [n (name k)]
                  (if (= \_ (.charAt n 0))
                    (keyword (namespace k) (subs n 1))
                    (keyword (namespace k) (str "_" n)))))))

(defn inbound [db type id]
  (reduce (fn [m [a]] (assoc m (reverse-key a) '...))
          {:db/sym-id id}
          (q db
             {:query '[:find ?a
                       :in $ ?type ?e
                       :where
                       [?aid :db/valueType ?type]
                       [_ ?aid ?e]
                       [?aid :db/ident ?a]]
              :args [db type id]
              :timeout 5000})))

(defn pull+ [db eid]
  (let [[[pm ib]] (q db
                     {:query
                      '[:find (pull ?e [*]) ?ib
                        :in $ ?e
                        :where
                        [(q '[:find ?a
                              :in $ ?e
                              :where
                              [_ ?aid ?e]
                              [(q '[:find ?aid ?a
                                    :where
                                    [?aid :db/valueType]
                                    [?aid :db/ident ?a]]
                                  $)
                               [[?aid ?a]]]]
                            $ ?e) ?ib]
                        ]
                      :args [db eid]})
        im (reduce (fn [ret [k v]]
                     (let [rk (reverse-key k)]
                       (assoc ret rk '...)))
                   {} ib)]
    (merge pm im)))

(defn navize [db coll k x]
  (let [eid (maybe-eid x)
        v (cond
           (#{:db/id :db/sym-id} k) x

           (and (-ident? k) (.startsWith (name k) "_"))
           (when-let [id (or (:db/id coll) (:db/sym-id coll))]
             (mapv #(hash-map :db/id (nth % 0))
                   (q db
                      {:query
                       '[:find ?e
                         :in $ ?a ?v
                         :where
                         [?e ?a ?v]]
                       :args [db (reverse-key k) id]
                       :timeout 5000
                       :chunk 10000})))
           
           eid (pull+ db eid)

           (and (-ident? x) (namespace x))
           (inbound db (if (symbol? x) :db.type/symbol :db.type/keyword) x)

           :else x)]
    (if (coll? v)
      (vary-meta v merge {'clojure.core.protocols/nav (partial navize db)})
      v)))