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

(ns ^:skip-wiki datomic.client.impl.shared.io.specs
    (:require
     [clojure.spec.alpha :as s]
     [cognitect.anomalies :as anom]
     [cognitect.http-client :as http-client]
     cognitect.http-client.specs
     [datomic.client.impl.shared.io :as dio]))

(s/def ::non-empty-string? (s/and string? #(not (empty? %))))
(s/def ::op qualified-keyword?)
(s/def ::database-id ::non-empty-string?)
(s/def ::client-req (s/keys :req-un [::op ::database-id]))

;; http-client/submit-request minus the server address elements and uri,
;; which are added later by spi/-add-routing.
(s/def ::http-request-sans-server-and-uri (s/keys :req-un [::http-client/request-method
                                                           ::http-client/body
                                                           ::http-client/headers]
                                                  :opt [::http-client/timeout-msec
                                                        ::http-client/meta]
                                                  :opt-un [::http-client/query-string]))

(s/def ::status pos-int?)
(s/def ::headers map?)
(s/def ::body string?)
(s/def ::http-result (s/keys :req-un [::status ::headers]
                             :opt-un [::body]))
(s/def ::http-error (s/and ::anom/anomaly
                           (s/keys :req-un [::http-result])))
