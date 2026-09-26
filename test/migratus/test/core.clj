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

(deftest test-manually-marking-a-created-migration-repeatable
  (let [migration    "create-trigger"
        migration-up "create-trigger.up.sql"]
    (testing "create only ever makes a plain migration -- there's no repeatable flag"
      (create nil migration :sql)
      (is (migration-exists? migration-up))
      (is (migration-exists? "create-trigger.down.sql")
          "a plain create still makes both an up and a down file"))
    (testing "the author manually adds -- :repeatable to the up file to make it repeatable"
      (let [file (io/file (utils/find-migration-dir "migrations")
                          (first (migration-exists? migration-up)))]
        (spit file (str "-- :repeatable\n"
                       "CREATE OR REPLACE FUNCTION noop() RETURNS void AS $$ BEGIN END; $$ LANGUAGE plpgsql;\n"))
        (let [created (->> (mig/list-migrations {:migration-dir "migrations"})
                           (filter #(= migration (proto/name %)))
                           first)]
          (is (satisfies? proto/RepeatableMigration created))
          (is (= :sql (proto/migration-type created))))))
    (testing "should delete the migration"
      (destroy nil migration)
      (is (empty? (migration-exists? migration-up))))))

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

(deftest test-squashing-list
  (let [ups    (atom [])
        downs  (atom [])
        config {:store         :mock
                :completed-ids (atom #{1 3})}]
    (with-redefs [mig/list-migrations (constantly (migrations ups downs))]
      (testing "should throw an exception if the migration is not applied"
        (is (thrown? IllegalArgumentException
                     (migratus.core/squashing-list config 1 3))))
      (testing "should bring up an uncompleted migration"
        (up config 4 2)
        (is (= [2 4] @ups))
        (is (empty? @downs)))
      (testing "should return the list of squashing migrations in order"
        (is (= ["id-1" "id-2" "id-3" "id-4"]
               (migratus.core/squashing-list config 1 4))))
      (testing "should return the list of squashing migrations within the inclusive range"
        (is (= ["id-1" "id-2" "id-3"]
               (migratus.core/squashing-list config 1 3)))))))

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

(deftest test-migrate-in-transaction-rejects-disable-tx-migrations
  (let [ups   (atom [])
        store (mock/->MockStore (atom #{}) (atom {}) {})]
    (with-redefs [proto/make-store (constantly store)]
      (testing "a migration that opts out of transactions is fine without :migrate-in-transaction?"
        (with-redefs [mig/list-migrations
                      (constantly [(mock/make-migration
                                     {:id 1 :name "concurrent-index" :ups ups :downs (atom []) :tx? false})])]
          (migrate {}))
        (is (= [1] @ups)))

      (testing "the same batch is rejected up front when :migrate-in-transaction? is true"
        (reset! ups [])
        (with-redefs [mig/list-migrations
                      (constantly [(mock/make-migration
                                     {:id 2 :name "concurrent-index" :ups ups :downs (atom []) :tx? false})])]
          (is (thrown-with-msg?
                clojure.lang.ExceptionInfo
                #"Cannot run in a single transaction.*2-concurrent-index"
                (migrate {:migrate-in-transaction? true}))))
        (is (empty? @ups) "the migration must not have run at all"))

      (testing "a repeatable migration that opts out of transactions is also rejected"
        (reset! ups [])
        (with-redefs [mig/list-migrations
                      (constantly [(mock/make-repeatable-migration
                                     {:id 3 :name "concurrent-index" :checksum 1 :ups ups :tx? false})])]
          (is (thrown-with-msg?
                clojure.lang.ExceptionInfo
                #"Cannot run in a single transaction"
                (migrate {:migrate-in-transaction? true}))))
        (is (empty? @ups))))))

(deftest test-up-in-transaction-rejects-disable-tx-migrations
  (let [ups   (atom [])
        store (mock/->MockStore (atom #{}) (atom {}) {})]
    (with-redefs [proto/make-store (constantly store)]
      (testing "an explicit `up` with a disable-tx migration is fine without :migrate-in-transaction?"
        (with-redefs [mig/list-migrations
                      (constantly [(mock/make-migration
                                     {:id 1 :name "concurrent-index" :ups ups :downs (atom []) :tx? false})])]
          (up {} 1))
        (is (= [1] @ups)))

      (testing "the same call is rejected up front when :migrate-in-transaction? is true"
        (reset! ups [])
        (with-redefs [mig/list-migrations
                      (constantly [(mock/make-migration
                                     {:id 2 :name "concurrent-index" :ups ups :downs (atom []) :tx? false})])]
          (is (thrown-with-msg?
                clojure.lang.ExceptionInfo
                #"Cannot run in a single transaction.*2-concurrent-index"
                (up {:migrate-in-transaction? true} 2))))
        (is (empty? @ups) "the migration must not have run at all")))))

(deftest test-repeatable-migrations-skipped-when-regular-migration-fails
  (let [repeatable-ups (atom [])
        regular        (mock/make-migration {:id 1 :name "regular" :ups (atom []) :downs (atom [])})
        repeatable     (mock/make-repeatable-migration {:id 2 :name "trigger" :checksum 1 :ups repeatable-ups})]
    (doseq [regular-result [:failure :ignore]]
      (reset! repeatable-ups [])
      (let [store (reify proto/Store
                    (migrate-up [_this _migration] regular-result)
                    (migrate-repeatable-up [_this _name _checksum migration]
                      (proto/up migration nil)
                      :success))]
        (is (= regular-result (@#'migratus.core/run-migrate store [regular] [repeatable])))
        (is (empty? @repeatable-ups)
            (str "repeatable migrations must not run when the regular batch returns " regular-result))))))

(deftest test-migrate-repeatable
  (let [ups   (atom [])
        store (mock/->MockStore (atom #{}) (atom {}) {})]
    (with-redefs [proto/make-store (constantly store)]
      (testing "a new repeatable migration runs and its checksum is recorded"
        (with-redefs [mig/list-migrations
                      (constantly [(mock/make-repeatable-migration
                                     {:id 100 :name "trigger" :checksum 111 :ups ups})])]
          (migrate {}))
        (is (= [100] @ups))
        (is (= {"trigger" 111} (proto/repeatable-checksums store))))

      (testing "re-running migrate with an unchanged checksum is a no-op"
        (reset! ups [])
        (with-redefs [mig/list-migrations
                      (constantly [(mock/make-repeatable-migration
                                     {:id 100 :name "trigger" :checksum 111 :ups ups})])]
          (migrate {}))
        (is (empty? @ups)))

      (testing "a changed checksum re-runs the migration and updates the stored checksum"
        (reset! ups [])
        (with-redefs [mig/list-migrations
                      (constantly [(mock/make-repeatable-migration
                                     {:id 100 :name "trigger" :checksum 222 :ups ups})])]
          (migrate {}))
        (is (= [100] @ups))
        (is (= {"trigger" 222} (proto/repeatable-checksums store))))

      (testing "repeatable migrations run after regular pending migrations"
        (let [order (atom [])
              regular (mock/make-migration {:id 1 :name "regular" :ups order :downs (atom [])})
              repeatable (mock/make-repeatable-migration
                           {:id 100 :name "trigger" :checksum 333 :ups order})]
          (with-redefs [mig/list-migrations (constantly [regular repeatable])]
            (migrate {}))
          (is (= [1 100] @order))))

      (testing "multiple repeatable migrations run in id order, regardless of list-migrations order"
        (let [order (atom [])
              earlier (mock/make-repeatable-migration
                        {:id 200 :name "earlier" :checksum 1 :ups order})
              later   (mock/make-repeatable-migration
                        {:id 201 :name "later" :checksum 1 :ups order})]
          ;; deliberately returned out of id order
          (with-redefs [mig/list-migrations (constantly [later earlier])]
            (migrate {}))
          (is (= [200 201] @order)))))))

(deftest test-pending-list-includes-repeatable
  (let [ups    (atom [])
        downs  (atom [])
        store  (mock/->MockStore (atom #{1 3}) (atom {"trigger" 111}) {})
        config {:store :mock}]
    (with-redefs [proto/make-store (constantly store)
                  mig/list-migrations (constantly
                                         (conj (migrations ups downs)
                                               (mock/make-repeatable-migration
                                                 {:id 100 :name "trigger" :checksum 111 :ups ups})
                                               (mock/make-repeatable-migration
                                                 {:id 101 :name "changed-trigger" :checksum 999 :ups ups})))]
      (testing "unchanged repeatable migrations are not pending, changed ones are"
        (is (= ["id-2" "id-4" "changed-trigger"]
               (migratus.core/pending-list config)))))))

(deftest supported-extensions
  (testing "All supported extensions show up.
           NOTE: when you add a protocol, to migratus core, update this test")
  (is (= '("sql" "edn")
         (proto/get-all-supported-extensions))))
