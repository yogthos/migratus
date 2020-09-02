### 1.2.9

[Fix DB connection leak in select-migrations](https://github.com/yogthos/migratus/pull/186)

### 1.2.8

fix for checking whether table exists when using the latest pg driver

### 1.2.7

allow subfolders inside the migrations folder [PR 176](https://github.com/yogthos/migratus/pull/176)

### 1.2.6

improved error reporting for invalid migation file names

### 1.2.5

fixed error for transactional mode.

### 1.2.4

[support for passing in an existing connection](https://github.com/yogthos/migratus/pull/172)

### 1.2.3

censor passworrd in logs [PR 166](https://github.com/yogthos/migratus/pull/166)

### 1.2.2

fix doc strings, [file system lookup for pending migrations](https://github.com/yogthos/migratus/commit/6bd8948b452a4ba909e1f978f7a33422e47b3d9e)

### 1.2.1

type hints for compiling with Graal

### 1.1.1

[pr](https://github.com/yogthos/migratus/pull/151) that adds the optional `-- expect` sanity check in migrations
[pr](https://github.com/yogthos/migratus/pull/150) that adds `:tx-handles-ddl?` flag that skips the automatic down that occurs on exception

### 1.1.0

- switched to use `db-do-commands` when applying migrations to address [issue 149](https://github.com/yogthos/migratus/issues/149)

### 1.0.9

[PR 144](https://github.com/yogthos/migratus/pull/144) removed \n in SQL to also allow windows line terminators.

### 1.0.8

alter migration function to return nil if successful, `:ignore` or `:failure` when migrations are incomplete.
Add support for Thread cancellation during migrations.
Tests added for backout.

### 1.0.7

Update dependency on `org.clojure/tools.logging` to 0.4.1
Update dependency on `org.clojure/java.jdbc` to 0.7.7
Fix issue with handling directories that have spaces.

### 1.0.6

search Context classloader as fall back to system class loader for migration directory discovery

### 1.0.5
### 1.0.4

updated `migratus.migrations/timestamp` to use UTC

### 1.0.3

updated `pending-list` function to use `log/debug` as well as return names of the migrations as a vector.

### 0.9.2

### 0.9.2

Changedd `datetime` to `timestamp` as it's supported by more databases.

### 0.9.1

#### features

As of version 0.9.1 Migratus writes a human-readable description, and timestamp when the migration was applied.
This is a breaking change, as the schema for the migration table has changed. Users upgrading from pervious versions
need the following additional columns in the migrations table:

```clojure
[:applied "timestamp" "" ""]
[:description "VARCHAR(1024)" "" ""]
```

or

```sql
ALTER TABLE migratus.schema_migrations ADD COLUMN description varchar(1024);
--;;
ALTER TABLE migratus.schema_migrations ADD COLUMN applied timestamp with time zone;
```
