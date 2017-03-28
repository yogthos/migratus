(ns migratus.test.migrations
  (:require [clojure.test :refer :all]
            [migratus.migration.sql :as sql-mig]
            [migratus.migrations :refer :all]))

(deftest test-parse-name
  (is (= ["20111202110600" "create-foo-table" ["up" "sql"]]
         (parse-name "20111202110600-create-foo-table.up.sql")))
  (is (= ["20111202110600" "create-foo-table" ["down" "sql"]]
         (parse-name "20111202110600-create-foo-table.down.sql"))))

(def multi-stmt-up (str "-- this is the first statement\n\n"
                        "CREATE TABLE\nquux\n"
                        "(id bigint,\n"
                        " name varchar(255));\n\n"
                        "--;;\n"
                        "-- comment for the second statement\n\n"
                        "CREATE TABLE quux2(id bigint, name varchar(255));\n"))

(def multi-stmt-down (str "DROP TABLE quux2;\n"
                          "--;;\n"
                          "DROP TABLE quux;\n"))

(deftest test-find-migrations
  (is (= {"20111202113000"
          {:sql
           {:up   {:id        "20111202113000"
                   :name      "create-bar-table"
                   :content   "CREATE TABLE IF NOT EXISTS bar(id BIGINT);\n"}
            :down {:id        "20111202113000"
                   :name      "create-bar-table"
                   :content   "DROP TABLE IF EXISTS bar;\n"}}}
          "20111202110600"
          {:sql
           {:up   {:id        "20111202110600"
                   :name      "create-foo-table"
                   :content   "CREATE TABLE IF NOT EXISTS foo(id bigint);\n"}
            :down {:id        "20111202110600"
                   :name      "create-foo-table"
                   :content   "DROP TABLE IF EXISTS foo;\n"}}}
          "20120827170200"
          {:sql
           {:up   {:id        "20120827170200"
                   :name      "multiple-statements"
                   :content   multi-stmt-up}
            :down {:id        "20120827170200"
                   :name      "multiple-statements"
                   :content   multi-stmt-down}}}}
         (find-migrations "migrations" #{"init.sql"}))))

(deftest test-find-jar-migrations
  (is (= {"20111214173500"
          {:sql
           {:up   {:id        "20111214173500"
                   :name      "create-baz-table"
                   :content   "CREATE TABLE IF NOT EXISTS baz(id bigint);\n"}
            :down {:id        "20111214173500"
                   :name      "create-baz-table"
                   :content "DROP TABLE IF EXISTS baz;\n"}}}}
         (find-migrations "jar-migrations" #{"init.sql"}))))

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
         (set (list-migrations {:migration-dir "migrations"})))))

(deftest test-list-migrations-bad-type
  (is (thrown-with-msg?
       Exception
       #"Unknown type 'foo' for migration"
       (list-migrations {:migration-dir "migrations-bad-type"}))))

(deftest test-list-migrations-duplicate-type
  (is (thrown-with-msg?
       Exception
       #"Multiple migration types"
       (list-migrations {:migration-dir "migrations-duplicate-type"}))))

(deftest test-list-migrations-duplicate-name
  (is false "implement me"))
