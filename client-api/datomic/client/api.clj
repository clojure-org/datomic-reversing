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

(ns datomic.client.api
  "Synchronous client library for interacting with Datomic.

This namespace is a wrapper for datomic.client.api.async.

Functions in this namespace that communicate with a separate
process take an arg-map with the following optional keys:

  :timeout   Timeout in msec.

Functions that support offset and limit take the following
additional optional keys:

  :offset    Number of results to omit from the beginning
             of the returned data.
  :limit     Maximum total number of results to return.
             Specify -1 for no limit. Defaults to -1 for q
             and to 1000 for all other APIs.

Functions that return datoms return values of a type that supports
indexed (count/nth) access of [e a v t added] as well as
lookup (keyword) access via :e :a :v :t :added.

All errors are reported via ex-info exceptions, with map contents
as specified by cognitect.anomalies.
See https://github.com/cognitect-labs/anomalies."
  (:refer-clojure :exclude [sync])
  (:require [datomic.client.api.impl :as impl]
            [datomic.client.api.protocols :as protocols]))

(defn client
  "Create a client for a Datomic system. This function does not
communicate with a server and returns immediately.

For a cloud system, arg-map requires:

  :server-type   - :cloud
  :region        - AWS region, e.g. \"us-east-1\"
  :system        - your system name
  :endpoint      - IP address of your system or query group

Optionally, a cloud system arg-map accepts:

  :creds-provider  - instance of com.amazonaws.auth.AWSCredentialsProvider. Defaults to DefaultAWSCredentialsProviderChain
  :creds-profile   - name of an AWS Named Profile. See http://docs.aws.amazon.com/cli/latest/userguide/cli-multiple-profiles.html
  :proxy-port      - local port for SSH tunnel to bastion 

  Note: :creds-provider and :creds-profile are mutually exclusive, providing both will result in an error.

For a peer-server system, arg-map requires:

  :server-type   - :peer-server
  :access-key    - access-key from peer server launch
  :secret        - secret from peer server launch
  :endpoint      - peer server host:port

Returns a client object."
  [arg-map]
  (case (:server-type arg-map)
        :ion
        (client (assoc arg-map :server-type (impl/ion-server-type)))
        
        (:cloud :peer-server)
        (impl/dynacall 'com.datomic/client
                       'datomic.client.api.sync/client
                       arg-map)

        (:peer-client)
        (impl/dynacall '(or com.datomic/datomic-pro com.datomic/datomic-free)
                       'datomic.peer-client/create-client
                       arg-map)

        :local
        (impl/dynacall 'com.datomic/client-impl-local
                       'datomic.client.impl.local/create-client
                       arg-map)
        (throw (impl/incorrect ":server-type must be one of :cloud, :local, :peer-client, or :peer-server"))))

(defn administer-system
  "Run :action on system.

Currently the only supported action is:

:upgrade-schema        upgrade an existing database to use the latest base schema

:upgrade-schema takes the following map

:db-name               database name

Returns a map of the new base schema
NOTE: create-database is not available with peer-server.
Use a Datomic Peer to create databases with Datomic On-Prem."
  [client arg-map]
  (protocols/administer-system client arg-map))

(defn list-databases
  "Lists all databases. arg-map requires no keys but can contain any of
the optional keys listed in the namespace doc.

Returns collection of database names."
  [client arg-map]
  (protocols/list-databases client arg-map))

(defn connect
  "Connects to a database. Takes a client object and an arg-map with keys:

:db-name               database name

Returns a connection. See namespace doc for error and timeout
handling.

Returned connection supports ILookup for key-based access. Supported
keys are:

:db-name               database name"
  [client arg-map]
  (protocols/connect client arg-map))

(defn create-database
  "Creates a database specified by arg-map with key:

:db-name    The database name.

Returns true. See namespace doc for error and timeout handling.
NOTE: create-database is not available with peer-server.
Use a Datomic Peer to create databases with Datomic On-Prem."
  [client arg-map]
  (protocols/create-database client arg-map))

(defn delete-database
  "Deletes a database specified by arg-map with keys:

 :db-name    The database name.

Returns true. See namespace doc for error and timeout handling.
NOTE: delete-database is not available with peer-server.
Use a Datomic Peer to delete databases with Datomic On-Prem."
  [client arg-map]
  (protocols/delete-database client arg-map))

(defn db
  "Returns the current database value for a connection.

Supports ILookup interface for key-based access. Supported keys are:

:db-name               database name
:t                     basis t for the database
:as-of                 a point in time
:since                 a point in time
:history               true for history databases"
  [conn]
  (protocols/db conn))

