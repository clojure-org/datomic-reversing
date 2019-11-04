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

(ns ^:skip-wiki datomic.client.impl.shared.spi)

(defprotocol Spi
  (-add-routing
   [spi req]
   "Add the routing keys to a partial http request map.")
  (-get-sign-params
   [spi req address]
   "Returns :cognitect.hmac-authn/sign-params from cache, or nil")
  (-refresh-sign-params
   [spi req address]
   "Async. Updates sign params. Returns channel with :cognitect.hmac-authn/sign-params
or :cognitect.anomalies/anomaly"))
