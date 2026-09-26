(ns migratus.test.migrations
  (:require
    [clojure.test :refer [deftest is]]
    [migratus.migration.repeatable :as repeatable-mig]
    [migratus.migration.sql :as sql-mig]
    [migratus.migrations :as sut]
    [migratus.properties :as props]
    [migratus.protocols :as proto]
    [migratus.utils :as utils]))

(deftest test-parse-name
  (is (= ["20111202110600" nil "create-foo-table" ["up" "sql"]]
        (sut/parse-name "20111202110600-create-foo-table.up.sql")))
  (is (= ["20111202110600" nil "create-foo-table" ["down" "sql"]]
        (sut/parse-name "20111202110600-create-foo-table.down.sql")))
  (is (= ["20111202110600" "r" "create-foo-table" ["up" "sql"]]
         (sut/parse-name "20111202110600-R-create-foo-table.up.sql")))
  (is (= ["20111202110600" "r" "create-foo-table" ["down" "sql"]]
         (sut/parse-name "20111202110600-R-create-foo-table.down.sql"))))

(def multi-stmt-up
  (str "-- this is the first statement\n\n"
    "CREATE TABLE\nquux\n"
    "(id bigint,\n"
    " name varchar(255));\n\n"
    "--;;\n"
    "-- comment for the second statement\n\n"
    "CREATE TABLE quux2(id bigint, name varchar(255));\n"))

(def multi-stmt-down
  (str "DROP TABLE quux2;\n"
    "--;;\n"
    "DROP TABLE quux;\n"))

(deftest test-properties
  (is (nil? (props/load-properties {})))
  (is (number? (get (props/load-properties {:properties {}}) "${migratus.timestamp}")))
  (let [props (props/load-properties
                {:properties
                 {:env ["path"]
                  :map {:foo "bar"
                        :baz {:bar "foo"}}}})]
    (is (seq (get props "${path}")))
    (is (= "bar" (get props "${foo}")))
    (is (= "foo" (get props "${baz.bar}")))))

(deftest test-find-migrations
  (let [create-migrations {"20111202113000"
                           {"create-bar-table"
                            {:sql
                             {:up   "CREATE TABLE IF NOT EXISTS bar(id BIGINT);\n"
                              :down "DROP TABLE IF EXISTS bar;\n"}}}
                           "20111202110600"
                           {"create-foo-table"
                            {:sql
                             {:up   "CREATE TABLE IF NOT EXISTS foo(id bigint);\n"
                              :down "DROP TABLE IF EXISTS foo;\n"}}}}
        migrations (assoc create-migrations "20120827170200"
                                            {"multiple-statements"
                                             {:sql
                                              {:up   multi-stmt-up
                                               :down multi-stmt-down}}})]
    (is (= migrations (sut/find-migrations "migrations" ["init.sql"] nil))
        "single migrations dir")
    (is (= create-migrations (sut/find-migrations "migrations" ["init.sql" "*-multiple-*"] nil))
        "single migrations dir with glob exclusions"))
  (is (= {"20220604110500"
          {"create-foo1-table"
           {:sql
            {:down "DROP TABLE IF EXISTS foo1;"
             :up "CREATE TABLE IF NOT EXISTS foo1(id bigint);"}}}
          "20220604113000"
          {"create-bar1-table"
           {:sql
            {:down "DROP TABLE IF EXISTS bar1;"
             :up "CREATE TABLE IF NOT EXISTS bar1(id BIGINT);"}}}
          "20220604113500"
          {"create-bar2-table"
           {:sql
            {:up "CREATE TABLE IF NOT EXISTS bar2(id BIGINT);"
             :down "DROP TABLE IF EXISTS bar2;"}}}
          "20220604111500"
          {"create-foo2-table"
           {:sql
            {:down "DROP TABLE IF EXISTS foo2;",
             :up "CREATE TABLE IF NOT EXISTS foo2(id bigint);"}}}}
         (sut/find-migrations ["migrations1" "migrations2"] [] nil))
    "multiple migrations dirs")
  (is (= {"20111202110600" {"create-foo-table" {:sql {:up   "CREATE TABLE IF NOT EXISTS foo(id bigint);\n",
                                                      :down "DROP TABLE IF EXISTS TEST_SCHEMA.foo;\n"}},
                            "create-schema"    {:sql {:up "CREATE SCHEMA TEST_SCHEMA\n"}}}}
         (sut/find-migrations "migrations-with-props" [] {"${migratus.schema}" "TEST_SCHEMA"}))))

