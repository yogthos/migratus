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
(ns migratus.database
  (:require [clojure.java.io :as io]
            [clojure.java.jdbc :as sql]
            [clojure.tools.logging :as log]
            [migratus.migration.sql :as sql-mig]
            [migratus.protocols :as proto]
            [migratus.utils :as utils])
  (:import java.io.File
           [java.sql Connection SQLException]
           java.util.jar.JarEntry))

(def default-migrations-table "schema_migrations")

(defn migration-table-name [config]
  (:migration-table-name config default-migrations-table))

(def reserved-id -1)

(defn mark-reserved [db table-name]
  (boolean
   (try
     (sql/insert! db table-name {:id reserved-id})
     (catch Exception _))))

(defn mark-unreserved [db table-name]
  (sql/delete! db table-name ["id=?" reserved-id]))

(defn complete? [db table-name id]
  (first (sql/query db [(str "SELECT * from " table-name " WHERE id=?") id])))

(defn mark-complete [db table-name description id]
  (log/debug "marking" id "complete")
  (sql/insert! db table-name {:id          id
                              :applied     (java.sql.Timestamp. (.getTime (java.util.Date.)))
                              :description description}))

(defn mark-not-complete [db table-name id]
  (log/debug "marking" id "not complete")
  (sql/delete! db table-name ["id=?" id]))

(defn migrate-up* [db {:keys [tx-handles-ddl?] :as config} {:keys [name] :as migration}]
  (let [id         (proto/id migration)
        table-name (migration-table-name config)]
    (if (mark-reserved db table-name)
      (try
        (when-not (complete? db table-name id)
          (proto/up migration (assoc config :conn db))
          (mark-complete db table-name name id)
          :success)
        (catch Throwable up-e
          (if tx-handles-ddl?
            (log/error (format "Migration %s failed because %s" name (.getMessage up-e)))
            (do
              (log/error (format "Migration %s failed because %s backing out" name (.getMessage up-e)))
              (try
                (proto/down migration (assoc config :conn db))
                (catch Throwable down-e
                  (log/debug down-e (format "As expected, one of the statements failed in %s while backing out the migration" name))))))
          (throw up-e))
        (finally
          (mark-unreserved db table-name)))
      :ignore)))

(defn migrate-down* [db config migration]
  (let [id         (proto/id migration)
        table-name (migration-table-name config)]
    (if (mark-reserved db table-name)
      (try
        (when (complete? db table-name id)
          (proto/down migration (assoc config :conn db))
          (mark-not-complete db table-name id)
          :success)
        (finally
          (mark-unreserved db table-name)))
      :ignore)))

(defn find-init-script-file [migration-dir init-script-name]
  (first
   (filter (fn [^File f] (and (.isFile f) (= (.getName f) init-script-name)))
           (file-seq migration-dir))))

(defn find-init-script-resource [migration-dir jar init-script-name]
  (let [init-script-path (.getPath (io/file migration-dir init-script-name))]
    (->> (.entries jar)
         (enumeration-seq)
         (filter (fn [^JarEntry entry]
                   (.endsWith (.getName entry) init-script-path)))
         (first)
         (.getInputStream jar))))

(defn find-init-script [dir init-script-name]
  (let [dir (utils/ensure-trailing-slash dir)]
    (when-let [migration-dir (utils/find-migration-dir dir)]
      (if (instance? File migration-dir)
        (find-init-script-file migration-dir init-script-name)
        (find-init-script-resource dir migration-dir init-script-name)))))

(defn connect* [db]
  (let [^Connection conn
        (try
          (sql/get-connection db)
          (catch Exception e
            (log/error e (str "Error creating DB connection for " db))))]
    (.setAutoCommit conn false)
    {:connection conn}))

(defn disconnect* [db]
  (when-let [conn (:connection db)]
    (when-not (.isClosed conn)
      (.close conn))))

(defn completed-ids* [db table-name]
  (sql/with-db-transaction
    [t-con db]
    (->> (sql/query t-con (str "select id from " table-name " where id != " reserved-id))
         (map :id)
         (doall))))

