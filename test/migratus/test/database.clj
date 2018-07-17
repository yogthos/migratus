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
            [migratus.core :as core]
            [clojure.test :refer :all]
            [migratus.database :refer :all]
            [clojure.tools.logging :as log]
            [migratus.test.migration.edn :as test-edn]
            [migratus.test.migration.sql :as test-sql]
            [migratus.utils :as utils])
  (:import java.io.File
           java.util.jar.JarFile
           (java.util.concurrent CancellationException)))

(def config (merge test-sql/test-config
                   {:store                :database
                    :migration-table-name "foo_bar"}))

(defn verify-data [config table-name]
  (let [db     (connect* (:db config))
        result (sql/query db [(str "SELECT * from " table-name)])]
    (.close (:connection db))
    result))

(defn test-with-store [store & commands]
  (try
    (proto/connect store)
    (doseq [cmd commands]
      (cmd (proto/config store)))
    (finally
      (proto/disconnect store))))

(use-fixtures :each test-sql/setup-test-db)

(deftest test-find-init-script-resource
  (testing "finds init.sql under migrations/ in a JAR file"
    (let [jar-file    (JarFile. "test/migrations-jar/init-test.jar")
          init-script (find-init-script-resource "migrations/" jar-file "init.sql")]
      (is (not (nil? init-script)))
      (is (= "CREATE SCHEMA foo;\n" (slurp init-script))))))

(deftest test-make-store
  (testing "should create default table name"
    (is (not (test-sql/verify-table-exists?
               (dissoc config :migration-table-name) default-migrations-table)))
    (test-with-store
      (proto/make-store (dissoc config :migration-table-name))
      (fn [config]
        (is (test-sql/verify-table-exists? config default-migrations-table)))))
  (test-sql/reset-db)
  (testing "should create schema_migrations table"
    (is (not (test-sql/verify-table-exists? config "foo_bar")))
    (test-with-store
      (proto/make-store config)
      (fn [config]
        (is (test-sql/verify-table-exists? config "foo_bar"))))))

(deftest test-init
  (testing "db init"
    (let [config (assoc config :init-script "init.sql")]
      (test-sql/reset-db)
      (let [store (proto/make-store config)]
        (proto/init store)
        (is (test-sql/verify-table-exists? config "foo")))
      (test-sql/reset-db)
      (let [store (proto/make-store (assoc config :init-in-transaction? false))]
        (proto/init store)
        (is (test-sql/verify-table-exists? config "foo"))))))

(deftest test-migrate
  (is (not (test-sql/verify-table-exists? config "foo")))
  (is (not (test-sql/verify-table-exists? config "bar")))
  (is (not (test-sql/verify-table-exists? config "quux")))
  (is (not (test-sql/verify-table-exists? config "quux2")))
  (core/migrate config)
  (is (test-sql/verify-table-exists? config "foo"))
  (is (test-sql/verify-table-exists? config "bar"))
  (is (test-sql/verify-table-exists? config "quux"))
  (is (test-sql/verify-table-exists? config "quux2"))
  (core/down config 20111202110600)
  (is (not (test-sql/verify-table-exists? config "foo")))
  (is (test-sql/verify-table-exists? config "bar"))
  (is (test-sql/verify-table-exists? config "quux"))
  (is (test-sql/verify-table-exists? config "quux2"))
  (core/migrate config)
  (is (test-sql/verify-table-exists? config "foo"))
  (is (test-sql/verify-table-exists? config "bar"))
  (is (test-sql/verify-table-exists? config "quux"))
  (is (test-sql/verify-table-exists? config "quux2"))
  (core/down config 20111202110600 20120827170200)
  (is (not (test-sql/verify-table-exists? config "foo")))
  (is (test-sql/verify-table-exists? config "bar"))
  (is (not (test-sql/verify-table-exists? config "quux")))
  (is (not (test-sql/verify-table-exists? config "quux2")))
  (core/up config 20111202110600 20120827170200)
  (is (test-sql/verify-table-exists? config "foo"))
  (is (test-sql/verify-table-exists? config "bar"))
  (is (test-sql/verify-table-exists? config "quux"))
  (is (test-sql/verify-table-exists? config "quux2")))

