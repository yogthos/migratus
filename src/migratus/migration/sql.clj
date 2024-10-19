(ns migratus.migration.sql
  (:require [next.jdbc :as jdbc]
            [next.jdbc.prepare :as prepare]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [migratus.protocols :as proto]
            [clojure.java.io :as io])
  (:import
    (java.sql Connection
              SQLException)
    java.util.regex.Pattern))

(def ^Pattern sep (Pattern/compile "^.*--;;.*\r?\n" Pattern/MULTILINE))
(def ^Pattern sql-comment (Pattern/compile "--.*" Pattern/MULTILINE))
(def ^Pattern sql-comment-without-expect (Pattern/compile "--((?! *expect).)*$" Pattern/MULTILINE))
(def ^Pattern empty-line (Pattern/compile "^[ ]+" Pattern/MULTILINE))

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
    (let [expected   (some-> expect-str Long/parseLong)
          actual     (some-> result first)
          different? (not= actual expected)
          message    (format "%s %d"
                             (some-> command (clojure.string/split #"\s+" 2) first clojure.string/upper-case)
                             actual)]
      (if different?
        (log/error message "Expected" expected)
        (log/info message)))))

(defn wrap-modify-sql-fn [old-modify-fn]
  (fn [sql]
    (let [modify-fn (or old-modify-fn identity)
          result (modify-fn sql)]
      (if (string? result)
        [result]
        result))))

(defn parse-commands-sql [{:keys [command-separator]} commands]
  (if command-separator
    (->>
      (str/split commands (re-pattern command-separator))
      (map str/trim)
      (remove empty?))
    commands))

(defn do-commands
  "Adapt db-do-commands to jdbc
   https://cljdoc.org/d/com.github.seancorfield/next.jdbc/1.2.780/doc/migration-from-clojure-java-jdbc"
  [connectable commands]
  (cond
    (instance? Connection connectable)
    (with-open [stmt (prepare/statement connectable)]
      ;; We test for (string? commands) because migratus.test.migrations.sql tests fails otherwise.
      ;; Perhaps it is a bug in migratus.test.mock implementation ?!
      (if (string? commands)
        (run! #(.addBatch stmt %) [commands])
        (run! #(.addBatch stmt %) commands))
      (into [] (.executeBatch stmt)))
    (:connection connectable)
    (do-commands (:connection connectable) commands)
    :else
    (with-open [conn (jdbc/get-connection connectable)]
      (do-commands conn commands))))

(defn execute-command [config t-con expect-results? commands]
  (log/trace "executing" commands)
  (cond->
    (try
      (do-commands t-con (parse-commands-sql config commands))
      (catch SQLException e
        (log/error (format "failed to execute command:\n %s" commands))
        (loop [e e]
          (if-let [next-e (.getNextException e)]
            (recur next-e)
            (log/error (.getMessage e))))
        (throw e))
      (catch Throwable t
        (log/error (format "failed to execute command:\n %s\nFailure: %s" commands (.getMessage t)))
        (throw t)))
    expect-results? (check-expectations commands)))

(defn- run-sql*
  [config conn expect-results? commands direction]
  (log/debug "found" (count commands) (name direction) "migrations")
  (doseq [c commands]
    (execute-command config conn expect-results? c)))

(defn run-sql
  [{:keys [conn db modify-sql-fn expect-results?] :as config} sql direction]
  (when-let [commands (mapcat (wrap-modify-sql-fn modify-sql-fn) (split-commands sql expect-results?))]
    (if (use-tx? sql)
      (jdbc/with-transaction [t-con (or conn db)]
        (run-sql* config t-con expect-results? commands direction))
      (run-sql* config (or conn db) expect-results? commands direction))))

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
  (let [ext (proto/get-extension* x)]
    [(str migration-name ".up." ext)
     (str migration-name ".down." ext)]))

(defmethod proto/squash-migration-files* :sql
  [x migration-dir migration-name ups downs]
  (doall
   (for [[mig-file sql] (map vector (proto/migration-files* x migration-name) [ups downs])]
     (let [file (io/file migration-dir mig-file)]
       (.createNewFile file)
       (with-open [writer (java.io.BufferedWriter. (java.io.FileWriter. file))]
         (.write writer sql))
       (.getName (io/file migration-dir mig-file))))))