(defn transact
  "Submits a transaction specified by arg-map:

 :tx-data    a collection of list forms or map forms

For a complete specification of the tx-data format, see
https://docs.datomic.com/cloud/transactions/transaction-data-reference.html.

Returns a map with the following keys:

 :db-before  database value before the transaction
 :db-after   database value after the transaction
 :tx-data    collection of datoms produced by the transaction
 :tempids    a map from tempids to their resolved entity IDs.

See namespace doc for timeout and error handling."
  [conn arg-map]
  (protocols/transact conn arg-map))

(defn sync
  "Used to coordinate with other clients. Returns a database
value with basis :t >= t. Does *not* make a remote call."
  [conn t]
  (protocols/sync conn t))

(defn tx-range
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

See datoms for a description of :data value.

See namespace doc for offset/limit, timeout, and error handling."
  [conn arg-map]
  (protocols/tx-range conn arg-map))

(defn with-db
  "Returns a with-db value suitable for passing to 'with'."
  [conn]
  (protocols/with-db conn))

(defn as-of
  "Returns the value of the database as of some time-point."
  [db time-point]
  (protocols/as-of db time-point))

(defn datoms
  "Returns an Iterable of datoms from an index as specified by arg-map:

 :index       One of :eavt, :aevt, :avet, or :vaet.
 :components  Optional vector in the same order as the index
              containing one or more values to further narrow the
              result

Datoms are associative and indexed:

Key     Index        Value
--------------------------
:e      0            entity id
:a      1            attribute id
:v      2            value
:tx     3            transaction id
:added  4            boolean add/retract

For a description of Datomic indexes, see
https://docs.datomic.com/cloud/query/raw-index-access.html.

See namespace doc for timeout, offset/limit, and error handling."
  [db arg-map]
  (protocols/datoms db arg-map))

(defn db-stats
  "Queries for database stats. Returns a map including at least:

 :datoms      total count of datoms in the (history) database

See namespace doc for timeout and error handling."
  [db]
  (protocols/db-stats db))

(defn history
  "Returns a database value containing all assertions and
retractions across time. A history database can be passed to 'datoms',
'index-range', and 'q', but not to 'with' or 'pull'. Note
that queries against a history database will include retractions
as well as assertions. Retractions can be identified by the fifth
datom field ':added', which is true for asserts and false for
retracts."
  [db]
  (protocols/history db))

(defn index-range
  "Returns datoms from the AVET index as specified by arg-map:

 :attrid  An attribute entity identifier.
 :start   Optional. The start value, inclusive, of the requested
          range, defaulting to the beginning of the index.
 :end     Optional. The end value, exclusive, of the requested
          range, defaultin to the end of the index.

For a description of Datomic indexes, see
https://docs.datomic.com/cloud/query/raw-index-access.html.

Returns an Iterable of datoms. See namespace doc for
offset/limit, timeout, and error handling."
  [db arg-map]
  (protocols/index-range db arg-map))

(defn pull
  "Returns a hierarchical selection described by selector and eid.

 :selector   the selector expression
 :eid        entity id

For a complete decription of the selector syntax, see
https://docs.datomic.com/cloud/query/query-pull.html.

Returns a map.

The arity-2 version takes :selector and :eid in arg-map, which
also supports :timeout. See namespace doc."
  ([db arg-map]
   (impl/navize db nil nil (protocols/pull db arg-map)))
  ([db selector eid]
   (impl/navize db nil nil (protocols/pull db selector eid))))

(defn since
  "Returns the value of the database since some time-point."
  [db t]
  (protocols/since db t))

(defn with
  "Applies tx-data to a database returned from 'with-db' or a
 prior call to 'with'.  The result of calling 'with' is a
 database value as-if the data was applied in a transaction, but
 the durable database is unaffected.

Takes and returns data in the same format expected by transact.

See namespace doc for timeout and error handling."
  [db arg-map]
  (protocols/with db arg-map))

(defn q
  "Performs the query described by query and args:

  :query  The query to perform: a map, list, or string (see below).
  :args   Data sources for the query, e.g. database values
          retrieved from a call to db, and/or rules.

  The query list form is [:find ?var1 ?var2 ...
                          :with ?var3 ...
                          :in $src1 $src2 ...
                          :where clause1 clause2 ...]

  :find  specifies the tuples to be returned
  :with  is optional, and names vars to be kept in the aggrgation set
         but not returned
  :in    is optional. Omitting ':in ...' is the same as specifying
         ':in $'
  :where limits the result returned

For a complete description of the query syntax, see
https://docs.datomic.com/cloud/query/query-data-reference.html.

Returns a collection of tuples.

The arity-1 version takes :query and :args in arg-map, which
allows additional options for :offset, :limit, and :timeout. See
namespace doc."
  ([arg-map]
     (if-let [qable (impl/find-queryable (:args arg-map))]
       (impl/navize qable nil nil (impl/q qable arg-map))
       (throw (impl/incorrect "Query args must include a database"))))
  ([query & args]
     (q {:query query :args args})))

