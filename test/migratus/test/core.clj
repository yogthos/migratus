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
            migratus.mock
            [clojure.test :refer :all]
            [migratus.core :refer :all]
            migratus.logger
            [clojure.java.io :as io])
  (:import [migratus.mock MockStore MockMigration]))

(defn migrations [ups downs]
  (for [n (range 4)]
    {:id (inc n) :name (str "id-" (inc n)) :ups ups :downs downs}))

(deftest test-migrate
  (let [ups (atom [])
        downs (atom [])
        migrations (migrations ups downs)
        config {:store :mock
                :completed-ids [1 3]
                :migrations (reverse migrations)}]
    (migrate config)
    (is (= [2 4] @ups))
    (is (empty? @downs))))

(deftest test-up
  (let [ups (atom [])
        downs (atom [])
        migrations (migrations ups downs)
        config {:store :mock
                :completed-ids [1 3]
                :migrations migrations}]
    (testing "should bring up an uncompleted migration"
      (up config 4 2)
      (is (= [2 4] @ups))
      (is (empty? @downs)))
    (reset! ups [])
    (reset! downs [])
    (testing "should do nothing for a completed migration"
      (up config 1)
      (is (empty? @ups))
      (is (empty? @downs)))))

(deftest test-down
  (let [ups (atom [])
        downs (atom [])
        migrations (migrations ups downs)
        config {:store :mock
                :completed-ids [1 3]
                :migrations migrations}]
    (testing "should bring down a completed migration"
      (down config 1 3)
      (is (empty? @ups))
      (is (= [3 1] @downs)))
    (reset! ups [])
    (reset! downs [])
    (testing "should do nothing for an uncompleted migration"
      (down config 2)
      (is (empty? @ups))
      (is (empty? @downs)))))

(defn- migration-exists? [name]
    (let [migrations (file-seq (migratus.database/find-migration-dir "migrations"))
          names (map #(.getName %) migrations)]
        (filter #(.contains % name) names)))

(deftest test-create-and-destroy
    (let [config {:store :database
                  :migrations migrations}
          migration "create-user"
          migration-up  "create-user.up.sql"
          migration-down "create-user.down.sql"]
        (testing "should create two migrations"
            (create config migration)
            (is (migration-exists? migration-up))
            (is (migration-exists? migration-down)))
        (testing "should delete two migrations"
            (destroy config migration)
            (is (empty? (migration-exists? migration-up)))
            (is (empty? (migration-exists? migration-down))))))

(deftest test-create-missing-directory
  (let [migration-dir "doesnt_exist"
        config {:store :database
                :migrations migrations
                :migration-dir migration-dir}
        migration "create-user"
        migration-up  "create-user.up.sql"
        migration-down  "create-user.down.sql"]
    ;; Make sure the directory doesn't exist before we start the test
    (when (.exists (io/file "resources" migration-dir))
      (io/delete-file (io/file "resources" migration-dir)))

    (testing "when migration dir doesn't exist, it is created"
      (is (nil? (migratus.database/find-migration-dir migration-dir)))
      (create config migration)
      (is (not (nil? (migratus.database/find-migration-dir migration-dir))))
      (is (migration-exists? migration-up))
      (is (migration-exists? migration-down)))

    ;; Clean up after ourselves
    (when (.exists (io/file "resources" migration-dir))
      (destroy config migration)
      (io/delete-file (io/file "resources" migration-dir)))))
