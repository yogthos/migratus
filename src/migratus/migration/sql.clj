(ns migratus.migration.sql
  (:require
    [clojure.java.jdbc :as sql]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [migratus.protocols :as proto])
  (:import
    java.util.regex.Pattern))

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

(defn execute-command [t-con tx? c]
  (log/trace "executing" c)
  (try
    (sql/db-do-prepared t-con tx? c)
    (catch Throwable t
      (log/error (format "failed to execute command:\n %s\nFailure: %s" c (.getMessage t)))
      (throw t))))

(defn- run-sql*
  [conn tx? commands direction]
  (log/debug "found" (count commands) (name direction) "migrations")
  (doseq [c commands]
    (execute-command conn tx? c)))

(defn run-sql
  [{:keys [conn db modify-sql-fn]} tx? sql direction]
  (when-let [commands (map (or modify-sql-fn identity) (split-commands sql))]
    (if tx?
      (sql/with-db-transaction
        [t-con (or conn db)]
        (run-sql* t-con true commands direction))
      (sql/with-db-connection
        [t-con (or conn db)]
        (run-sql* t-con false commands direction)))))

(defrecord SqlMigration [id name up down tx?]
  proto/Migration
  (id [this]
    id)
  (name [this]
    name)
  (up [this config]
    (if up
      (run-sql config tx? up :up)
      (throw (Exception. (format "Up commands not found for %d" id)))))
  (down [this config]
    (if down
      (run-sql config tx? down :down)
      (throw (Exception. (format "Down commands not found for %d" id))))))

(defmethod proto/make-migration* :sql
  [_ mig-id mig-name payload config]
  (let [up (when-let [sql (:up payload)]
             (if (string? sql) sql (:notx sql)))
        down (when-let [sql (:down payload)]
               (if (string? sql) sql (:notx sql)))
        tx? (and (nil? (get-in payload [:up :notx]))
                 (nil? (get-in payload [:down :notx])))]
    (->SqlMigration mig-id mig-name up down tx?)))


(defmethod proto/get-extension* :sql
  [_]
  "sql")

(defmethod proto/migration-files* :sql
  [x migration-name]
  (let [ext  (proto/get-extension* x)]
    [(str migration-name ".up." ext)
     (str migration-name ".down." ext)]))
