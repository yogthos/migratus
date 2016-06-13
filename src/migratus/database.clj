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
  (:import [java.io File StringWriter]
           java.sql.Connection
           java.text.SimpleDateFormat
           java.util.Date
           [java.util.jar JarEntry JarFile]
           java.util.regex.Pattern))

(defn ->kebab-case [s]
  (-> (reduce
        (fn [s c]
          (if (and
                (not-empty s)
                (Character/isLowerCase (last s))
                (Character/isUpperCase c))
            (str s "-" c)
            (str s c)))
        "" s)
      (clojure.string/replace #"[\s]+" "-")
      (.replaceAll "_" "-")
      (.toLowerCase)))

(defn complete? [db table-name id]
  (first (sql/query db [(str "SELECT * from " table-name " WHERE id=?") id])))

(defn mark-complete [db table-name id]
  (log/debug "marking" id "complete")
  (sql/insert! db table-name {:id id}))

(defn mark-not-complete [db table-name id]
  (log/debug "marking" id "not complete")
  (sql/delete! db table-name ["id=?" id]))

(def sep (Pattern/compile "^--;;.*\r?\n" Pattern/MULTILINE))
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

(defn up* [db table-name id up modify-sql-fn]
  (sql/with-db-transaction
    [t-con db]
    (when-not (complete? t-con table-name id)
      (when-let [commands (map modify-sql-fn (split-commands up))]
        (log/debug "found" (count commands) "up migrations")
        (doseq [c commands]
          (log/trace "executing" c)
          (try
            (sql/db-do-prepared db c)
            (catch Throwable t
              (log/error t "failed to execute command:\n" c "\n")
              (throw t))))
        (mark-complete t-con table-name id)
        true))))

(defn down* [db table-name id down modify-sql-fn]
  (sql/with-db-transaction
    [t-con db]
    (when (complete? db table-name id)
      (when-let [commands (map modify-sql-fn (split-commands down))]
        (log/debug "found" (count commands) "down migrations")
        (doseq [c commands]
          (log/trace "executing" c)
          (sql/db-do-prepared db c))
        (mark-not-complete db table-name id)
        true))))

(defn parse-name [file-name]
  (next (re-matches #"^(\d+)-([^\.]+)\.(up|down)\.sql$" file-name)))

(defn find-migration-dir [dir]
  (->> (cp/classpath-directories)
       (map #(io/file % dir))
       (filter #(.exists ^File %))
       first))

(def default-migration-parent
  "resources/")

(defn find-or-create-migration-dir [dir]
  (if-let [migration-dir (find-migration-dir dir)]
    migration-dir

    ;; Couldn't find the migration dir, create it
    (let [new-migration-dir (io/file default-migration-parent dir)]
      (io/make-parents new-migration-dir ".")
      new-migration-dir)))


(defn find-migration-files [migration-dir]
  (->> (for [f (filter (fn [^File f]
                         (.isFile f)) (file-seq migration-dir))
             :let [file-name (.getName ^File f)]]
         (if-let [[id name direction] (parse-name file-name)]
           {id {direction {:id        id
                           :name      name
                           :direction direction
                           :content   (slurp f)}}}
           (log/warn (str "'" file-name "'")
                     "does not appear to be a valid migration")))
       (remove nil?)))

(defn ensure-trailing-slash [dir]
  (if (not= (last dir) \/)
    (str dir "/")
    dir))

(defn find-migration-jar [dir]
  (first (for [jar (cp/classpath-jarfiles)
               :when (some #(.matches (.getName ^JarEntry %)
                                      (str "^" (Pattern/quote dir) ".*"))
                           (enumeration-seq (.entries ^JarFile jar)))]
           jar)))

(defn find-migration-resources [dir jar]
  (->> (for [entry (enumeration-seq (.entries jar))
             :when (.matches (.getName ^JarEntry entry)
                             (str "^" (Pattern/quote dir) ".*"))
             :let [entry-name (.replaceAll (.getName ^JarEntry entry) dir "")]]
         (if-let [[id name direction] (parse-name entry-name)]
           (let [w (StringWriter.)]
             (io/copy (.getInputStream ^JarFile jar entry) w)
             {id {direction {:id        id
                             :name      name
                             :direction direction
                             :content   (.toString w)}}})))
       (remove nil?)))

(defn find-migrations [dir]
  (->> (let [dir (ensure-trailing-slash dir)]
         (if-let [migration-dir (find-migration-dir dir)]
           (find-migration-files migration-dir)
           (if-let [migration-jar (find-migration-jar dir)]
             (find-migration-resources dir migration-jar))))
       (apply (partial merge-with merge))))

(def default-migrations-table "schema_migrations")

(defn migration-table-name [config]
  (:migration-table-name config default-migrations-table))

(defn parse-migration-id [id]
  (try
    (Long/parseLong id)
    (catch Exception e
      (log/error e (str "failed to parse migration id: " id)))))

(defrecord Migration [db table-name id name up down modify-sql-fn]
  proto/Migration
  (id [this]
    id)
  (name [this]
    name)
  (up [this]
    (if up
      (up* db table-name id up modify-sql-fn)
      (throw (Exception. (format "Up commands not found for %d" id)))))
  (down [this]
    (if down
      (down* db table-name id down modify-sql-fn)
      (throw (Exception. (format "Down commands not found for %d" id))))))

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
    (->> (sql/query t-con (str "select id from " table-name))
         (map :id)
         (doall))))

(defn migrations* [db migration-dir table-name modify-sql-fn]
  (for [[id mig] (find-migrations migration-dir)
        :let [{:strs [up down]} mig]]
    (Migration. db
                table-name
                (parse-migration-id (or (:id up) (:id down)))
                (or (:name up) (:name down))
                (:content up)
                (:content down)
                modify-sql-fn)))

(defn method-exists? [obj method-name]
  (->> (.getClass obj)
       (.getDeclaredMethods)
       (map #(.getName %))
       (some #{method-name})))

(defn get-schema [conn]
  (try
    (if (method-exists? conn "getSchema") (.getSchema conn) nil)
    (catch java.sql.SQLFeatureNotSupportedException _)))

(defn lookup-tables
  [conn schema table-name]
  (let [metadata (.getMetaData conn)
        catalog  (.getCatalog conn)]
    (-> (.getTables metadata catalog schema table-name nil)
        sql/result-set-seq
        doall
        not-empty)))

(defn find-table
  "attempt to look up the table using the current schema
   fallback to lookup without the schema"
  [conn table-name]
  (or
    (lookup-tables conn (get-schema conn) table-name)
    (lookup-tables conn nil table-name)))

(defn table-exists? [conn table-name]
  (let [conn (:connection conn)]
    (or
      (find-table conn table-name)
      (find-table conn (.toUpperCase table-name)))))

(defn init-schema! [db table-name modify-sql-fn]
  (sql/with-db-transaction
    [t-con db]
    (when-not (table-exists? t-con table-name)
      (log/info "creating migration table" (str "'" table-name "'"))
      (sql/db-do-commands t-con
                          (modify-sql-fn
                           (sql/create-table-ddl table-name [["id" "BIGINT" "UNIQUE" "NOT NULL"]]))))))

(defn- timestamp []
  (let [fmt (SimpleDateFormat. "yyyyMMddHHmmss ")]
    (.format fmt (Date.))))

(defn destroy* [files]
  (doseq [f files]
    (.delete f)))

(defrecord Database [config]
  proto/Store
  (config [this] config)
  (completed-ids [this]
    (completed-ids* @(:connection config) (migration-table-name config)))
  (migrations [this]
    (migrations* @(:connection config)
                 (:migration-dir config)
                 (migration-table-name config)
                 (get config :modify-sql-fn identity)))
  (create [this name]
    (let [migration-dir (find-or-create-migration-dir (:migration-dir config))
          migration-name (->kebab-case (str (timestamp) name))
          migration-up-name (str migration-name ".up.sql")
          migration-down-name (str migration-name ".down.sql")]
      (.createNewFile (File. migration-dir migration-up-name))
      (.createNewFile (File. migration-dir migration-down-name))))
  (destroy [this name]
    (let [migration-dir (find-migration-dir (:migration-dir config))
          migration-name (->kebab-case name)
          pattern (re-pattern (str "[\\d]*-" migration-name ".*.sql"))
          migrations (file-seq migration-dir)]
      (when-let [files (filter #(re-find pattern (.getName %)) migrations)]
        (destroy* files))))
  (connect [this]
    (reset! (:connection config) (connect* (:db config)))
    (init-schema! @(:connection config) (migration-table-name config) (get config :modify-sql-fn identity)))
  (disconnect [this]
    (disconnect* @(:connection config))
    (reset! (:connection config) nil)))

(defmethod proto/make-store :database
  [config]
  (-> config
      (update-in [:migration-dir] #(or % "migrations"))
      (assoc :connection (atom nil))
      (Database.)))