(defn comment-out-bar-statements [sql]
  (if (re-find #"CREATE TABLE IF NOT EXISTS bar" sql)
    (str "-- " sql)
    sql))

(deftest test-migrate-with-modify-sql-fn
  (is (not (test-sql/verify-table-exists? config "foo")))
  (is (not (test-sql/verify-table-exists? config "bar")))
  (is (not (test-sql/verify-table-exists? config "quux")))
  (is (not (test-sql/verify-table-exists? config "quux2")))
  (core/migrate (assoc config :modify-sql-fn comment-out-bar-statements))
  (is (test-sql/verify-table-exists? config "foo"))
  (is (not (test-sql/verify-table-exists? config "bar")))
  (is (test-sql/verify-table-exists? config "quux"))
  (is (test-sql/verify-table-exists? config "quux2")))

(deftest test-migration-table-creation-is-hooked
  (let [hook-called (atom false)]
    (core/migrate
      (assoc config
        :migration-table-name "schema_migrations"
        :modify-sql-fn (fn [sql]
                         (when (re-find #"CREATE TABLE schema_migrations" sql)
                           (reset! hook-called true))
                         sql)))
    (is @hook-called)))

(deftest test-migrate-until-just-before
  (is (not (test-sql/verify-table-exists? config "foo")))
  (is (not (test-sql/verify-table-exists? config "bar")))
  (is (not (test-sql/verify-table-exists? config "quux")))
  (is (not (test-sql/verify-table-exists? config "quux2")))
  (core/migrate-until-just-before config 20120827170200)
  (is (test-sql/verify-table-exists? config "foo"))
  (is (test-sql/verify-table-exists? config "bar"))
  (is (not (test-sql/verify-table-exists? config "quux")))
  (is (not (test-sql/verify-table-exists? config "quux2")))
  (core/migrate config)
  (is (test-sql/verify-table-exists? config "foo"))
  (is (test-sql/verify-table-exists? config "bar"))
  (is (test-sql/verify-table-exists? config "quux"))
  (is (test-sql/verify-table-exists? config "quux2")))

(deftest test-migration-ignored-when-already-reserved
  (test-with-store
    (proto/make-store config)
    (fn [{:keys [db migration-table-name] :as config}]
      (testing "can only reserve once"
        (is (mark-reserved db migration-table-name))
        (is (not (mark-reserved db migration-table-name))))
      (testing "migrations don't run when locked"
        (is (not (test-sql/verify-table-exists? config "foo")))
        (is (= :ignore (core/migrate config)))
        (is (not (test-sql/verify-table-exists? config "foo"))))
      (testing "migrations run once lock is freed"
        (mark-unreserved db migration-table-name)
        (is (nil? (core/migrate config)))
        (is (test-sql/verify-table-exists? config "foo")))
      (testing "rollback migration isn't run when locked"
        (is (mark-reserved db migration-table-name))
        (core/down config 20111202110600)
        (is (test-sql/verify-table-exists? config "foo")))
      (testing "rollback migration run once lock is freed"
        (mark-unreserved db migration-table-name)
        (core/down config 20111202110600)
        (is (not (test-sql/verify-table-exists? config "foo")))))))

(defn copy-dir
  [^File from ^File to]
  (when-not (.exists to)
    (.mkdirs to))
  (doseq [f (.listFiles from)
          :when (.isFile f)]
    (io/copy f (io/file to (.getName f)))))

(deftest test-migration-sql-edn-mixed
  (let [migration-dir (io/file "test/migrations-mixed")
        test-config   (merge config
                             test-edn/test-config
                             {:parent-migration-dir "test"
                              :migration-dir "migrations-mixed"})]
    (try
      (utils/recursive-delete (io/file test-edn/test-dir))
      (utils/recursive-delete migration-dir)
      (copy-dir (io/file "test/migrations") migration-dir)
      (copy-dir (io/file "test/migrations-edn") migration-dir)

      (is (not (test-sql/verify-table-exists? test-config "foo")))
      (is (not (test-sql/verify-table-exists? test-config "bar")))
      (is (not (test-sql/verify-table-exists? test-config "quux")))
      (is (not (test-sql/verify-table-exists? test-config "quux2")))
      (is (not (test-edn/test-file-exists?)))

      (core/migrate test-config)

      (is (test-sql/verify-table-exists? test-config "foo"))
      (is (test-sql/verify-table-exists? test-config "bar"))
      (is (test-sql/verify-table-exists? test-config "quux"))
      (is (test-sql/verify-table-exists? test-config "quux2"))
      (is (test-edn/test-file-exists?))

      (finally
        (utils/recursive-delete migration-dir)))))



(deftest test-description-and-applied-fields
  (core/migrate config)
  (let [from-db (verify-data config (:migration-table-name config))]
    (testing "descriptions match")
    (is (= (map #(dissoc % :applied) from-db)
           '({:id          20111202110600,
              :description "create-foo-table"}
              {:id          20111202113000,
               :description "create-bar-table"}
              {:id          20120827170200,
               :description "multiple-statements"})))
    (testing "applied are timestamps")
    (is (every? identity (map #(-> %
                                   :applied
                                   type
                                   (= java.sql.Timestamp))
                              from-db)))))

