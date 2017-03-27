(ns migratus.test.migrations
  (:require [clojure.test :refer :all]
            [migratus.migrations :refer :all]))

(deftest test-parse-name
  (is (= ["20111202110600" "create-foo-table" "up"]
         (parse-name "20111202110600-create-foo-table.up.sql")))
  (is (= ["20111202110600" "create-foo-table" "down"]
         (parse-name "20111202110600-create-foo-table.down.sql"))))

(deftest test-find-migrations
  (is (= {"20111202113000"
          {"down" {:id        "20111202113000"
                   :name      "create-bar-table"
                   :direction "down"
                   :content   "DROP TABLE IF EXISTS bar;\n"}
           "up"   {:id        "20111202113000"
                   :name      "create-bar-table"
                   :direction "up"
                   :content   "CREATE TABLE IF NOT EXISTS bar(id BIGINT);\n"}}
          "20111202110600"
          {"up"   {:id        "20111202110600"
                   :name      "create-foo-table"
                   :direction "up"
                   :content   "CREATE TABLE IF NOT EXISTS foo(id bigint);\n"}
           "down" {:id        "20111202110600"
                   :name      "create-foo-table"
                   :direction "down"
                   :content   "DROP TABLE IF EXISTS foo;\n"}}
          "20120827170200"
          {"up"   {:id        "20120827170200"
                   :name      "multiple-statements"
                   :direction "up"
                   :content   (str "-- this is the first statement\n\n"
                                   "CREATE TABLE\nquux\n"
                                   "(id bigint,\n"
                                   " name varchar(255));\n\n"
                                   "--;;\n"
                                   "-- comment for the second statement\n\n"
                                   "CREATE TABLE quux2(id bigint, name varchar(255));\n")}
           "down" {:id        "20120827170200"
                   :name      "multiple-statements"
                   :direction "down"
                   :content   (str "DROP TABLE quux2;\n"
                                   "--;;\n"
                                   "DROP TABLE quux;\n")}}}
         (find-migrations "migrations" #{"init.sql"}))))

(deftest test-find-jar-migrations
  (is (= {"20111214173500"
          {"down" {:id        "20111214173500"
                   :name      "create-baz-table"
                   :direction "down", :content "DROP TABLE IF EXISTS baz;\n"}
           "up"   {:id        "20111214173500"
                   :name      "create-baz-table"
                   :direction "up"
                   :content   "CREATE TABLE IF NOT EXISTS baz(id bigint);\n"}}}
         (find-migrations "jar-migrations" #{"init.sql"}))))
