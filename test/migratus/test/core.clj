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
(ns migratus.test.core
  (:require [migratus.protocols :as proto]
            [migratus.mock :as mock]
            [clojure.test :refer :all]
            [migratus.core :refer :all]
            migratus.logger
            [migratus.migrations :as mig]
            [migratus.utils :as utils]
            [clojure.java.io :as io])
  (:import [migratus.mock MockStore MockMigration]))

(defn migrations [ups downs]
  (for [n (range 4)]
    (mock/make-migration
      {:id (inc n) :name (str "id-" (inc n)) :ups ups :downs downs})))

(deftest test-migrate
  (let [ups    (atom [])
        downs  (atom [])
        config {:store         :mock
                :completed-ids (atom #{1 3})}]
    (with-redefs [mig/list-migrations (constantly (migrations ups downs))]
      (migrate config))
    (is (= [2 4] @ups))
    (is (empty? @downs))))

(deftest test-up
  (let [ups    (atom [])
        downs  (atom [])
        config {:store         :mock
                :completed-ids (atom #{1 3})}]
    (with-redefs [mig/list-migrations (constantly (migrations ups downs))]
      (testing "should bring up an uncompleted migration"
        (up config 4 2)
        (is (= [2 4] @ups))
        (is (empty? @downs)))
      (reset! ups [])
      (reset! downs [])
      (testing "should do nothing for a completed migration"
        (up config 1)
        (is (empty? @ups))
        (is (empty? @downs))))))

(deftest test-down
  (let [ups    (atom [])
        downs  (atom [])
        config {:store         :mock
                :completed-ids (atom #{1 3})}]
    (with-redefs [mig/list-migrations (constantly (migrations ups downs))]
      (testing "should bring down a completed migration"
        (down config 1 3)
        (is (empty? @ups))
        (is (= [3 1] @downs)))
      (reset! ups [])
      (reset! downs [])
      (testing "should do nothing for an uncompleted migration"
        (down config 2)
        (is (empty? @ups))
        (is (empty? @downs))))))

(defn- migration-exists? [name & [dir]]
  (when-let [migrations-dir (utils/find-migration-dir (or dir "migrations"))]
    (->> (file-seq migrations-dir)
         (map #(.getName %))
         (filter #(.contains % name))
         (not-empty))))

(deftest test-create-and-destroy
  (let [migration      "create-user"
        migration-up   "create-user.up.sql"
        migration-down "create-user.down.sql"]
    (testing "should create two migrations"
      (create nil migration)
      (is (migration-exists? migration-up))
      (is (migration-exists? migration-down)))
    (testing "should delete two migrations"
      (destroy nil migration)
      (is (empty? (migration-exists? migration-up)))
      (is (empty? (migration-exists? migration-down))))))

(deftest test-create-and-destroy-edn
  (let [migration     "create-other-user"
        migration-edn "create-other-user.edn"]
    (testing "should create the migration"
      (create nil migration :edn)
      (is (migration-exists? migration-edn)))
    (testing "should delete the migration"
      (destroy nil migration)
      (is (empty? (migration-exists? migration-edn))))))

(deftest test-create-missing-directory
  (let [migration-dir  "doesnt_exist"
        config         {:parent-migration-dir "test"
                        :migration-dir        migration-dir}
        migration      "create-user"
        migration-up   "create-user.up.sql"
        migration-down "create-user.down.sql"]
    ;; Make sure the directory doesn't exist before we start the test
    (when (.exists (io/file "test" migration-dir))
      (io/delete-file (io/file "test" migration-dir)))

    (testing "when migration dir doesn't exist, it is created"
      (is (nil? (utils/find-migration-dir migration-dir)))
      (create config migration)
      (is (not (nil? (utils/find-migration-dir migration-dir))))
      (is (migration-exists? migration-up migration-dir))
      (is (migration-exists? migration-down migration-dir)))

    ;; Clean up after ourselves
    (when (.exists (io/file "test" migration-dir))
      (destroy config migration)
      (io/delete-file (io/file "test" migration-dir)))))

(deftest test-completed-list
  (let [ups    (atom [])
        downs  (atom [])
        config {:store         :mock
                :completed-ids (atom #{1 2 3})}]
    (with-redefs [mig/list-migrations (constantly (migrations ups downs))]
      (testing "should return the list of completed migrations"
        (is (= ["id-1" "id-2" "id-3"]
               (migratus.core/completed-list config)))))))

(deftest test-pending-list
  (let [ups    (atom [])
        downs  (atom [])
        config {:store         :mock
                :completed-ids (atom #{1})}]
    (with-redefs [mig/list-migrations (constantly (migrations ups downs))]
      (testing "should return the list of pending migrations"
        (is (= ["id-2" "id-3" "id-4"]
               (migratus.core/pending-list config)))))))

(deftest test-select-migrations
  (let [ups    (atom [])
        downs  (atom [])
        config {:store         :mock
                :completed-ids (atom #{1 3})}]
    (with-redefs [mig/list-migrations (constantly (migrations ups downs))]
      (testing "should return the list of [id name] selected migrations"
        (is (= [[1 "id-1"] [3 "id-3"]]
               (migratus.core/select-migrations config migratus.core/completed-migrations)))
        (is (= [[2 "id-2"] [4 "id-4"]]
               (migratus.core/select-migrations config migratus.core/uncompleted-migrations)))))))

(deftest supported-extensions
  (testing "All supported extensions show up.
           NOTE: when you add a protocol, to migratus core, update this test")
  (is (= '("sql" "edn")
         (proto/get-all-supported-extensions))))
