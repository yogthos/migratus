(ns migratus.migration.sql
  (:require
    [clojure.java.jdbc :as sql]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [migratus.protocols :as proto])
  (:import
    java.sql.SQLException
    java.util.regex.Pattern))

(def sep (Pattern/compile "^.*--;;.*\r?\n" Pattern/MULTILINE))
(def sql-comment (Pattern/compile "^--.*" Pattern/MULTILINE))
(def sql-comment-without-expect (Pattern/compile "^--((?! *expect).)*$" Pattern/MULTILINE))
(def empty-line (Pattern/compile "^[ ]+" Pattern/MULTILINE))

(defn use-tx? [sql]
  (not (str/starts-with? sql "-- :disable-transaction")))

(defn sanitize [command expect-results?]
  (-> command
      (clojure.string/replace (if expect-results? sql-comment-without-expect sql-comment) "")
      (clojure.string/replace empty-line "")))

(defn split-commands [commands expect-results?]
  (->> (.split sep commands)
       (map #(sanitize % expect-results?))
       (remove empty?)
       (not-empty)))

(defn check-expectations [result c]
  (let [[full-str expect-str command] (re-matches #"(?sm).*\s*-- expect (.*);;\n+(.*)" c)]
    (assert expect-str (str "No expectation on command: " c))
    (let [expected (some-> expect-str Long/parseLong)
          actual (some-> result first)
          different? (not= actual expected)
          message (format "%s %d"
                          (some-> command (clojure.string/split #"\s+" 2) first clojure.string/upper-case)
                          actual)]
      (if different?
        (log/error message "Expected" expected)
        (log/info message)))))

(defn execute-command [t-con tx? expect-results? c]
  (log/trace "executing" c)
  (cond->
    (try
      (sql/db-do-commands t-con tx? [c])
      (catch SQLException e
        (log/error (format "failed to execute command:\n %s" c))
        (loop [e e]
          (if-let [next-e (.getNextException e)]
            (recur next-e)
            (log/error (.getMessage e))))
        (throw e))
      (catch Throwable t
        (log/error (format "failed to execute command:\n %s\nFailure: %s" c (.getMessage t)))
        (throw t)))
    expect-results? (check-expectations c)))

(defn- run-sql*
  [conn tx? expect-results? commands direction]
  (log/debug "found" (count commands) (name direction) "migrations")
  (doseq [c commands]
    (execute-command conn tx? expect-results? c)))

(defn run-sql
  [{:keys [conn db modify-sql-fn expect-results?]} sql direction]
  (when-let [commands (map (or modify-sql-fn identity) (split-commands sql expect-results?))]
    (if (use-tx? sql)
      (sql/with-db-transaction
        [t-con (or conn db)]
        (run-sql* t-con true expect-results? commands direction))
      (sql/with-db-connection
        [t-con (or conn db)]
        (run-sql* t-con false expect-results? commands direction)))))

(defrecord SqlMigration [id name up down]
  proto/Migration
  (id [this]
    id)
  (name [this]
    name)
  (tx? [this direction]
    (if-let [sql (get this direction)]
      (use-tx? sql)
      (throw (Exception. (format "SQL %s commands not found for %d" direction id)))))
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
