(ns migratus.migration.sql
  (:require [clojure.java.jdbc :as sql]
            [clojure.tools.logging :as log]
            [migratus.protocols :as proto])
  (:import java.util.regex.Pattern))

(def sep (Pattern/compile "^.*--;;.*\r?\n" Pattern/MULTILINE))
(def sql-comment (Pattern/compile "^--.*" Pattern/MULTILINE))
(def empty-line (Pattern/compile "^[ ]+" Pattern/MULTILINE))

(defn sanitize [command]
  (-> command
      (clojure.string/replace sql-comment "")
      (clojure.string/replace empty-line "")))

(defn split-commands [commands]
  (->> (.split sep commands)
       (map sanitize)
       (remove empty?)
       (not-empty)))

(defn execute-command [t-con c]
  (log/trace "executing" c)
  (try
    (sql/db-do-prepared t-con c)
    (catch Throwable t
      (log/error  (format "failed to execute command:\n %s\nFailure: %s" c (.getMessage t)))
      (throw t))))

(defn run-sql [{:keys [conn db modify-sql-fn]} sql direction]
  (sql/with-db-transaction
    [t-con (or conn db)]
    (when-let [commands (map (or modify-sql-fn identity) (split-commands sql))]
      (log/debug "found" (count commands) (name direction) "migrations")
      (doseq [c commands]
        (execute-command t-con c)))))

(defrecord SqlMigration [id name up down]
  proto/Migration
  (id [this]
    id)
  (name [this]
    name)
  (up [this config]
    (if up
      (run-sql config up :up)
      (throw (Exception. (format "Up commands not found for %d" id)))))
  (down [this config]
    (if down
      (run-sql config down :down)
      (throw (Exception. (format "Down commands not found for %d" id))))))

(defmethod proto/make-migration* :sql
  [_ mig-id mig-name payload config]
  (->SqlMigration mig-id mig-name (:up payload) (:down payload)))


(defmethod proto/get-extension* :sql
  [_]
  "sql")

(defmethod proto/migration-files* :sql
  [x migration-name]
  (let [ext  (proto/get-extension* x)]
    [(str migration-name ".up." ext)
     (str migration-name ".down." ext)]))
