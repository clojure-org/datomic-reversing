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

(ns ^{:doc "Alpha, subject to change.

This is not a full or final protocol spec, do not use as a basis for
client implementations."}
    datomic.client.protocol
  (:require [clojure.spec.alpha :as s]))

(defmulti request-message :op)
(defmulti response-message :op)

(s/def :datomic.client.protocol/op keyword?)
(s/def :datomic.client.protocol/db-name string?)

(s/def :datomic.client.protocol.response/body string?)
(s/def :datomic.client.protocol.response.error/status (s/int-in 400 599))

(s/def :datomic.client.protocol.response/error (s/keys :req-un [:datomic.client.protocol.response.error/status
                                                                :datomic.client.protocol.response/body]))

(s/def :datomic.client.protocol/database-id string?)
(s/def :datomic.client.protocol/t pos-int?)
(s/def :datomic.client.protocol/next-t pos-int?)

(s/def :datomic.client.protocol/t-arg (s/or :t :datomic.client.protocol/t
                                            :tx-eid pos-int?
                                            :date inst?))
(s/def :datomic.client.protocol/as-of :datomic.client.protocol/t-arg)
(s/def :datomic.client.protocol/since :datomic.client.protocol/t-arg)
(s/def :datomic.client.protocol/history boolean?)
(s/def :datomic.client.protocol/next-token string?)
(s/def :datomic.client.protocol/db-desc (s/and (s/keys :req-un [:datomic.client.protocol/database-id
                                                                :datomic.client.protocol/t]
                                                       :opt-un [:datomic.client.protocol/next-t
                                                                :datomic.client.protocol/as-of
                                                                :datomic.client.protocol/since
                                                                :datomic.client.protocol/history
                                                                :datomic.client.protocol/next-token])
                                               #(or (not (:next-t %))
                                                    (> (:next-t %) (:t %)))))

(s/def :datomic.client.protocol/dbs (s/coll-of :datomic.client.protocol/db-desc))
(s/def :datomic.client.protocol/db-arg (s/keys :req-un [:datomic.client.protocol/database-id
                                                        :datomic.client.protocol/t]
                                               :opt-un [:datomic.client.protocol/as-of
                                                        :datomic.client.protocol/since]))


(s/def :datomic.client.protocol/request (s/keys :req-un [:datomic.client.protocol/op]))
(s/def :datomic.client.protocol/response (s/keys :req-un [:datomic.client.protocol/op]
                                     :opt-un [:datomic.client.protocol/dbs]))


