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

(ns ^{:doc "Implementation detail. Do not use."} datomic.client.api.specs.alpha
  (:require [clojure.core.async :as async]
            [clojure.spec.alpha :as s]
            [cognitect.anomalies :as anom]
            [datomic.client.api.alpha :as api])
  (:import [clojure.core.async.impl.protocols Channel]))

(s/def ::non-empty-string (s/and string? #(not (empty %))))
(defn connection?
  [x]
  (satisfies? api/Connection x))

(defn database?
  [x]
  (satisfies? api/Db x))

(defn channel?
  [x]
  (instance? Channel x))

(defn datom?
  [x]
  (instance? clojure.lang.ILookup x))

;; -------------------------------------------------------------------------------
;; DATOMIC SPECS

(s/def ::lookup-ref (s/tuple ::a ::v))

(s/def ::e (s/or :eid nat-int?
        :ident keyword?
        :lookup-ref ::lookup-ref))

(s/def ::a ::e)

(s/def ::v
  (s/or :string string?
        :boolean boolean?
        :long int?
        :keyword keyword?
        ;;:bigint :datomic.db.type/bigint
        :float float?
        :double double?
        :bigdec bigdec?
        :ref ::e
        :instant inst?
        :uuid uuid?
        :uri uri?
        :bytes bytes?))

(s/def ::t nat-int?)
(s/def ::next-t pos-int?)
(s/def ::offset nat-int?)
(s/def ::chunk pos-int?)
(s/def ::limit pos-int?)
(s/def ::database-id ::non-empty-string)
(s/def ::next-token ::non-empty-string)
(s/def ::history boolean?)

;; ident?
(s/def ::tx
  (s/or :t-or-tx-entity-id  pos-int?
        :inst inst?))

(s/def ::as-of ::tx)

;; -------------------------------------------------------------------------------
;; CLIENT API SPECS

(s/def ::attrid ::a)
(s/def ::args (s/coll-of any?))
(s/def ::components (s/every any? :max-count 4))
(s/def ::data (s/every datom?))
(s/def ::database (s/keys :req-un [::database-id ::t ::next-t]))
(s/def ::datoms nat-int?)
(s/def ::eid ::e)
(s/def ::end ::tx)
(s/def ::index #{:eavt :aevt :avet :vaet})
(s/def ::paging (s/keys :opt-un [::offset ::limit ::chunk]))
(s/def ::selector vector?)
(s/def ::start ::tx)
(s/def ::timeout nat-int?)
(s/def ::with-database (s/merge ::database
                                (s/keys :req-un [::next-token])))

(s/fdef datomic.client.api.alpha/as-of
        :args (s/cat :db database? :tx ::tx)
        :ret database?)

(s/def ::eavt-components
  (s/? (s/cat :e ::e
              :more (s/? (s/cat :a ::a
                                :more (s/? (s/cat :v ::v
                                                  :more (s/? ::t))))))))

(s/def ::aevt-components
  (s/? (s/cat :a ::a
              :more (s/? (s/cat :e ::e
                                :more (s/? (s/cat :v ::v
                                                  :more (s/? ::t))))))))

(s/def ::avet-components
  (s/? (s/cat :a ::a
              :more (s/? (s/cat :v ::v
                                :more (s/? (s/cat :e ::e
                                                  :more (s/? ::t))))))))

(s/def ::vaet-components
  (s/? (s/cat :v ::v
              :more (s/? (s/cat :a ::a
                                :more (s/? (s/cat :e ::e
                                                  :more (s/? ::t))))))))

(s/def ::datoms-args
  (s/and
    (s/merge (s/keys :req-un [::index] :opt-un [::components])
             ::paging)
    (fn [{:keys [index components]}]
      (case index
        :eavt (s/valid? ::eavt-components components)
        :aevt (s/valid? ::aevt-components components)
        :avet (s/valid? ::avet-components components)
        :vaet (s/valid? ::vaet-components components)))))

(s/fdef datomic.client.api.alpha/datoms
        :args (s/cat :db database?
                     :args (s/merge ::datoms-args ::paging))
        :ret channel?)
(s/def ::datoms-result (s/coll-of datom?))

(s/fdef datomic.client.api.alpha/db
        :args (s/cat :conn connection?)
        :ret database?)

(s/fdef datomic.client.api/db-stats
        :args (s/cat :db :database?)
        :ret channel?)
(s/def ::db-stats-result (s/keys :req-un [::datoms]))

(s/fdef datomic.client.api.alpha/pull
        :args (s/cat :db database?
                     :arg-map (s/keys :req-un [::selecto ::eid]))
        :ret channel?)
(s/def ::pull-result map?)

(s/def ::query
  (s/or :map map?
        :list vector?
        :string string?))
(s/def ::query-args (s/keys :req-un [::query
                                     ::args]
                            :opt-un [::timeout]))

(s/fdef datomic.client.api.alpha/q
        :args (s/cat :conn connection?
                     :arg-map (s/merge ::query-args ::paging))
        :ret channel?)
(s/def ::q-result (s/every (s/coll-of any?)))

(s/def ::tx-range-args
  (s/merge (s/keys :opt-un [::start ::End])))

(s/fdef datomic.client.api/tx-range
        :args (s/cat :conn connection?
                     :args (s/merge ::tx-range-args ::paging))
        :ret channel?)
(s/def ::tx-range-result (s/every (s/keys ::req-un [::t ::data])))
