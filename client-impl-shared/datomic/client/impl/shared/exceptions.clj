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

(ns datomic.client.impl.shared.exceptions)

(set! *warn-on-reflection* true)

(defn ^Throwable root-cause
  [^Throwable x]
  (when x
    (let [cause (.getCause x)]
      (if cause
        (recur cause)
        x))))

(defn throwable->anomaly
  [^Throwable t]
  {:cognitect.anomalies/category :cognitect.anomalies/fault
   :cognitect.anomalies/message (.getMessage (root-cause t))
   :datomic.client/ex t})

(defn anom
  [x]
  (when (:cognitect.anomalies/category x)
    x))

