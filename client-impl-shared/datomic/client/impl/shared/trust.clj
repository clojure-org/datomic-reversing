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

(ns ^:skip-wiki datomic.client.impl.shared.trust
    (:require [clojure.java.io :as io]
              [clojure.string :as string])
    (:import [java.security KeyStore]
             [java.security.cert X509Certificate CertificateFactory]))

(defn trust-store
  [trust-certs]
  (let [trust-store (KeyStore/getInstance (KeyStore/getDefaultType))
        cacerts-filename (-> (System/getProperty "java.home")
                             (str "/lib/security/cacerts")
                             (string/replace "/" java.io.File/separator))
        cacerts-file (io/make-input-stream cacerts-filename {})
        cacerts-pwd (or (System/getProperty "datomic.client.cacertsPassword")
                        "changeit") ;; the Java-defined default
        cert-factory (CertificateFactory/getInstance "X.509")
        ;; load built-in trusted cert
        builtin-cert-filename (io/resource "datomic/client/impl/shared/transactor-trust.pem")
        builtin-cert (.generateCertificate cert-factory (io/make-input-stream builtin-cert-filename {}))
        ;; load provided trusted cert
        provided-certs (map #(.generateCertificate
                              cert-factory
                              (io/make-input-stream (.getBytes ^String %) {}))
                            trust-certs)]
    (.load trust-store cacerts-file (.toCharArray cacerts-pwd))
    (.setCertificateEntry trust-store "datomic-client-builtin" builtin-cert)
    (reduce (fn [n provided-cert]
              (.setCertificateEntry trust-store (str "datomic-client-provided-" n) provided-cert)
              (inc n))
            0
            provided-certs)
    trust-store))



