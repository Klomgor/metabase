(ns metabase.driver.sql-jdbc.sync.interface
  (:require
   [honey.sql :as sql]
   [metabase.driver :as driver]
   [metabase.driver.sql-jdbc.quoting :refer [with-quoting  quote-identifier]]
   [metabase.driver.sql.query-processor :as sql.qp]))

(defmulti active-tables
  "Return a reducible sequence of maps containing information about the active tables/views, collections, or equivalent
  that currently exist in a database. Each map should contain the key `:name`, which is the string name of the table.
  For databases that have a concept of schemas, this map should also include the string name of the table's `:schema`.

  Two different implementations are provided in this namespace: `fast-active-tables` (the default), and
  `post-filtered-active-tables`. You should be fine using the default, but refer to the documentation for those
  functions for more details on the differences."
  {:added "0.37.1"
   :arglists '([driver
                database
                ^String schema-inclusion-filters
                ^String schema-exclusion-filters])}
  driver/dispatch-on-initialized-driver
  :hierarchy #'driver/hierarchy)

(defmulti excluded-schemas
  "Return set of string names of schemas to skip syncing tables from."
  {:added "0.37.1" :arglists '([driver])}
  driver/dispatch-on-initialized-driver
  :hierarchy #'driver/hierarchy)

(defmulti have-select-privilege?
  "Check if we have SELECT privileges for given `table`.

  Default impl is in [[metabase.driver.sql-jdbc.sync.describe-database]]."
  {:added "0.37.1" :arglists '([driver ^java.sql.Connection connection ^String table-schema ^String table-name])}
  driver/dispatch-on-initialized-driver
  :hierarchy #'driver/hierarchy)

(defmulti filtered-syncable-schemas
  "Return a set of string names of schemas that should be synced for the given database. Schemas for
  which the current DB user has no `SELECT` permissions should be filtered out. The default implementation will fetch
  a sequence of all schema names from the JDBC database metadata and filter out any schemas in `excluded-schemas`, along
  with any that shouldn't be included based on the given inclusion and exclusion patterns (see the
  `metabase.driver.sync` namespace for full explanation)."
  {:changelog-test/ignore true
   :added "0.43.0"
   :arglists '([driver
                database
                ^String schema-inclusion-patterns
                ^String schema-exclusion-patterns])}
  driver/dispatch-on-initialized-driver
  :hierarchy #'driver/hierarchy)

(defmulti database-type->base-type
  "Given a native DB column type (as a keyword), return the corresponding `Field` `base-type`, which should derive from
  `:type/*`. You can use `pattern-based-database-type->base-type` in this namespace to implement this using regex
  patterns.

  If you need to get mapping for dynamic (eg. user defined), that could require database call, check out
  the `driver/dynamic-database-types-lookup`."
  {:added "0.37.1" :arglists '([driver database-type])}
  driver/dispatch-on-initialized-driver
  :hierarchy #'driver/hierarchy)

(defmulti column->semantic-type
  "Attempt to determine the semantic-type of a field given the column name and native type. For example, the Postgres
  driver can mark Postgres JSON type columns as `:type/SerializedJSON` semantic type.

  `database-type` and `column-name` will be strings."
  {:added "0.37.1" :arglists '([driver database-type column-name])}
  driver/dispatch-on-initialized-driver
  :hierarchy #'driver/hierarchy)

(defmulti fallback-metadata-query
  "SELECT columns from a given table so we can get column metadata. By default doesn't return any rows. This can be
  overriden because SQLite is silly and only returns column information for views if the query returns a non-zero
  number of rows.

    (fallback-metadata-query :postgres \"my_database\" \"public\" \"my_table\")
    ;; -> [\"SELECT * FROM my_database.public.my_table WHERE 1 <> 1 LIMIT 0\"]"
  {:added "0.37.1" :arglists '([driver db-name-or-nil schema-name table-name])}
  driver/dispatch-on-initialized-driver
  :hierarchy #'driver/hierarchy)

(defmulti db-default-timezone
  "JDBC-specific version of of [[metabase.driver/db-default-timezone]] that takes a [[clojure.java.jdbc]] connection
  spec rather than a set of DB details. If an implementation of this method is provided, it will be used automatically
  in the default `:sql-jdbc` implementation of [[metabase.driver/db-default-timezone]].

  This exists so we can reuse this code with the application database without having to create a new Connection pool
  for the application DB.

  DEPRECATED: you can implement [[metabase.driver/db-default-timezone]] directly;
  use [[metabase.driver.sql-jdbc.execute/do-with-connection-with-options]] to get a `java.sql.Connection` for a
  Database."
  {:added "0.38.0", :arglists '([driver jdbc-spec]), :deprecated "0.48.0"}
  driver/dispatch-on-initialized-driver
  :hierarchy #'driver/hierarchy)

#_{:clj-kondo/ignore [:deprecated-var]}
(defmethod db-default-timezone :sql-jdbc
  [_driver _jdbc-spec]
  nil)

(defmulti describe-nested-field-columns
  "Return information about the nestable columns in a `table`. Required for drivers that support
  `:nested-field-columns`. Results should match the [[metabase.sync.interface/NestedFCMetadata]] schema."
  {:added "0.43.0", :arglists '([driver database table])}
  driver/dispatch-on-initialized-driver
  :hierarchy #'driver/hierarchy)

(defmulti current-user-table-privileges
  "Returns the rows of data as arrays needed to populate the table_privileges table
   with the DB connection's current user privileges.
   The data contains the privileges that the user has on the given `database`.
   The privileges include select, insert, update, and delete.

   The rows have the following keys and value types:
     - role            :- [:maybe :string]
     - schema          :- [:maybe :string]
     - table           :- :string
     - select          :- :boolean
     - update          :- :boolean
     - insert          :- :boolean
     - delete          :- :boolean

   Either:
   (1) role is null, corresponding to the privileges of the DB connection's current user
   (2) role is not null, corresponding to the privileges of the role"
  {:added "0.49.0" :arglists '([driver conn-spec & args])}
  driver/dispatch-on-initialized-driver
  :hierarchy #'driver/hierarchy)

(defmulti alter-columns-sql
  "Generate the query to be used with [[driver/alter-columns!]]."
  {:added "0.49.0",
   :arglists '([driver table-name column-definitions])
   :deprecated "0.54.0"}
  driver/dispatch-on-initialized-driver
  :hierarchy #'driver/hierarchy)

(defmulti alter-table-columns-sql
  "Generate the query to be used with [[driver/alter-table-columns!]].
  Supersedes the deprecated [[alter-columns-sql]].
  This version receives additional kw-args `opts` (as passed to [[driver/alter-table-columns!]])."
  {:added "0.54.0"
   :arglists '([driver table-name column-definitions & opts])}
  driver/dispatch-on-initialized-driver
  :hierarchy #'driver/hierarchy)

;; used for compatibility with drivers only implementing alter-columns-sql
;; remove when alter-columns-sql is deleted (v0.57+)
#_{:clj-kondo/ignore [:deprecated-var]}
(defmethod alter-table-columns-sql ::driver/driver
  [driver table-name column-definitions & _opts]
  (alter-columns-sql driver table-name column-definitions))

;; default :sql-jdbc implementation kept here rather than on alter-table-columns-sql
;; to maximize compatibility:
;; a. get-method for superclass type calls still pick up this impl
;; b. existing specialisation of sql-jdbc should remain preferred when alter-table-column-sql is called
;; we can move this impl to alter-table-columns-sql when alter-columns-sql is deleted (v0.57+)
#_{:clj-kondo/ignore [:deprecated-var]}
(defmethod alter-columns-sql :sql-jdbc
  [driver table-name column-definitions]
  (with-quoting driver
    (first (sql/format {:alter-table  (keyword table-name)
                        :alter-column (map (fn [[column-name type-and-constraints]]
                                             (vec (cons (quote-identifier column-name)
                                                        (if (string? type-and-constraints)
                                                          [[:raw type-and-constraints]]
                                                          type-and-constraints))))
                                           column-definitions)}
                       :quoted  true
                       :dialect (sql.qp/quote-style driver)))))
