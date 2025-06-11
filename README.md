# Migratus

[![CircleCI](https://dl.circleci.com/status-badge/img/gh/yogthos/migratus/tree/master.svg?style=svg)](https://dl.circleci.com/status-badge/redirect/gh/yogthos/migratus/tree/master)

![MIGRATE ALL THE THINGS!](https://cdn.rawgit.com/yogthos/migratus/master/migrate.png)

A general migration framework, with implementations for migrations as SQL
scripts or general Clojure code.

Designed to be compatible with a git based work flow where multiple topic
branches may exist simultaneously, and be merged into a master branch in
unpredictable order.

This is accomplished two ways:

1. Migration ids are not assumed to be incremented integers.  It is recommended that they be timestamps (e.g. '20111202091200').
2. Migrations are considered for completion independently.

Using a 14 digit timestamp will accommodate migrations granularity to a second,
reducing the chance of collisions for a distributed team.

In contrast, using a single global version for a store and incremented
integers for migration versions, it is possible for a higher numbered
migration to get merged to master and deployed before a lower numbered
migration, in which case the lower numbered migration would never get run,
unless it is renumbered.

Migratus does not use a single global version for a store. It considers each
migration independently, and runs all uncompleted migrations in sorted order.

## Quick Start

- add the Migratus dependency:

[![Clojars Project](https://img.shields.io/clojars/v/migratus.svg)](https://clojars.org/migratus)
[![Open Source Helpers](https://www.codetriage.com/yogthos/migratus/badges/users.svg)](https://www.codetriage.com/yogthos/migratus)

- Add the following code to
 `resources/migrations/20111206154000-create-foo-table.up.sql`

  `CREATE TABLE IF NOT EXISTS foo(id BIGINT);`

- Add the following code to
 `resources/migrations/20111206154000-create-foo-table.down.sql`

  `DROP TABLE IF EXISTS foo;`

### Multiple Statements

If you would like to run multiple statements in your migration, then
separate them with `--;;`.  For example:

```sql
CREATE TABLE IF NOT EXISTS quux(id bigint, name varchar(255));
--;;
CREATE INDEX quux_name on quux(name);
```

This is necessary because JDBC does not have a method that allows you to
send multiple SQL commands for execution.  Migratus will split your
commands, and attempt to execute them inside of a transaction.

Note that some databases, such as MySQL, do not support transactional DDL
commands. If you're working with such a database then it will not be able
to rollback all the DDL statements that were applied in case a statement
fails.

### Disabling transactions

Migratus attempts to run migrations within a transaction by default.
However, some databases do not support transactional DDL statements.
Transactions can be disabled by adding the following line at the start
of the migration file:

```sql
-- :disable-transaction
```

### Running Functions in Migrations

Functions inside migrations may need to be additionally wrapped, a PostgreSQL example would look as follows:

```sql
DO $func$
 BEGIN
 PERFORM schema_name.function_name('foo', 10);
END;$func$;
```

### Supporting `use` statements

To run migrations against several different databases (in MySQL, or "schemas" in Postgres, etc.), with embedded `use` statements in your migrations, specify the database in your migration-table-name in the connections, i.e. `database_name.table_name` not `table_name`.

### Property substitution

Migratus supports property substitution where migration files can contain placeholders with the format of `${property.name}`, these placeholders will be replaced with values found in the environment as a result of calling `(System/getenv)`.

Shell variables will be normalized into Java properties style by being lower cased and with `_` being transformed into `.`, e.g:

```
FOO_BAR - > foo.bar
```

This feature is enabled when the `:properties` flag is set in the configuration.

Migratus will look for the following default properties:

* `migratus.schema`
* `migratus.user`
* `migratus.database`
* `migratus.timestamp` (defaults to the value of `(.getTime (java.util.Date.))`)

Additional property can be specified using the `:env` key or by providing a map of custom properties using the `:env` key:

```clojure
{:store :database
 :properties {:env ["database.table"]
              :map {:database {:user "bob"}}}
 :db {:dbtype   "h2"
      :dbname   "site.db"}}
```

For example, given the following template:

```sql
GRANT SELECT,INSERT ON ${database.table} TO ${database.user};
```

The environment variable associated with the `database.table` key will replace `${database.table}` tag in the template, while `{:database {:user "bob"}}` will replace `${database.user}` with `"bob"`.

### Setup

- Add Migratus as a dependency to your `project.clj`
```clojure
:dependencies [[migratus <VERSION>]]
```

Next, create a namespace to manage the migrations:

```clojure
(ns my-migrations
 (:require [migratus.core :as migratus]))

(def config {:store                :database
             :migration-dir        "migrations/"
             :init-script          "init.sql" ;script should be located in the :migration-dir path
             ;defaults to true, some databases do not support
             ;schema initialization in a transaction
             :init-in-transaction? false
             :migration-table-name "foo_bar"
             :db {:dbtype "h2"
                  :dbname "site.db"}})

;initialize the database using the 'init.sql' script
(migratus/init config)

;apply pending migrations
(migratus/migrate config)

;rollback the migration with the latest timestamp
(migratus/rollback config)

;bring up migrations matching the ids
(migratus/up config 20111206154000)

;bring down migrations matching the ids
(migratus/down config 20111206154000)
```

#### Alternative setup

It is possible to pass a `java.sql.Connection` or `javax.sql.DataSource` in place of a db spec map, e.g:

```clojure
(ns my-migrations
  (:require [next.jdbc :as jdbc]))

(def connection (jdbc/get-connection
                  {:dbtype "h2"
                   :dbname "site.db"}))

(def config {:db {:connection connection}})

;; With next.jdbc >= 1.2.790 you can use {:connection-uri ...} format (as well as raw {:datasource ...} without :user/:password).
(def config {:db {:connection-uri ...}})

;; Migratus will close the connection by default
;; providing :managed-connection? hint allows managing the state of the connection externally
;; in case you wish to reuse the connection for other purposes
(def config {:connection conn :managed-connection? true})

```

```clojure
(ns my-migrations
  (:require [hikari-cp.core :as hk]))
;; Hikari: https://github.com/tomekw/hikari-cp

(def datasource-options {:adapter "h2"
                         :url     "jdbc:h2:./site.db"})

(def config {:db {:datasource (hk/make-datasource datasource-options)}})
```

#### Running as native image (Postgres only)

[PGMig](https://github.com/leafclick/pgmig) is a standalone tool built with migratus that's compiled as a standalone GraalVM native image executable.

### Generate migration files

Migratus also provides a convenience function for creating migration files:

```clojure
(migratus/create config "create-user")
;; minimal config needed to call create while specifying the destination path
(migratus/create {:migration-dir "migrations"} "create-user")
```

This will result with up/down migration files being created prefixed with the current timestamp, e.g:

```
20150701134958-create-user.up.sql
20150701134958-create-user.down.sql
```

## Code-based Migrations

Application developers often encounter situations where migrations cannot be easily expressed as a SQL script. For instance:

   - Executing programmatically-generated DDL statements
     (e.g. updating the schema of a dynamically-sharded table).
   - Transferring data between database servers.
   - Backfilling existing records with information that must be
     retrieved from an external system.

A common approach in these scenarios is to write one-off scripts which an admin must manually apply for each instance of the application, but issues arise if a script is not run or run multiple times.

Migratus addresses this problem by providing support for code-based migrations. You can write a migration as a Clojure function, and Migratus will ensure that it's run exactly once for each instance of the application.

### Defining a code-based migration

Create a code-based migration by adding a `.edn` file to your migrations directory that contains the namespace and up/down functions to run, e.g. `resources/migrations/20170331141500-import-users.edn`:

```clojure
{:ns app.migrations.import-users
 :up-fn migrate-up
 :down-fn migrate-down
 :transaction? true}
```

Migrations will run within a transaction by default, set `transaction?` to `false` to disable transactions.

Then, in `src/app/migrations/import_users.clj`:

```clojure
(ns app.migrations.import-users)

(defn migrate-up [config]
   ;; do stuff here
   )

(defn migrate-down [config]
   ;; maybe undo stuff here
   )
```

- The up and down migration functions should both accept a single
  parameter, which is the config map passed to Migratus (so your
  migrations can be configurable).
- You can omit the up or down migration by setting `:up-fn` or
  `down-fn` to `nil` in the EDN file.
- The `:up-fn` and `:down-fn` entries can optionally be a vector containing the
  migration function followed by additional args to be passed after
  the config map, e.g. `{..., :up-fn [migrate-up "arg1" :arg2], ...}`.

### Generate code-based migration files

The `migratus.core/create` function accepts an optional type parameter, which you can pass as `:edn` to create a new migration file.

```clojure
(migratus/create config "import-users" :edn)
```

### Mixing SQL and code-based migrations

You can include both SQL and code-based migrations in the same migrations directory, in which case they will be run intermixed in the order defined by their timestamps and their status stored in the same table in the migrations database. This way if there are dependencies between your SQL and code-based migrations, you can be assured that they'll run in the correct order.

## Quick Start with Leiningen

Migratus provides a Leiningen plugin:

   - Add migratus-lein as a plugin in addition to the Migratus dependency:

[![Clojars Project](https://img.shields.io/clojars/v/migratus-lein.svg)](https://clojars.org/migratus-lein)

   - Add the following key and value to your project.clj:

```clojure
:migratus {:store :database
           :migration-dir "migrations"
           :db {:dbtype "mysql"
                :dbname "//localhost/migratus"
                :user "root"
                :password ""}}
```

To apply pending migrations:

   - Run `lein migratus migrate`

To rollback the migration with the last creation timestamp:

   - Run `lein migratus rollback`

Then follow the rest of the above instructions.

## Configuration

Migratus is configured via a configuration map that you pass in as its first parameter. The `:store` key describes the type of store against which migrations should be run.  All other keys/values in the configuration map are store specific.

### Databases

To run migrations against a database use a :store of :database, and specify the database connection configuration in the :db key of the configuration map.

* `:migration-dir` - directory where migration files are found
* `:exclude-scripts` - a collection of script name globs that will be excluded from migrations
* `:db` - One of `java.sql.Connection` or `javax.sql.DataSource` instance or a `next.jdbc` database spec. See next.jdbc docs for the version you are using: https://cljdoc.org/d/com.github.seancorfield/next.jdbc/1.2.780/api/next.jdbc#get-datasource
* `:command-separator` - the separator will be used to split the commands within each transaction when specified
* `:expect-results?` - allows comparing migration query results using the `-- expect n` comment
* `:tx-handles-ddl?` -  skips the automatic down that occurs on exception
* `:init-script` -  string pointing to a script that should be run when the database is initialized
* `:init-in-transaction?` - defaults to true, but some databases do not support schema initialization in a transaction
* `:migration-table-name` - string specifying a custom name for the migration table, defaults to `schema_migrations`

#### example configurations

```clojure
{:store :database
 :migration-dir "migrations"
 :exclude-scripts ["*.clj"]
 :db {:dbtype "mysql"
      :dbname "migratus"
      :user "root"
      :password ""}}
```

The `:migration-dir` key specifies the directory on the classpath in which to find SQL migration files. Each file should be named with the following pattern `[id]-[name].[direction].sql` where id is a unique integer `id` (ideally it should be a timestamp) for the migration, name is some human readable description of the migration, and direction is either `up` or `down`.

When the `expect-results?` key is set in the config, an assertion can be added to the migrations to check that the expected number of rows was updated:

```sql
-- expect 17;;
update foobar set thing = 'c' where thing = 'a';

--;;

-- expect 1;;
delete from foobar where thing = 'c';
```

If Migratus is trying to run either the up or down migration and it does not exist, then an Exception will be thrown.

See test/migrations in this repository for an example of how database migrations work.

### Modify sql fn

If you want to do some processing of the sql before it gets executed, you can provide a `:modify-sql-fn` in the config data structure to do so.
It expects a sql-string and can return either a modified sql-string or a sequence of sql-strings.
This is intended for use with http://2ndquadrant.com/en/resources/pglogical/ and similar systems, where DDL statements need to be executed via an extension-provided function.

## Usage
   Migratus can be used programmatically by calling one of the following functions:

   | Function                                | Description              |
   |-----------------------------------------|--------------------------|
   | `migratus.core/init`                      | Runs a script to initialize the database, e.g: create a new schema.                                                                                |
   | `migratus.core/create`                    | Create a new migration with the current date.                                                                                                      |
   | `migratus.core/migrate`                   | Run `up` for any migrations that have not been run. Returns `nil` if successful, `:ignore` if the table is reserved. Supports thread cancellation. |
   | `migratus.core/rollback`                  | Run `down` for the last migration that was run.                                                                                                    |
   | `migratus.core/rollback-until-just-after` | Run `down` all migrations after `migration-id`. This only considers completed migrations, and will not migrate up.                                 |
   | `migratus.core/up`                        | Run `up` for the specified migration ids. Will skip any migration that is already up.                                                              |
   | `migratus.core/down`                      | Run `down` for the specified migration ids. Will skip any migration that is already down. 
   | `migratus.core/reset`                 | Reset the database by down-ing all migrations successfully applied, then up-ing all migrations.
   | `migratus.core/pending-list`              | Returns a list of pending migrations.                                                                                                              |
   | `migratus.core/migrate-until-just-before` | Run `up` for for any pending migrations which precede the given migration id (good for testing migrations).                                        |
   | `migratus.core/squashing-list`            | Takes from-id and to-id as inputs (both inclusive) and returns the list of migrations to be squashed. 
   | `migratus.core/create-squash`             | Reads files within the specified id range and generates a new squashed migration file. It removes the original migration files and creates a new one with the last ID from the range and a user-provided name.
   | `migratus.core/squash-between`           | Updates the migration table by marking the old IDs as squashed and replacing them with the new ID. No actual migration is applied, assuming they were previously applied.

See the docstrings of each function for more details.

Migratus can also be used from leiningen if you add it as a plugin dependency.

```clojure
:plugins [[migratus-lein <VERSION>]]
```

And add a configuration :migratus key to your `project.clj`.

```clojure
:migratus {:store :database
           :migration-dir "migrations"
           :db {:dbtype "mysql"
                :dbname "migratus"
                :user "root"
                :password ""}}
```

   You can then run the following tasks:

   | Task                        | Description                                                                                |
   |-----------------------------|--------------------------------------------------------------------------------------------|
   | lein migratus create <name> | Create a new migration with the current date.                                              |
   | lein migratus migrate       | Run 'up' for any migrations that have not been run.                                        |
   | lein migratus rollback      | Run 'down' for the last migration that was run.                                            |
   | lein migratus up & ids      | Run 'up' for the specified migration ids. Will skip any migration that is already up.      |
   | lein migratus down & ids    | Run 'down' for the specified migration ids. Will skip any migration that is already down.  |
   | lein migratus reset         | Run 'down' for all migrations that have been run, and 'up' for all migrations.             |
   | lein migratus pending       | Run 'pending-list' to get all pending migrations.                                          |

## Quickstart with native Clojure projects

See [clj-migratus](https://github.com/paulbutcher/clj-migratus) for more information.

## Usage

Add the following to your `deps.edn`:

```
:aliases {:migrate {:extra-deps {com.github.paulbutcher/clj-migratus {:git/tag "v1.0.0"
                                                                      :git/sha "67d0fe5"}}
                     :main-opts ["-m" "clj-migratus"]}}
```

Create a [Migratus configuration](https://github.com/yogthos/migratus#configuration) file. This can either be `migratus.edn`:

```
{:store :database
 :migration-dir "migrations"
 :db {:dbtype "mysql"
      :dbname "migratus"
      :user "root"
      :password ""}}
```

Or (recommended) `migratus.clj`, allowing credentials to be taken from the environment:

```
{:store :database
 :db {:jdbcUrl (get (System/getenv) "JDBC_DATABASE_URL")}}
```

Then run, for example:

```
$ clj -M:migrate init
$ clj -M:migrate migrate
$ clj -M:migrate create create-user-table
```

See [Migratus Usage](https://github.com/yogthos/migratus#usage) for documentation on each command.

## Working on migratus itself

You can use either `lein` or `clj` for now as it has both project definitions.

Run tests with kaocha:

```
   #  https://cljdoc.org/d/lambdaisland/kaocha/1.68.1059/doc/4-running-kaocha-cli

   bin/kaocha --test-help

   bin/kaocha --fail-fast

   bin/kaocha --fail-fast --focus migratus.test.migration.sql/test-run-sql-migrations

   # Run only integration tests - defined in tests.edn
   bin/kaocha testcontainers
```

## License

Copyright © 2016 Paul Stadig, Dmitri Sotnikov

Licensed under the Apache License, Version 2.0.