(deftest test-find-jar-migrations
  (let [dir "migrations-in-jar"
        url (java.net.URL. (str "jar:file:test/migrations-jar/migrations.jar!/" dir))]
    (is (not (nil? (utils/jar-file url))))))

(deftest test-list-migrations
  (is (= #{(sql-mig/->SqlMigration
             20111202113000
             "create-bar-table"
             "CREATE TABLE IF NOT EXISTS bar(id BIGINT);\n"
             "DROP TABLE IF EXISTS bar;\n")
           (sql-mig/->SqlMigration
             20111202110600
             "create-foo-table"
             "CREATE TABLE IF NOT EXISTS foo(id bigint);\n"
             "DROP TABLE IF EXISTS foo;\n")
           (sql-mig/->SqlMigration
             20120827170200
             "multiple-statements"
             multi-stmt-up
             multi-stmt-down)}
        (set (sut/list-migrations {:migration-dir "migrations"})))))

(deftest test-list-migrations-bad-type
  (is (empty?
        (sut/list-migrations {:migration-dir "migrations-bad-type"}))))

(deftest test-list-migrations-duplicate-type
  (is (thrown-with-msg?
        Exception
        #"Multiple migration types"
        (sut/list-migrations {:migration-dir "migrations-duplicate-type"}))))

(deftest test-list-migrations-duplicate-name
  (is (thrown-with-msg?
        Exception
        #"Multiple migrations with id"
        (sut/list-migrations {:migration-dir "migrations-duplicate-name"}))))

(deftest test-list-migrations-repeatable
  (let [migrations (sut/list-migrations {:migration-dir "migrations-repeatable"})
        by-name     (into {} (map (juxt proto/name identity)) migrations)
        table       (get by-name "create-table-quux")
        trigger     (get by-name "create-trigger-quux")]
    (is (= 2 (count migrations)))

    (is (= (sql-mig/->SqlMigration
             20220820030400
             "create-table-quux"
             "CREATE TABLE IF NOT EXISTS quux(id bigint, name varchar(255), updated_at timestamp);\n--;;\nCREATE INDEX quux_name on quux(name);\n"
             "DROP TABLE IF EXISTS quux;")
          table)
        "the regular migration parses as a plain SqlMigration")

    (is (instance? migratus.migration.repeatable.RepeatableSqlMigration trigger)
        "the R-prefixed migration parses as a RepeatableSqlMigration")
    (is (satisfies? proto/RepeatableMigration trigger))
    (is (= :r-sql (proto/migration-type trigger)))
    (is (= 20220820030300 (proto/id trigger)))
    (let [up-sql "CREATE OR REPLACE FUNCTION quux_set_updated_at()\nRETURNS TRIGGER AS $$\nBEGIN\n  NEW.updated_at = now();\n  RETURN NEW;\nEND;\n$$ LANGUAGE plpgsql;\n--;;\nDROP TRIGGER IF EXISTS quux_set_updated_at ON quux;\n--;;\nCREATE TRIGGER quux_set_updated_at\nBEFORE UPDATE ON quux\nFOR EACH ROW\nEXECUTE PROCEDURE quux_set_updated_at();\n"]
      (is (= (repeatable-mig/crc32 up-sql) (proto/checksum trigger))))))

(deftest test-list-migrations-repeatable-edn
  (let [migrations (sut/list-migrations {:migration-dir "migrations-repeatable-edn"})
        payload    "{:ns migratus.test.migration.edn.test-script\n :up-fn migrate-up}\n"]
    (is (= 1 (count migrations)))
    (let [mig (first migrations)]
      (is (instance? migratus.migration.edn.RepeatableEdnMigration mig)
          "the R-prefixed .edn migration parses as a RepeatableEdnMigration")
      (is (satisfies? proto/RepeatableMigration mig))
      (is (= :r-edn (proto/migration-type mig)))
      (is (= 20220820030300 (proto/id mig)))
      (is (= "say-hello" (proto/name mig)))
      (is (true? (proto/tx? mig :up))
          "defaults to running in a transaction, same as a regular EDN migration")
      (is (= :noop (proto/down mig {}))
          "repeatable migrations have no down")
      (is (= (repeatable-mig/crc32 payload) (proto/checksum mig))
          "checksum is derived from the whole EDN payload, not just :up-fn"))))
