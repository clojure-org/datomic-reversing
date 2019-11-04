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

(ns datomic.client.impl.shared.protocols
  (:refer-clojure :exclude [sync]))

(defprotocol Client
  (administer-system [_ arg-map])
  (list-databases [_ arg-map])
  (connect [_ db-name])
  (create-database [_ arg-map])
  (delete-database [_ arg-map]))

(defprotocol Connection
  (db [_])
  (log [_])
  (q [_ m])
  (tx-range [_ m])
  (transact [_ m])
  (recent-db [_] "Returns a channel of db value or anomaly, refreshing conn if stale")
  (sync [_ t])
  (with-db [_]))

(defprotocol Db
  (as-of [_ t])
  (datoms [_ m])
  (db-stats [_])
  (history [_])
  (index-range [_ arg-map])
  (pull [_ arg-map])
  (since [_ t])
  (with [_ m]))

(defprotocol ParentConnection
  (-conn [_]))

(extend-protocol ParentConnection
  nil
  (-conn [_] nil)
  Object

  (-conn [_] nil))
