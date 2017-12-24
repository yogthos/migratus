(ns migratus.test.migration.sql
  (:require [clojure.java.io :as io]
            [clojure.java.jdbc :as sql]
            [clojure.test :refer :all]
            [migratus.core :as core]
            [migratus.database :as db]
            [migratus.migration.sql :refer :all]
            migratus.mock
            [migratus.protocols :as proto]))

(def db-store (str (.getName (io/file ".")) "/site.db"))

(def test-config {:migration-dir        "migrations/"
                  :db                   {:classname   "org.h2.Driver"
                                         :subprotocol "h2"
                                         :subname     db-store}})

(defn reset-db []
  (letfn [(delete [f]
            (when (.exists f)
              (.delete f)))]
    (delete (io/file "resources"))
    (delete (io/file "site.db.trace.db"))
    (delete (io/file "site.db.mv.db"))))

(defn setup-test-db [f]
  (reset-db)
  (f))

(use-fixtures :each setup-test-db)

(defn verify-table-exists? [config table-name]
  (sql/with-db-connection [db (:db config)]
    (db/table-exists? db table-name)))

(deftest test-run-sql-migrations
  (let [config (merge test-config
                      {:store :mock
                       :completed-ids (atom #{})})]

    (is (not (verify-table-exists? config "foo")))
    (is (not (verify-table-exists? config "bar")))
    (is (not (verify-table-exists? config "quux")))
    (is (not (verify-table-exists? config "quux2")))

    (core/migrate config)

    (is (verify-table-exists? config "foo"))
    (is (verify-table-exists? config "bar"))
    (is (verify-table-exists? config "quux"))
    (is (verify-table-exists? config "quux2"))

    (core/rollback config)

    (is (verify-table-exists? config "foo"))
    (is (verify-table-exists? config "bar"))
    (is (not (verify-table-exists? config "quux")))
    (is (not (verify-table-exists? config "quux2")))))