(defn table-exists?
  "Checks whether the migrations table exists, by attempting to select from
  it. Note that this appears to be the only truly portable way to determine
  whether the table exists in a schema which the `db` configuration will find
  via a `SELECT FROM` or `INSERT INTO` the table. (In particular, note that
  attempting to find the table in the database meta-data as exposed by the JDBC
  driver does *not* tell you whether the table is on the current schema search
  path.)"
  [db table-name]
  (sql/with-db-transaction
    [t-con db]
    (try
      (sql/query t-con [(str "SELECT 1 FROM " table-name)])
      true
      (catch SQLException _
        false))))



(defn migration-table-up-to-date?
  [db table-name]
  (sql/with-db-transaction
    [t-con db]
    (try
      (sql/query t-con [(str "SELECT applied,description FROM " table-name)])
      true
      (catch SQLException _
        false))))


(defn create-migration-table!
  "Creates the schema for the migration table via t-con in db in table-name"
  [db modify-sql-fn table-name]
  (log/info "creating migration table" (str "'" table-name "'"))
  (sql/with-db-transaction
    [t-con db]
    (sql/db-do-commands t-con
                        (modify-sql-fn
                         (sql/create-table-ddl table-name [[:id "BIGINT" "UNIQUE" "NOT NULL"]
                                                           [:applied "TIMESTAMP" "" ""]
                                                           [:description "VARCHAR(1024)" "" ""]])))))

(defn update-migration-table!
  "Updates the schema for the migration table via t-con in db in table-name"
  [db modify-sql-fn table-name]
  (log/info "updating migration table" (str "'" table-name "'"))
  (sql/with-db-transaction
    [t-con db]
    (sql/db-do-commands t-con
                        (modify-sql-fn
                         [(str "ALTER TABLE " table-name " ADD COLUMN description varchar(1024)")
                          (str "ALTER TABLE " table-name " ADD COLUMN applied timestamp")]))))


(defn init-schema! [db table-name modify-sql-fn]
  ;; Note: the table-exists? *has* to be done in its own top-level
  ;; transaction. It can't be run in the same transaction as other code, because
  ;; if the table doesn't exist, then the error it raises internally in
  ;; detecting that will (on Postgres, at least) mark the transaction as
  ;; rollback only. That is, the act of detecting that it is necessary to create
  ;; the table renders the current transaction unusable for that purpose. I
  ;; blame Heisenberg.
  (or (table-exists? db table-name)
      (create-migration-table! db modify-sql-fn table-name))
  (or (migration-table-up-to-date? db table-name)
      (update-migration-table! db modify-sql-fn table-name)))


(defn run-init-script! [init-script-name init-script conn modify-sql-fn]
  (try
    (log/info "running initialization script '" init-script-name "'")
    (log/trace "\n" init-script "\n")
    (sql/db-do-prepared conn (modify-sql-fn init-script))
    (catch Throwable t
      (log/error t "failed to initialize the database with:\n" init-script "\n")
      (throw t))))

(defn init-db! [db migration-dir init-script-name modify-sql-fn transaction?]
  (if-let [init-script (some-> (find-init-script migration-dir init-script-name) slurp)]
    (if transaction?
      (sql/with-db-transaction
        [t-con db]
        (run-init-script! init-script-name init-script t-con modify-sql-fn))
      (run-init-script! init-script-name init-script db modify-sql-fn))
    (log/error "could not locate the initialization script '" init-script-name "'")))

(defrecord Database [connection config]
  proto/Store
  (config [this] config)
  (init [this]
    (let [conn (connect* (:db config))]
      (try
        (init-db! conn
                  (utils/get-migration-dir config)
                  (utils/get-init-script config)
                  (get config :modify-sql-fn identity)
                  (get config :init-in-transaction? true))
        (finally
          (disconnect* conn)))))
  (completed-ids [this]
    (completed-ids* @connection (migration-table-name config)))
  (migrate-up [this migration]
    (if (proto/tx? migration :up)
      (sql/with-db-transaction [t-con @connection]
        (migrate-up* t-con config migration))
      (migrate-up* (:db config) config migration)))
  (migrate-down [this migration]
    (if (proto/tx? migration :down)
      (sql/with-db-transaction [t-con @connection]
        (migrate-down* t-con config migration))
      (migrate-down* (:db config) config migration)))
  (connect [this]
    (reset! connection (connect* (:db config)))
    (init-schema! @connection
                  (migration-table-name config)
                  (get config :modify-sql-fn identity)))
  (disconnect [this]
    (disconnect* @connection)
    (reset! connection nil)))

(defmethod proto/make-store :database
  [config]
  (->Database (atom nil) config))


