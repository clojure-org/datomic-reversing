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

(ns datomic.client.api.protocols
  (:refer-clojure :exclude [sync]))

(defprotocol Client
  (administer-system
    [client arg-map]
    "Runs :action on system. Takes a client object and an arg-map with an :action key.
:action                Action to perform

Actions:
:upgrade-schema        upgrade an existing database to use the latest base schema

:upgrade-schema requires the following keys in the arg-map

:db-name               database name

Returns a truthy value on success, otherwise throws on error")

  (list-databases
   [client arg-map]
   "Lists all databases. arg-map requires no keys but can contain any of
the optional keys listed in the namespace doc.

Returns collection of database names.")
  (connect
   [client arg-map]
   "Connects to a database. Takes a client object and an arg-map with keys:

:db-name               database name

Returns a connection. See namespace doc for error and timeout
handling.

Returned connection supports ILookup for key-based access. Supported
keys are:

:db-name               database name")
  (create-database
   [client arg-map]
   "Creates a database specified by arg-map with key:

  :db-name    The database name.

Returns true. See namespace doc for error and timeout handling.
NOTE: create-database is not available with peer-server. 
Use a Datomic Peer to create databases with Datomic On-Prem.")
  (delete-database
   [client arg-map]
   "Deletes a database specified by arg-map with keys:

  :db-name    The database name.

Returns true. See namespace doc for error and timeout handling.
NOTE: delete-database is not available with peer-server. 
Use a Datomic Peer to delete databases with Datomic On-Prem."))

(defprotocol Connection
  (db
   [conn]
   "Returns the current database value for a connection.

Supports ILookup interface for key-based access. Supported keys are:

:db-name               database name
:t                     basis t for the database
:as-of                 a point in time
:since                 a point in time
:history               true for history databases")
  (transact
   [conn arg-map]
   "Submits a transaction specified by arg-map:

  :tx-data    a collection of list forms or map forms

For a complete specification of the tx-data format, see
https://docs.datomic.com/cloud/transactions/transaction-data-reference.html.

Returns a map with the following keys:

  :db-before  database value before the transaction
  :db-after   database value after the transaction
  :tx-data    collection of datoms produced by the transaction
  :tempids    a map from tempids to their resolved entity IDs.

See namespace doc for timeout and error handling.")
  (sync
      [conn t]
    "Used to coordinate with other clients. Returns a database
value with basis :t >= t. Does *not* make a remote call.")
  (tx-range
   [conn arg-map]
   "Retrieve a range of transactions in the log as specified by
arg-map:

  :start   Optional. The start time-point or nil to start from the
           beginning of the transaction log.
  :end     Optional. The end time-point, exclusive, or nil to
           run to the end of the transaction log.

  Returns an Iterable of transactions.
  Transactions have keys:

  :t       the basis t of the transaction
  :data    a collection of the datom in the transaction

See namespace doc for offset/limit, timeout, and error handling.")
  (with-db
    [conn]
    "Returns a with-db value suitable for passing to 'with'."))

(defprotocol Db
  (as-of
   [db time-point]
   "Returns the value of the database as of some time-point.")
  (datoms
   [db arg-map]
   "Returns datoms from an index as specified by arg-map:

  :index       One of :eavt, :aevt, :avet, or :vaet.
  :components  Optional vector in the same order as the index
               containing one or more values to further narrow the
               result

For a description of Datomic indexes, see
https://docs.datomic.com/cloud/query/raw-index-access.html.

Returns an Iterable of datoms. See namespace doc for timeout,
offset/limit, and error handling.")
  (db-stats
   [db]
   "Queries for database stats. Returns a map including at least:

  :datoms      total count of datoms in the (history) database

See namespace doc for timeout and error handling.")
  (history
   [db]
   "Returns a database value containing all assertions and
retractions across time. A history database can be passed to 'datoms',
'index-range', and 'q', but not to 'with' or 'pull'. Note 
that queries against a history database will include retractions
as well as assertions. Retractions can be identified by the fifth
datom field ':added', which is true for asserts and false for
retracts.")
  (index-range
   [db arg-map]
   "Returns datoms from the AVET index as specified by arg-map:

  :attrid  An attribute entity identifier.
  :start   Optional. The start value, inclusive, of the requested
           range, defaulting to the beginning of the index.
  :end     Optional. The end value, exclusive, of the requested
           range, defaultin to the end of the index.

For a description of Datomic indexes, see
https://docs.datomic.com/cloud/query/raw-index-access.html.

Returns an Iterable of datoms. See namespace doc for
offset/limit, timeout, and error handling.")
  (pull
   [db arg-map] [db selector eid]
   "Returns a hierarchical selection described by selector and eid.

  :selector   the selector expression
  :eid        entity id

For a complete decription of the selector syntax, see
https://docs.datomic.com/cloud/query/query-pull.html.

Returns a map.

The arity-2 version takes :selector and :eid in arg-map, which
also supports :timeout. See namespace doc.")
  (since
   [db t]
   "Returns the value of the database since some time-point.")
  (with
   [db arg-map]
   "Applies tx-data to a database returned from 'with-db' or a
  prior call to 'with'.  The result of calling 'with' is a
  database value as-if the data was applied in a transaction, but
  the durable database is unaffected.

Takes and returns data in the same format expected by transact.

See namespace doc for timeout and error handling."))

