(ns migratus.test.migrations
  (:require [clojure.test :refer :all]
            [migratus.migration.sql :as sql-mig]
            [migratus.migrations :refer :all]
            [migratus.utils :as utils]))

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
          {"create-bar-table"
           {:sql
            {:up   "CREATE TABLE IF NOT EXISTS bar(id BIGINT);\n"
             :down "DROP TABLE IF EXISTS bar;\n"}}}
          "20111202110600"
          {"create-foo-table"
           {:sql
            {:up   "CREATE TABLE IF NOT EXISTS foo(id bigint);\n"
             :down "DROP TABLE IF EXISTS foo;\n"}}}
          "20120827170200"
          {"multiple-statements"
           {:sql
            {:up   multi-stmt-up
             :down multi-stmt-down}}}}
         (find-migrations "migrations" #{"init.sql"}))))

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
         (set (list-migrations {:migration-dir "migrations"})))))

(deftest test-list-migrations-bad-type
  (is (empty?
        (list-migrations {:migration-dir "migrations-bad-type"}))))

(deftest test-list-migrations-duplicate-type
  (is (thrown-with-msg?
        Exception
        #"Multiple migration types"
        (list-migrations {:migration-dir "migrations-duplicate-type"}))))

(deftest test-list-migrations-duplicate-name
  (is (thrown-with-msg?
        Exception
        #"Multiple migrations with id"
        (list-migrations {:migration-dir "migrations-duplicate-name"}))))




