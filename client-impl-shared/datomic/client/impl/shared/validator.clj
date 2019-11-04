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

(ns datomic.client.impl.shared.validator)

(def index? #{:eavt :aevt :avet :vaet})

(defn predname
  [pred]
  (get {string? 'string
        identity 'value
        index? 'index
        keyword? 'keyword
        coll? 'collection}
       pred 'value))

(defn prednames
  [typemap]
  (reduce-kv
   (fn [m k v] (assoc m k (predname v)))
   {}
   typemap))

(defn require-keys
  "Returns an anomaly if argmap does not contain a value for every key
in typemap, whose value metches the predicate value in typemap."
  [op argmap typemap]
  (if (or (map? argmap) (nil? argmap))
    (reduce-kv
     (fn [_ k pred]
       (if (pred (get argmap k))
         nil
         (reduced {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                   :cognitect.anomalies/message (str "Expected " (predname pred) " for " k)
                   ::got argmap
                   ::op op
                   ::requirements (prednames typemap)})))
     nil
     typemap)
    {:cognitect.anomalies/category :cognitect.anomalies/incorrect
     :cognitect.anomalies/message (str "Expected a map")
     ::got argmap
     ::op op
     ::requirements (prednames typemap)}))