(s/def :datomic.client.protocol/index #{:eavt :aevt :avet :vaet})


(s/def :datomic.client.protocol/chunk pos-int?)
(s/def :datomic.client.protocol/limit (s/or :no-limit #{-1}
                                :limit pos-int?))
(s/def :datomic.client.protocol/offset nat-int?)
(s/def :datomic.client.protocol/read-request (s/keys :opt-un [:datomic.client.protocol/chunk
                                                  :datomic.client.protocol/limit
                                                  :datomic.client.protocol/offset]))

(s/def :datomic.client.protocol/data (s/coll-of any?))
(s/def :datomic.client.protocol/next-offset pos-int?)
(s/def :datomic.client.protocol/read-response (s/and
                                   (s/keys :req-un [:datomic.client.protocol/data]
                                           :opt-un [:datomic.client.protocol/chunk
                                                    :datomic.client.protocol/next-token
                                                    :datomic.client.protocol/next-offset])
                                   (fn [{:keys [chunk next-token next-offset]}]
                                     (or (and chunk next-token next-offset)
                                         (= nil chunk next-token next-offset)))))

;; status
(defmethod request-message :status [_]
  :datomic.client.protocol/request) ;; will need db identity in request at some point

(defmethod response-message :status [_]
  (s/merge :datomic.client.protocol/response
           :datomic.client.protocol/db-desc))

;; q
(s/def :datomic.client.protocol.q/op #{:q})
(s/def :datomic.client.protocol.q/query (s/or :map map?
                                  :list (s/coll-of any?)
                                  :string string?))
(s/def :datomic.client.protocol.q/args (s/* any?))
(s/def :datomic.client.protocol.q/timeout pos-int?)
(s/def :datomic.client.protocol.q/data (s/coll-of any?))
(s/def :datomic.client.protocol.q/count nat-int?)

(defmethod request-message :q [_]
  (s/merge :datomic.client.protocol/request
           :datomic.client.protocol/read-request
           (s/keys :req-un [:datomic.client.protocol.q/query
                            :datomic.client.protocol.q/args]
                   :opt-un [:datomic.client.protocol.q/timeout])))

(defmethod response-message :q [_]
  (s/merge :datomic.client.protocol/response
           :datomic.client.protocol/read-response
           (s/keys :req-un [:datomic.client.protocol.q/count]))) 

;; datoms
(s/def :datomic.client.protocol.datoms/data (s/coll-of any?))

(defmethod request-message :datoms [_]
  (s/merge :datomic.client.protocol/request
           :datomic.client.protocol/db-desc
           :datomic.client.protocol/read-request
           (s/keys :req-un [:datomic.client.protocol/index]
                   :opt-un [:datomic/e
                            :datomic/a
                            :datomic/v])))

(defmethod response-message :datoms [_]
  (s/merge :datomic.client.protocol/response
           :datomic.client.protocol/read-response
           (s/keys :req-un [:datomic.client.protocol.datoms/data])))

;; index-range
;; see DATOMIC-1955
;; (s/def :datomic.client.protocol.index-range/attrid :datomic/a)
;; (s/def :datomic.client.protocol.index-range/start :datomic/v)
;; (s/def :datomic.client.protocol.index-range/end :datomic/v)
;; (s/def :datomic.client.protocol.index-range/data (s/coll-of any?))

(defmethod request-message :index-range [_]
  (s/merge :datomic.client.protocol/request
           :datomic.client.protocol/db-desc
           :datomic.client.protocol/read-request
           (s/keys :req-un [:datomic.client.protocol.index-range/attrid]
                   :opt-un [:datomic.client.protocol.index-range/start
                            :datomic.client.protocol.index-range/end])))

(defmethod response-message :index-range [_]
  (s/merge :datomic.client.protocol/response
           :datomic.client.protocol/read-response
           (s/keys :req-un [:datomic.client.protocol.index-range/data])))

;; pull
(s/def :datomic.client.protocol.pull/selector (s/or :coll (s/coll-of any?)
                                                    :str string?))
#_(s/def :datomic.client.protocol.pull/eid :datomic/e)
(s/def :datomic.client.protocol.pull/result map?)

(defmethod request-message :pull [_]
  (s/merge :datomic.client.protocol/request
           :datomic.client.protocol/db-desc
           (s/keys :req-un [:datomic.client.protocol.pull/eid
                            :datomic.client.protocol.pull/selector])))

(defmethod response-message :pull [_]
  (s/merge :datomic.client.protocol/response
           (s/keys :req-un [:datomic.client.protocol.pull/result])))

;; next
;; really want one of the other data formats
(s/def :datomic.client.protocol.next/data (s/or :q :datomic.client.protocol.q/data
                                    :datoms :datomic.client.protocol.datoms/data
                                    :index-range :datomic.client.protocol.index-range/data
                                    :tx-range :datomic.client.protocol.tx-range/data))
(defmethod request-message :next [_]
  (s/merge :datomic.client.protocol/request
           (s/keys :req-un [:datomic.client.protocol/next-token
                            :datomic.client.protocol/offset
                            :datomic.client.protocol/chunk])))

(defmethod response-message :next [_]
  (s/merge :datomic.client.protocol/response
           :datomic.client.protocol/read-response
           (s/keys :req-un [:datomic.client.protocol.next/data])))

;; transact
(s/def :datomic.client.protocol.transact/tx-id uuid?) ;; how to differentiate from tx-id in API spec, where it is an integer eid
(s/def :datomic.client.protocol.transact/database-id string?)
(s/def :datomic.client.protocol.transact/tx-data (s/or :coll (s/coll-of any?)
                                                       :str string?))
(s/def :datomic.client.protocol.transact/tempids (s/map-of string? pos-int?))
(s/def :datomic.client.protocol.transact/db-before :datomic.client.protocol/db-desc)
(s/def :datomic.client.protocol.transact/db-after :datomic.client.protocol/db-desc)

(defmethod request-message :transact [_]
  (s/merge :datomic.client.protocol/request
           (s/keys :req-un [:datomic.client.protocol.transact/tx-id
                            :datomic.client.protocol/database-id
                            :datomic.client.protocol.transact/tx-data])))

(defmethod response-message :transact [_]
  (s/merge :datomic.client.protocol/response
           (s/and (s/keys :req-un [:datomic.client.protocol.transact/tx-data
                                   :datomic.client.protocol.transact/tempids
                                   :datomic.client.protocol.transact/db-before
                                   :datomic.client.protocol.transact/db-after]
                          :opt-un [:datomic.client.protocol/dbs])
                  ;; TBD: do we want to check this - slows down response generation
                  ;; #(= (get-in % [:before :next-t])
                  ;;     (get-in % [:after :t]))
                  ;; #(= (get-in % [:before :database-id])
                  ;;     (get-in % [:after :database-id]))
                  )))

;; with-db
(defmethod request-message :with-db [_]
  (s/merge :datomic.client.protocol/request
           :datomic.client.protocol/db-desc
           (s/keys :req-un [:datomic.client.protocol/database-id
                            :datomic.client.protocol/t])))

(defmethod response-message :with-db [_]
  (s/merge :datomic.client.protocol/response
           (s/keys :req-un [:datomic.client.protocol/next-token
                            :datomic.client.protocol/database-id
                            :datomic.client.protocol/t
                            :datomic.client.protocol/next-t])))

;; with
(defmethod request-message :with [_]
  (s/merge :datomic.client.protocol/request
           :datomic.client.protocol/db-desc
           (s/keys :req-un [:datomic.client.protocol/next-token
                            ;; does tx-id get added to tx entity correctly?
                            ;; :datomic.client.protocol.transact/tx-id
                            :datomic.client.protocol/database-id
                            :datomic.client.protocol.transact/tx-data])))

(defmethod response-message :with [_]
  (s/merge :datomic.client.protocol/response
           (s/and (s/keys :req-un [:datomic.client.protocol.transact/tx-data
                                   :datomic.client.protocol.transact/tempids
                                   :datomic.client.protocol.transact/db-before
                                   :datomic.client.protocol.transact/db-after])
                  ;; TBD: do we want to check this - slows us way down
                  ;; #(= (get-in % [:before :next-t])
                  ;;     (get-in % [:after :t]))
                  ;; #(= (get-in % [:before :database-id])
                  ;;     (get-in % [:after :database-id]))
                  )))



;; tx-range
;; see DATOMIC-1955
;; (s/def :datomic.client.protocol.tx-range/start :datomic/tx)
;; (s/def :datomic.client.protocol.tx-range/end :datomic/tx)
;; TBD: narrow data definition to describe tx maps?
(s/def :datomic.client.protocol.tx-range/data (s/coll-of any?))

(defmethod request-message :tx-range [_]
  (s/merge :datomic.client.protocol/request
           :datomic.client.protocol/read-request
           (s/keys :req-un [:datomic.client.protocol/database-id] 
                   :opt-un [:datomic.client.protocol.tx-range/start
                            :datomic.client.protocol.tx-range/end])))

(defmethod response-message :tx-range [_]
  (s/merge :datomic.client.protocol/response
           :datomic.client.protocol/read-response
           (s/keys :req-un [:datomic.client.protocol.tx-range/data])
           ;; TBD: need to spec response to tx-range op
           ))

;; db-stats
(s/def :datomic.client.protocol.db-stats/datoms nat-int?)
(s/def :datomic.client.protocol.db-stats/result (s/keys :req-un [:datomic.client.protocol.db-stats/datoms]))
(defmethod request-message :db-stats [_]
  (s/merge :datomic.client.protocol/request
           :datomic.client.protocol/read-request
           (s/keys :req-un [:datomic.client.protocol/database-id])))

(defmethod response-message :db-stats [_]
  (s/merge :datomic.client.protocol/response
           (s/keys :req-un [:datomic.client.protocol.db-stats/result])))

(s/def :datomic.client.protocol.catalog/cause string?)

(s/def :datomic.client.protocol.list-dbs/result (s/coll-of any?))

(defmethod request-message :datomic.catalog/list-dbs [_]
  :datomic.client.protocol/request)

(defmethod request-message :datomic.catalog/resolve-db [_]
  :datomic.client.protocol/request)

(defmethod response-message :datomic.catalog/list-dbs [_]
  (s/or :result (s/keys :req-un [:datomic.client.protocol.list-dbs/result])
        :error (s/keys :req-un [:datomic.client.protocol.catalog/cause])))

(s/def :datomic.client.protocol.create-db/result keyword?)
(defmethod request-message :datomic.catalog/create-db [_]
  (s/merge :datomic.client.protocol/request
           (s/keys :req-un [:datomic.client.protocol/db-name])))

(defmethod response-message :datomic.catalog/create-db [_]
  (s/or :result (s/keys :req-un [:datomic.client.protocol.create-db/result])
        :error (s/keys :req-un [:datomic.client.protocol.catalog/cause])))

(s/def :datomic.client.protocol.delete-db/result keyword?)
(defmethod request-message :datomic.catalog/delete-db [_]
  (s/merge :datomic.client.protocol/request
           (s/keys :req-un [:datomic.client.protocol/db-name])))

(defmethod response-message :datomic.catalog/delete-db [_]
  (s/or :result (s/keys :req-un [:datomic.client.protocol.delete-db/result])
        :error (s/keys :req-un [:datomic.client.protocol.catalog/cause])))

(s/def :datomic.client.protocol/request-message (s/multi-spec request-message :op))
(s/def :datomic.client.protocol/response-message (s/multi-spec response-message :op))
