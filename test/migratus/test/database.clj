;;;; Copyright © 2011 Paul Stadig
;;;;
;;;; Licensed under the Apache License, Version 2.0 (the "License"); you may not
;;;; use this file except in compliance with the License.  You may obtain a copy
;;;; of the License at
;;;;
;;;;   http://www.apache.org/licenses/LICENSE-2.0
;;;;
;;;; Unless required by applicable law or agreed to in writing, software
;;;; distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
;;;; WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
;;;; License for the specific language governing permissions and limitations
;;;; under the License.
(ns migratus.test.database
  (:require [clojure.java.io :as io]
            [clojure.java.jdbc.deprecated :as sql]
            [migratus.protocols :as proto]
            [migratus.core :as core])
  (:use [clojure.test]
        [migratus.database]))

(def config {:store :database
             :migration-dir "migrations/"
             :migration-table-name "foo_bar"
             :db {:classname "com.mysql.jdbc.Driver"
                  :subprotocol "mysql"
                  :subname "//localhost/migratus"
                  :user "root"
                  :password ""}})

(defn reset-db []
  (sql/with-connection (assoc (:db config) :subname "//localhost/mysql")
    (sql/do-commands "DROP DATABASE IF EXISTS migratus;")
    (sql/do-commands "CREATE DATABASE migratus;")))

(defn setup-test-db [f]
  (reset-db)
  (f))

(use-fixtures :each setup-test-db)

(defn verify-table-exists? [table-name]
  (sql/with-connection (:db config)
    (table-exists? table-name)))

(deftest test-make-store
  (testing "should create default table name"
    (is (not (verify-table-exists? default-table-name)))
    (proto/make-store (dissoc config :migration-table-name))
    (is (verify-table-exists? default-table-name)))
  (reset-db)
  (testing "should create schema_migrations table"
    (is (not (verify-table-exists? "foo_bar")))
    (proto/make-store config)
    (is (verify-table-exists? "foo_bar"))))

(deftest test-parse-name
  (is (= ["20111202110600" "create-foo-table" "up"]
         (parse-name "20111202110600-create-foo-table.up.sql")))
  (is (= ["20111202110600" "create-foo-table" "down"]
         (parse-name "20111202110600-create-foo-table.down.sql"))))

(deftest test-find-migrations
  (is (= {"20111202113000"
          {"down" {:id "20111202113000"
                   :name "create-bar-table"
                   :direction "down"
                   :content "DROP TABLE IF EXISTS bar;\n"}
           "up" {:id "20111202113000"
                 :name "create-bar-table"
                 :direction "up"
                 :content "CREATE TABLE IF NOT EXISTS bar(id BIGINT);\n"}}
          "20111202110600"
          {"up" {:id "20111202110600"
                 :name "create-foo-table"
                 :direction "up"
                 :content "CREATE TABLE IF NOT EXISTS foo(id bigint);\n"}
           "down" {:id "20111202110600"
                   :name "create-foo-table"
                   :direction "down"
                   :content "DROP TABLE IF EXISTS foo;\n"}}
          "20120827170200"
          {"up" {:id "20120827170200"
                 :name "multiple-statements"
                 :direction "up"
                 :content
                 (str "CREATE TABLE quux(id bigint, name varchar(255));\n"
                      "--;;\n"
                      "CREATE TABLE quux2(id bigint, name varchar(255));\n")}
           "down" {:id "20120827170200"
                   :name "multiple-statements"
                   :direction "down"
                   :content (str "DROP TABLE quux2;\n"
                                 "--;;\n"
                                 "DROP TABLE quux;\n")}}}
         (find-migrations "migrations"))))

(deftest test-find-jar-migrations
  (is (= {"20111214173500"
          {"down" {:id "20111214173500"
                   :name "create-baz-table"
                   :direction "down", :content "DROP TABLE IF EXISTS baz;\n"}
           "up" {:id "20111214173500"
                 :name "create-baz-table"
                 :direction "up"
                 :content "CREATE TABLE IF NOT EXISTS baz(id bigint);\n"}}}
         (find-migrations "jar-migrations"))))

(deftest test-migrate
  (is (not (verify-table-exists? "foo")))
  (is (not (verify-table-exists? "bar")))
  (is (not (verify-table-exists? "quux")))
  (is (not (verify-table-exists? "quux2")))
  (core/migrate config)
  (is (verify-table-exists? "foo"))
  (is (verify-table-exists? "bar"))
  (is (verify-table-exists? "quux"))
  (is (verify-table-exists? "quux2"))
  (core/down config 20111202110600)
  (is (not (verify-table-exists? "foo")))
  (is (verify-table-exists? "bar"))
  (is (verify-table-exists? "quux"))
  (is (verify-table-exists? "quux2"))
  (core/migrate config)
  (is (verify-table-exists? "foo"))
  (is (verify-table-exists? "bar"))
  (is (verify-table-exists? "quux"))
  (is (verify-table-exists? "quux2"))
  (core/down config 20111202110600 20120827170200)
  (is (not (verify-table-exists? "foo")))
  (is (verify-table-exists? "bar"))
  (is (not (verify-table-exists? "quux")))
  (is (not (verify-table-exists? "quux2")))
  (core/up config 20111202110600 20120827170200)
  (is (verify-table-exists? "foo"))
  (is (verify-table-exists? "bar"))
  (is (verify-table-exists? "quux"))
  (is (verify-table-exists? "quux2")))
