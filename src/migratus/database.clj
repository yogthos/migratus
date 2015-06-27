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
            [clojure.java.classpath :as cp]
            [clojure.tools.logging :as log]
            [migratus.protocols :as proto])
  (:use [robert.bruce :only [try-try-again]])
  (:import [java.io File StringWriter]
           java.sql.Connection
           java.util.regex.Pattern))

(defn complete? [db table-name id]
  (first (sql/query db [(str "SELECT * from " table-name " WHERE id=?") id])))

(defn mark-complete [db table-name id]
  (log/debug "marking" id "complete")
  (sql/insert! db table-name {:id id}))

(defn mark-not-complete [db table-name id]
  (log/debug "marking" id "not complete")
  (sql/delete! db table-name ["id=?" id]))

(def sep (Pattern/compile "^--;;.*\n" Pattern/MULTILINE))
(def sql-comment (Pattern/compile "^--.*" Pattern/MULTILINE))
(def empty-line (Pattern/compile "^[ ]+" Pattern/MULTILINE))

(defn sanitize [command]
  (-> command
      (clojure.string/replace sql-comment "")
      (clojure.string/replace empty-line "")))

(defn split-commands [commands]
  (->> (.split sep commands)
       (map sanitize)
       (map sanitize)
       (remove empty?)))

(defn up* [db table-name id up]
  (sql/with-db-transaction
    [t-con db]
    (when-not (complete? t-con table-name id)
      (let [commands (split-commands up)]
        (log/debug "found" (count commands) "up migrations")
        (doseq [c commands]
          (log/trace "executing" c)
          (sql/db-do-commands db c)))
      (mark-complete t-con table-name id))))

(defn down* [db table-name id down]
  (sql/with-db-transaction
    [t-con db]
    (when (complete? db table-name id)
      (let [commands (split-commands down)]
        (log/debug "found" (count commands) "down migrations")
        (doseq [c commands]
          (log/trace "executing" c)
          (sql/db-do-commands db c)))
      (mark-not-complete db table-name id))))

(defrecord Migration [table-name id name up down]
  proto/Migration
  (id [this]
    id)
  (name [this]
    name)
  (up [this db]
    (if up
      (up* db table-name id up)
      #_(try-try-again {:sleep 1000 :tries 3 :decay :exponential}
                     up* db table-name id up)
      (throw (Exception. (format "Up commands not found for %d" id)))))
  (down [this db]
    (if down
      (try-try-again {:sleep 1000 :tries 3 :decay :exponential}
                     down* db table-name id down)
      (throw (Exception. (format "Down commands not found for %d" id))))))

(defn parse-name [file-name]
  (next (re-matches #"^(\d{14})-([^\.]+)\.(up|down)\.sql$" file-name)))

(defn find-migration-dir [dir]
  (->> (cp/classpath-directories)
       (map #(io/file % dir))
       (filter #(.exists %))
       first))

(defn find-migration-files [migration-dir]
  (->> (for [f (filter (fn [^File f]
                         (.isFile f)) (file-seq migration-dir))
             :let [file-name (.getName ^File f)]]
         (if-let [[id name direction] (parse-name file-name)]
           {id {direction {:id      id :name name :direction direction
                           :content (slurp f)}}}
           (log/warn (str "'" file-name "'")
                     "does not appear to be a valid migration")))
       (remove nil?)))

(defn ensure-trailing-slash [dir]
  (if (not= (last dir) \/)
    (str dir "/")
    dir))

(defn find-migration-jar [dir]
  (first (for [jar (cp/classpath-jarfiles)
               :when (some #(.matches (.getName %)
                                      (str "^" (Pattern/quote dir) ".*"))
                           (enumeration-seq (.entries jar)))]
           jar)))

(defn find-migration-resources [dir jar]
  (->> (for [entry (enumeration-seq (.entries jar))
             :when (.matches (.getName entry)
                             (str "^" (Pattern/quote dir) ".*"))
             :let [entry-name (.replaceAll (.getName entry) dir "")]]
         (if-let [[id name direction] (parse-name entry-name)]
           (let [w (StringWriter.)]
             (io/copy (.getInputStream jar entry) w)
             {id {direction {:id      id :name name :direction direction
                             :content (.toString w)}}})))
       (remove nil?)))

(defn find-migrations [dir]
  (->> (let [dir (ensure-trailing-slash dir)]
         (if-let [migration-dir (find-migration-dir dir)]
           (find-migration-files migration-dir)
           (if-let [migration-jar (find-migration-jar dir)]
             (find-migration-resources dir migration-jar))))
       (apply (partial merge-with merge))))

(defn migration-table-name [config]
  (:migration-table-name config "schema_migrations"))

(defn parse-migration-id [id]
  (try
    (Long/parseLong id)
    (catch Exception e
      (log/error e (str "failed to parse migration id: " id)))))

(defn connect [config]
  (let [^Connection conn
        (try
          (sql/get-connection (:db config))
          (catch Exception e
            (log/error e (str "Error creating DB connection for " (:db config)))))]
    (.setAutoCommit conn false)
    {:connection conn}))

(defn disconnect [{:keys [connection]}]
  (when-not (.isClosed connection)
    (.close connection)))

(defn completed-ids [config db]
  (sql/with-db-transaction
    [t-con db]
    (->> (sql/query t-con (str "select * from " (migration-table-name config)))
         (map :id)
         set
         (doall))))

(defn migrations [config]
  (let [migrations (find-migrations (:migration-dir config))
        table-name (migration-table-name config)]
    (for [[id mig] migrations
          :let [{:strs [up down]} mig]]
      (Migration. table-name
                  (parse-migration-id (or (:id up) (:id down)))
                  (or (:name up) (:name down))
                  (:content up)
                  (:content down)))))

;;;;;deprecate
#_(defrecord Database [config]
  proto/Store
  (completed-ids [this db]
    (sql/with-db-transaction [t-con db]
                             (->> (sql/query t-con (str "select * from " (migration-table-name config)))
                                  (map :id)
                                  set
                                  (doall))))
  (migrations [this]
    (let [migrations (find-migrations (:migration-dir config))
          table-name (migration-table-name config)]
      (for [[id mig] migrations
            :let [{:strs [up down]} mig]]
        (Migration. table-name
                    (parse-migration-id (or (:id up) (:id down)))
                    (or (:name up) (:name down))
                    (:content up)
                    (:content down)))))
  (begin [this]
    (let [^Connection conn (try
                             (#'sql/get-connection (:db config))
                             (catch Exception e
                               (log/error e (str "Error creating DB connection for " (:db config)))))]
      (.setAutoCommit conn false)
      conn))
  (end [this conn]
    (when-not (.isClosed conn)
      (.close conn))))
;;;

(defn table-exists? [conn table-name]
  (let [conn (:connection conn)]
    (-> conn
        .getMetaData
        (.getTables (.getCatalog conn) nil (.toUpperCase table-name) nil)
        sql/result-set-seq
        not-empty
        boolean)))

(defn init-schema! [config db]
  (let [table-name (migration-table-name config)]
    (sql/with-db-transaction
      [t-con db]
      (sql/db-do-commands
        t-con
        (when-not (table-exists? t-con table-name)
          (log/info "creating migration table" (str "'" table-name "'"))
          (sql/db-do-commands t-con
                              (sql/create-table-ddl table-name ["id" "BIGINT" "UNIQUE" "NOT NULL"])))))))
