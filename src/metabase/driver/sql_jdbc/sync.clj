(ns metabase.driver.sql-jdbc.sync
  "Implementations for sync-related driver multimethods for SQL JDBC drivers, using JDBC DatabaseMetaData."
  (:require
   [metabase.driver.sql-jdbc.sync.dbms-version :as sql-jdbc.dbms-version]
   [metabase.driver.sql-jdbc.sync.describe-database
    :as sql-jdbc.describe-database]
   [metabase.driver.sql-jdbc.sync.describe-table
    :as sql-jdbc.describe-table]
   [metabase.driver.sql-jdbc.sync.interface :as sql-jdbc.sync.interface]
   [potemkin :as p]))

(comment sql-jdbc.dbms-version/keep-me sql-jdbc.sync.interface/keep-me sql-jdbc.describe-database/keep-me sql-jdbc.describe-table/keep-me)

#_{:clj-kondo/ignore [:deprecated-var]}
(p/import-vars
 [sql-jdbc.sync.interface
  active-tables
  alter-columns-sql
  alter-table-columns-sql
  column->semantic-type
  current-user-table-privileges
  database-type->base-type
  db-default-timezone
  describe-nested-field-columns
  excluded-schemas
  fallback-metadata-query
  filtered-syncable-schemas
  have-select-privilege?]

 [sql-jdbc.describe-table
  add-table-pks
  database-type->base-type-or-warn
  describe-fields
  describe-fields-pre-process-xf
  describe-fields-sql
  describe-fks
  describe-fks-sql
  describe-indexes
  describe-indexes-sql
  describe-table
  describe-table-fields
  describe-table-fields-xf
  describe-table-fks
  describe-table-indexes
  get-catalogs
  pattern-based-database-type->base-type]

 [sql-jdbc.describe-database
  describe-database
  fast-active-tables
  post-filtered-active-tables]

 [sql-jdbc.dbms-version
  dbms-version])
