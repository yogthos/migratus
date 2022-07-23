(ns migratus.test.migration.sql
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [migratus.core :as core]
            [migratus.database :as db]
            [migratus.migration.sql :refer :all]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def db-store (str (.getName (io/file ".")) "/site.db"))

(def db-spec {:dbtype "h2"
              :dbname  db-store})

(def test-config {:migration-dir "migrations/"
                  :db            db-spec})

(defn db-tables-and-views
  "Fetch tables and views (database metadata) from DB.
   Returns a collection of table metadata."
  [datasource]
  (with-open [con (jdbc/get-connection datasource)]
    (-> (.getMetaData con)
        (.getTables nil nil nil (into-array ["TABLE" "VIEW"]))
        (rs/datafiable-result-set datasource))))

(defn reset-db []
  (letfn [(delete [f]
            (when (.exists f)
              (.delete f)))]
    (delete (io/file "site.db.trace.db"))
    (delete (io/file "site.db.mv.db"))
    (delete (io/file "site.db"))))

(defn setup-test-db [f]
  (reset-db)
  (f))

(use-fixtures :each setup-test-db)

(defn verify-table-exists? [config table-name]
  (let [db (:db config)]
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


(comment
  (use 'clojure.tools.trace)

  (trace-ns clojure.test)
  (trace-ns migratus.test.migration.sql)
  (trace-ns migratus.test.database)
  (trace-ns migratus.database)
  (trace-ns migratus.migration.sql)
  (trace-ns migratus.protocols)
  (trace-ns migratus.core)
  (trace-ns migratus.mock)
  (trace-ns next.jdbc)
  (trace-ns next.jdbc.sql)
  (trace-ns next.jdbc.protocols)


  (run-test test-run-sql-migrations)

  0
  )
