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

(ns ^:skip-wiki datomic.client.impl.shared.io
    (:require
     [clojure.edn :as edn]
     [clojure.java.io :as io]
     [cognitect.http-client :as http]
     [cognitect.transit :as transit]
     [datomic.client.impl.shared.datom :as datom]
     [datomic.client.impl.shared.exceptions :as ex :refer (anom)])
  (:import
   [com.cognitect.transit ReadHandler]
   [datomic.client.impl.shared BytesOutputStream ByteBufferInputStream]
   [java.nio ByteBuffer]))

(set! *warn-on-reflection* true)

(def read-handlers
  {"datom" (reify ReadHandler
             (fromRep [_ v] (apply datom/create v)))})

(def write-handlers
  {})

(defn- marshal
  "Encodes val as Transit data in msgpack. Returns a map with keys:

  :bytes - a Java byte array of the marshalled data
  :length - the length in bytes of the marshalled data.

  Note that for efficiency's sake the byte array may be longer than
  the returned length."
  [m]
  (let [stm (BytesOutputStream.)
        w   (transit/writer stm :msgpack {:handlers write-handlers})]
    (transit/write w m)
    {:bytes  (.internalBuffer stm)
     :length (.length stm)}))

(defn- unmarshal
  "Given a byte array containing a Transit value encoded using the specified
   decoder type and return the unmarshaled value. Defaults to :msgpack."
  ([stm]
    (unmarshal stm :msgpack))
  ([stm type]
   (let [r   (transit/reader stm type {:handlers read-handlers})
         res (transit/read r)]
     res)))

(defn- catalog-op?
  [op]
  (= "datomic.catalog" (namespace op)))

(defn- qualified-op [op]
  (if (catalog-op? op)
    (format "%s/%s" (namespace op) (name op))
    (str "datomic.client.protocol/" (name op))))

;; client req must have an op
(defn client-req->http-req
  [client-req]
  (let [{:keys [op database-id next-token]} client-req
        {:keys [bytes length]} (marshal client-req)
        content-type "application/transit+msgpack"
        qualified-op (qualified-op op)]
    {:headers (merge {"content-type" content-type
                      "accept" content-type
                      "x-nano-op" qualified-op}
                     (when next-token {"x-nano-next" (str next-token)})
                     (when database-id {"x-nano-target" database-id}))
     :request-method :post
     :op qualified-op
     :body (ByteBuffer/wrap ^bytes bytes 0 length)
     :content-length length
     :content-type content-type}))

(defn- norm-headers
  "Given request headers map, return a new request headers map with all
   headers keys lowercased."
  [headers]
  (reduce-kv
    (fn [acc ^String k v]
      (assoc acc (.toLowerCase k) v))
    {} headers))

(defn- ^ByteBufferInputStream bbuf->is [bbuf]
  (ByteBufferInputStream. bbuf))

(defn- summarize-bbuf
  [bbuf]
  (when (instance? ByteBuffer bbuf)
    (let [is (bbuf->is bbuf)
          buf (byte-array 1024)
          size (.read is buf 0 1024)]
      (String. buf 0 size "UTF-8"))))

(defn- summarize-response
  [{:keys [status headers body]}]
  (cond-> {:status status :headers headers}
          body (assoc :body (summarize-bbuf body))))

(defn- http-status->anom-cat
  "Return the anomaly kw for an http status."
  [status]
  (cond
   (= status 403) :cognitect.anomalies/forbidden
   (= status 429) :cognitect.anomalies/busy
   (= status 502) :cognitect.anomalies/unavailable
   (= status 503) :cognitect.anomalies/unavailable
   (= status 504) :cognitect.anomalies/unavailable
   (<= 400 status 499) :cognitect.anomalies/incorrect
   (<= 500 status 599) :cognitect.anomalies/fault))

(defn- http-status->response-text
  "Fallback, used only when server response body could not be unmarshalled."
  [^long status]
  (case status
        400 "Bad Request"
        403 "Forbidden"
        429 "Busy"
        500 "Internal Server Error"
        502 "Bad Gateway"
        503 "Service Unavailable"
        504 "Gateway Time-out"
        "Response body did not conform to Datomic client protocol"))

(defn- unmarshal-response
  "Given a Ring response unmarshal the ByteBuffer :body of the Ring response
using the decoding as specified by the :content-type of the response.
Returns a response map with updated :body."
  [{:keys [headers status body] :as http-resp}]
  (if body
    (let [{:strs [content-type]} (norm-headers headers)
          stm (bbuf->is body)
          res (condp = content-type
                "application/transit+msgpack"
                (unmarshal stm :msgpack)

                "application/transit+json"
                (unmarshal stm :json)

                "application/edn"
                (edn/read (java.io.PushbackReader. (io/reader stm)))

                "text/plain"
                (slurp stm)

                nil)]
      (if res
        (assoc http-resp :body res)
        {:cognitect.anomalies/category (http-status->anom-cat status)
         :cognitect.anomalies/message (http-status->response-text status)
         :http-result (summarize-response http-resp)}))
    http-resp))

(defn- http-status-anomaly
  "Returns an error based on http status if possible, or nil. This should only be
attempted after checking for better error info in the body."
  [{:keys [status body] :as http-resp}]
  (when status
    (when-let [cat (http-status->anom-cat status)]
      {:cognitect.anomalies/category cat
       :http-result (summarize-response http-resp)})))
  
(defn- http-client-kw->anom-cat
  "Return the anomaly kw for a :cognitect.http-client error"
  [code]
  (case code
        ::http/timeout :cognitect.anomalies/interrupted
        ::http/throttled :cognitect.anomalies/busy
        ::http/connect-failed :cognitect.anomalies/unavailable
        ::http/resolve-failed :cognitect.anomalies/not-found
        :cognitect.anomalies/fault))

(defn- http-client-anomaly
  "Returns an anomaly based on cognitect.http-client ::http/error codes in the body,
else nil."
  [http-res]
  (when-let [http-client-kw (::http/error http-res)]
    (merge {:cognitect.anomalies/category (http-client-kw->anom-cat http-client-kw)}
           (when-let [^Throwable t (ex/root-cause (::http/throwable http-res))]
             {:cognitect.anomalies/message (str (.getName (.getClass t)) ": " (.getMessage t))
              :datomic.client/ex (:http/throwable http-res)}))))

(defn- http-body-anomaly
  "If http body represents an anomaly, return it, else nil."
  [http-res]
  (if (:cognitect.anomalies/category (:body http-res))
    (:body http-res)
    (http-client-anomaly http-res)))

(defn http-resp->client-resp
  [res]
  (let [{:keys [status body] :as ures} (unmarshal-response res)]
    (or (anom ures)
        (http-body-anomaly ures)
        (http-status-anomaly ures)
        body)))

