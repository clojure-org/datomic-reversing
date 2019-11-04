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

(ns datomic.client.api.alpha
    "Client library for interacting with Datomic.

  Functions that communicate with a separare process are asynchronous
  and return a core.async channel. In the case of an error, an error
  map is returned which will return true from 'error?'

  All async, channel-returning functions in this namespace
  allow an optional :timeout argument in their arg-map.

  Functions that return chunked results will return a succession of
  vectors of values in a channel. The channel will be closed when the
  results are exhausted.  If there is an error it will be in the
  channel instead of the chunk.

  Functions that return datoms return values of a type that supports
  indexed (count/nth) access of [e a v t added] as well as
  lookup (keyword) access via :e :a :v :t :added.")

(defprotocol SystemConnection
  (list-databases [_ arg-map]
 "Async, see also datomic.client namespace doc.
  Lists all databases. arg-map requires no keys but can contain any of
  the optional keys listed in the namespace doc.

  Returns a channel with a collection of database names.")
  (connect [_ db-name] "Async, seel also namespace doc.
Returns a channel with a DbConnection."))

(defprotocol Connection
  (shutdown [_]
 "Shuts down this connection, releasing any resources that might be
  held open. This is an optional method, and callers are not expected
  to call it, but can if they want to explicitly release any open
  resources. Once a connection has been shutdown, it should not be
  used to make any more requests."))

(defprotocol DbConnection
  (db [_] "Returns the current database value which implements the Db protocol.")
  (log [_] "Given a connection, returns a log descriptor usable in query.")
  (q [_ m]
 "Async and chunked, see also namespace doc.

  Performs the query described by arg-map:

  :query - The query to perform. A map, list, or string (see below).
  :args - data sources for the query, e.g. a database value
    retrieved from a call to db, a list of lists, and/or rules.
  :offset - Optional. Number of results to omit from the beginning of
    the returned data.
  :limit - Optional. Maximum total number of results that will be
    returned. Specify -1 to indicate no limit. Defaults to 1000.
  :chunk - Optional. Maximum number of results that will be returned
    for each chunk, up to 10000. Defaults to 1000.
  :timeout - Optional. Amount of time in milliseconds after which
    the query may be cancelled. Defaults to 60000.

  :args are data sources i.e. a database value retrieved from
  a call to db, a list of lists, and/or rules. If only one data
  source is provided, no :in section is required, else the :in section
  describes the inputs.

  :query can be a map, list, or string:

  The query map form is {:find vars-and-aggregates
                         :with vars-included-but-not-returned
                         :in sources
                         :where clauses}
  where vars, sources and clauses are lists.

  :with is optional, and names vars to be kept in the aggregation set but
  not returned.

  The query list form is [:find ?var1 ?var2 ...
                          :with ?var3 ...
                          :in $src1 $src2 ...
                          :where clause1 clause2 ...]
  The query list form is converted into the map form internally.

  The query string form is a string which, when read, results in a
  query list form or query map form.

  Query parse results are cached.

  Returns a channel that yields chunked query result tuples.")
  (tx-range [_ m]
 "Async and chunked, see also namespace doc.

  Retrieve a range of transactions in the log as specified by arg-map:

  :start - Optional. The start point, inclusive, of the requested
    range (as a transaction number, transaction ID, or Date) or nil to
    start from the beginning of the transaction log.
  :end - Optional. The end point, exclusive, of the requested
    range (as a transaction number, transaction ID, or Date) or nil to
    return results through the end of the transaction log.
  :offset - Optional. Number of results to omit from the beginning of
    the returned data.
  :limit - Optional. Maximum total number of results that will be
    returned. Specify -1 to indicate no limit. Defaults to 1000.
  :chunk - Optional. Maximum number of results that will be returned
    for each chunk, up to 10000. Defaults to 1000.

  Returns a channel of chunked transactions.
  Transactions are maps with keys:

  :t - the T point of the transaction
  :data - a collection of the datoms asserted/retracted by the
    transaction")
  (transact [_ m]
"Async, see also namespace doc.

  Submits a transaction specified by arg-map:

  :tx-data - A list of write operations, each of which is either an
    assertion, a retraction or the invocation of a data function. Each
    nested list starts with a keyword identifying the operation followed
    by the arguments for the operation. Write operations may also be
    maps from attribute identifiers to values to be asserted.

  Returns a promise channel that can be used to monitor the
  completion of the transaction. See (doc datomic.client). If the
  transaction commits, the channel's value is a map with the following
  keys:

  :db-before - Database value before the transaction
  :db-after - Database value after the transaction
  :tx-data - Collection of Datoms produced by the transaction
  :tempids - A map from tempids to their resolved entity IDs.")
  (with-db [_]))

;; returned by a call to db
(defprotocol Db
  (as-of [_ t]
 "Returns the value of the database as of some point t, inclusive. t
  can be a transaction number, transaction ID, or Date.")
  (datoms [_ m]
"Async and chunked, see also namespace doc.
Returns datoms from an index as specified by arg-map:

  :index - One of :eavt, :aevt, :avet, or :vaet, indicating the
    desired index. EAVT, AEVT, and AVET indexes contain all datoms.
    VAET contains only datoms for attributes of :db.type/ref.
  :components - Optional. A vector in the same order as the index
    containing one or more values to further narrow the result
  :offset - Optional. Number of results to omit from the beginning of
    the returned data.
  :limit - Optional. Maximum total number of results that will be
    returned. Specify -1 to indicate no limit. Defaults to 1000.
  :chunk - Optional. Maximum number of results that will be returned
    for each chunk, up to 10000. Defaults to 1000.

Returns a channel which yields chunks of datoms.")
  (db-stats [_]
"Queries for database stats. Returns a promise channel with a map
including at least:

  :datoms - total count of datoms in the database, including history.")
  (history [_]
"Returns a database value containing all assertions and
retractions across time. A history database can be used for datoms and
index-range calls and queries, but not for with calls.  Note that
queries against a history database will include retractions as well as
assertions. These can be distinguished by the fifth datom field
':added', which is true for asserts and false for retracts.")
  (index-range [_ arg-map]
"Async and chunked, see also namespace doc.
Returns datoms from the AVET index as specified by arg-map:

  :attrid - An attribute keyword or ID.
  :start - Optional. The start point, inclusive, of the requested
    range (as a transaction number, transaction ID, or Date) or
    nil/absent to start from the beginning.
  :end - Optional. The end point, exclusive, of the requested range (as a
    transaction number, transaction ID, or Date) or nil/absent to return
    results through the end of the attribute index.
  :offset - Optional. Number of results to omit from the beginning of
    the returned data.
  :limit - Optional. Maximum total number of results that will be
    returned. Specify -1 to indicate no limit. Defaults to 1000.
  :chunk - Maximum number of results that will be returned for each
    chunk, up to 10000. Defaults to 1000.

Returns a channel which yields chunks of datoms.")
  (pull [_ arg-map]
"Returns a promise channel with a hierarchical selection
described by arg-map:

  :selector - the selector expression
  :eid - an entity id")
  (since [_ t]
 "Returns the value of the database since some point t, exclusive.
  t is a transaction number, transaction ID, or Date.")
  (with [_ m]
 "Async, see also namespace doc.

  Applies tx-data to a database that must have been returned from
  'with-db' or a prior call to 'with'.  The result of calling 'with'
  is as if the data was applied in a transaction, but the source of
  the database is unaffected.

  Takes data in the same format expected by transact, and returns a
  promise channel similar to the return of transact."))

