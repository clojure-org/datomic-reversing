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

(ns ^:skip-wiki datomic.client.impl.shared.datom
  (:import [clojure.lang ILookup Indexed Counted]))

(set! *warn-on-reflection* true)

(deftype Datom [e a v tx added]
  Object
  (equals [this o]
          (or (identical? this o)
              (let [^Datom o o]
                (and (instance? Datom o)
                     (= tx (:tx o))
                     (= e (:e o))
                     (= a (:a o))
                     (zero? (compare v (:v o)))
                     (= added (:added o))))))
  (hashCode [_]
            (int (-> (hash e) (bit-xor (hash a)) (bit-xor (hash v)) (bit-xor (hash added)))))

  ILookup
  (valAt [this k]
         (.valAt this k nil))
  (valAt [this k notFound]
         (case k
               :e e
               :a a
               :v v
               :tx tx
               :added added
               notFound))

  Counted
  (count [_] 5)
    
  Indexed
  (nth [this i]
       (if (<= 0 i 4)
         (.nth this i nil)
         (throw (IndexOutOfBoundsException.))))
  (nth [this i notFound]
       (case i
             0 e
             1 a
             2 v
             3 tx
             4 added
             notFound)))

(defn create [e a v tx added]
  (Datom. e a v tx added))

(defn datom? [x]
  (instance? Datom x))

(defmethod print-method Datom
  [^Datom d ^java.io.Writer w]
  (.write w "#datom[") (print-method (:e d) w)
  (.write w " ") (print-method (:a d) w)
  (.write w " ") (print-method (:v d) w)
  (.write w " ") (print-method (:tx d) w)
  (.write w " ") (print-method (:added d) w)
  (.write w "]"))
