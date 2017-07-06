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
ALTER TABLE migratus.schema_migrations ADD COLUMN applied datetime;
```
