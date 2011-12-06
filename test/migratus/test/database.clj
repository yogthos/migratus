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
            [clojure.java.jdbc :as sql]
            [migratus.protocols :as proto]
            [migratus.core :as core])
  (:use [clojure.test]
        [migratus.database]))

(def config {:store :database
             :migration-dir "test/migrations/"
             :db {:classname "com.mysql.jdbc.Driver"
                  :subprotocol "mysql"
                  :subname "//localhost/migratus"
                  :user "root"
                  :password ""}})

(defn setup-test-db [f]
  (sql/with-connection (assoc (:db config) :subname "//localhost/mysql")
    (sql/do-commands "DROP DATABASE IF EXISTS migratus;")
    (sql/do-commands "CREATE DATABASE migratus;"))
  (f))

(use-fixtures :each setup-test-db)

(defn verify-table-exists? [table-name]
  (sql/with-connection (:db config)
    (table-exists? table-name)))

(deftest test-make-store
  (testing "should create schema_migrations table"
    (is (not (verify-table-exists? "schema_migrations")))
    (proto/make-store config)
    (is (verify-table-exists? "schema_migrations"))))

(deftest test-parse-name
  (is (= ["20111202110600" "create-foo-table" "up"]
         (parse-name "20111202110600-create-foo-table.up.sql")))
  (is (= ["20111202110600" "create-foo-table" "down"]
         (parse-name "20111202110600-create-foo-table.down.sql"))))

(deftest test-find-migrations
  (is (= [{:id 20111202110600 :name "create-foo-table"}
          {:id 20111202113000 :name "create-bar-table"}]
         (sort-by :id (find-migrations (io/file "test/migrations"))))))

(deftest test-migrate
  (is (not (verify-table-exists? "foo")))
  (is (not (verify-table-exists? "bar")))
  (core/migrate config)
  (is (verify-table-exists? "foo"))
  (is (verify-table-exists? "bar"))
  (core/down config 20111202110600)
  (is (not (verify-table-exists? "foo")))
  (is (verify-table-exists? "bar"))
  (core/migrate config)
  (is (verify-table-exists? "foo"))
  (is (verify-table-exists? "bar"))
  (core/down config 20111202110600 20111202113000)
  (is (not (verify-table-exists? "foo")))
  (is (not (verify-table-exists? "bar")))
  (core/up config 20111202110600 20111202113000)
  (is (verify-table-exists? "foo"))
  (is (verify-table-exists? "bar")))