(defn- test-backing-out* [test-config]
  (let [{:keys [db migration-table-name]} test-config]
    (testing "should fail")
    (is (thrown? Throwable (core/migrate test-config)))
    (testing "first statement in migration was backed out because second one failed")
    (is (not (test-sql/verify-table-exists? test-config "quux2")))
    (testing "third statement in migration was backed out because second one failed")
    (is (not (test-sql/verify-table-exists? test-config "quux3")))
    (testing "migration was not applied")
    (is (not (complete? db migration-table-name 20120827170200)))
    #_#_(testing "table should be unreserved after migration failure")
    (is (false? (mark-reserved db migration-table-name)))))

(deftest test-backing-out-bad-migration
  (log/debug "running backout tests")
  (test-backing-out* (assoc config :migration-dir "migrations-intentionally-broken")))

(deftest test-backing-out-bad-migration-no-tx
  (log/debug "running backout tests without tx")
  (test-backing-out* (assoc config :migration-dir "migrations-intentionally-broken-no-tx")))


(deftest test-no-tx-migration
  (let [{:keys [db migration-table-name] :as test-config} (assoc config :migration-dir "migrations-no-tx")]
    (is (not (test-sql/verify-table-exists? test-config "foo")))
    (core/migrate test-config)
    (is (test-sql/verify-table-exists? test-config "foo"))
    (core/down test-config 20111202110600)
    (is (not (test-sql/verify-table-exists? test-config "foo")))))

(deftest test-cancellation-observed
  (let [lines-processed (atom 0)
        future-instance (atom nil)
        future-instance-set (promise)
        migration-in-future (future (core/migrate
                                     (assoc config
                                       :migration-table-name "schema_migrations"
                                       :modify-sql-fn (fn [sql]
                                                        (when (re-find #"CREATE TABLE schema_migrations" sql)
                                                          (deref future-instance-set)
                                                          (future-cancel @future-instance))
                                                        (swap! lines-processed inc)
                                                        sql))))]
    (reset! future-instance migration-in-future)
    (deliver future-instance-set true)
    (is (thrown? CancellationException @migration-in-future))
    (Thread/sleep 100)
    (is (= 1 @lines-processed))))
