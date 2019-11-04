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

(ns datomic.query.support
  (:require [clojure.edn :as edn]))

(defn- incorrect!
  [msg]
  (throw (ex-info msg {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                       :cognitect.anomalies/message msg})))

(defn listq->mapq
  "turns [:find ?a, ?b :in $src1 $src2 :where [$src1 ?a :likes ?b] ($src2 drummer ?b)] into
  {:find (?a ?b) :in ($src1 $src2) :where ([$src1 ?a :likes ?b] ($src2 drummer ?b))}"
  [lq]
  (->> lq
       (partition-by #{:find :keys :strs :syms :with :in :where :timeout})
       (partition 2)
       (reduce (fn [m [k v]]
                 (let [k (first k)]
                   (assoc m k v)))
               {})))

(defn disallow-find-variants!
  [query]
  (when (some #(or (vector? %) (= '. %)) (:find query))
    (incorrect! "Only find-rel elements are allowed in client :find")))

(defn parse-as
  "takes a query (in any form, e.g. str/vec/map) and returns [q as]
  the returned query will be in map form, and 'as' will be nil or a vector of keys"
  [q]
  (let [nq (if (string? q) (edn/read-string q) q)
        nq (if (sequential? nq) (listq->mapq nq) nq)
        {:keys [find keys strs syms]} nq]
    (disallow-find-variants! nq)
    (if-let [asyms (or keys strs syms)]
      (let [as (cond->> asyms
                        keys (map keyword)
                        strs (map str)
                        :then vec)]
        (when-not (= (count (or keys strs syms)) (count find))
          (throw (incorrect! "Count of :keys/:strs/:syms must match count of :find")))
        [(dissoc nq :keys :syms :strs) as])
      [nq nil])))
