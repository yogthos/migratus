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
            [clojure.java.jdbc.internal :as sqli]
            [clojure.java.classpath :as cp]
            [clojure.tools.logging :as log]
            [migratus.protocols :as proto])
  (:use [robert.bruce :only [try-try-again]])
  (:import (java.io File StringWriter)
           (java.sql Connection)
           (java.util.regex Pattern)))

(defn complete? [table-name id]
  (sql/with-query-results results
    [(str "SELECT * from " table-name " WHERE id=?") id]
    (first results)))

(defn mark-complete [table-name id]
  (log/debug "marking" id "complete")
  (sql/insert-rows table-name [id]))

(defn mark-not-complete [table-name id]
  (log/debug "marking" id "not complete")
  (sql/delete-rows table-name ["id=?" id]))

(def sep (Pattern/compile "^--;;.*\n" Pattern/MULTILINE))

(defn split-commands [up]
  (.split sep up))

(defn up* [table-name id up]
  (sql/transaction
   (when (not (complete? table-name id))
     (doseq [c (split-commands up)]
       (sql/do-commands c))
     (mark-complete table-name id))))

(defn down* [table-name id down]
  (sql/transaction
   (when (complete? table-name id)
     (sql/do-commands down)
     (mark-not-complete table-name id))))

(defrecord Migration [table-name id name up down]
  proto/Migration
  (id [this]
    id)
  (name [this]
    name)
  (up [this]
    (if up
      (try-try-again up* table-name id up)
      (throw (Exception. (format "Up commands not found for %d" id)))))
  (down [this]
    (if down
      (try-try-again down* table-name id down)
      (throw (Exception. (format "Down commands not found for %d" id))))))

(defn parse-name [file-name]
  (next (re-matches #"^(\d{14})-([^\.]+)\.(up|down)\.sql$" file-name)))

(defn create-name [id name direction]
  (str id "-" name "." direction ".sql"))

(defn find-migration-dir [dir]
  (first (filter #(.exists %)
                 (map #(io/file % dir)
                      (cp/classpath-directories)))))

(defn find-migration-files [migration-dir]
  (->> (for [f (filter (fn [^File f]
                         (.isFile f)) (file-seq migration-dir))
             :let [file-name (.getName ^File f)]]
         (if-let [[id name direction] (parse-name file-name)]
           {id {direction {:id id :name name :direction direction
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
             {id {direction {:id id :name name :direction direction
                             :content (.toString w)}}})))
       (remove nil?)))

(defn find-migrations [dir]
  (->> (let [dir (ensure-trailing-slash dir)]
         (if-let [migration-dir (find-migration-dir dir)]
           (find-migration-files migration-dir)
           (if-let [migration-jar (find-migration-jar dir)]
             (find-migration-resources dir migration-jar))))
       (apply (partial merge-with merge))))

(defn slurp-file [migration-dir id name direction]
  (let [file-name (str id "-" name "." direction ".sql")
        f (io/file migration-dir file-name)]
    (when (.exists f)
      (slurp f))))

(def default-table-name "schema_migrations")

(defrecord Database [config]
  proto/Store
  (completed-ids [this]
    (sql/transaction
     (sql/with-query-results results
       [(str "select * from " (:migration-table-name config
                                                     default-table-name))]
       (doall (map :id results)))))
  (migrations [this]
    (let [migrations (find-migrations (:migration-dir config))
          table-name (:migration-table-name config default-table-name)]
      (for [[id mig] migrations
            :let [{:strs [up down]} mig]]
        (Migration. table-name
                    (Long/parseLong (or (:id up) (:id down)))
                    (or (:name up) (:name down))
                    (:content up)
                    (:content down)))))
  (begin [this]
    (let [^Connection conn (try
                             (sqli/get-connection (:db config))
                             (catch Exception e
                               (log/error e "Error creating DB connection")
                               nil))]
      (if conn
        (push-thread-bindings {#'sqli/*db*
                               (assoc sqli/*db*
                                 :connection conn
                                 :level 0
                                 :rollback (atom false))})
        (push-thread-bindings {#'sqli/*db* sqli/*db*}))
      (.setAutoCommit conn false)))
  (end [this]
    (try
      (when-let [conn (sql/find-connection)]
        (.close conn))
      (finally
       (pop-thread-bindings)))))

(defn table-exists? [table-name]
  (let [conn (sql/find-connection)]
    (sql/resultset-seq
     (-> conn
         .getMetaData
         (.getTables (.getCatalog conn) nil table-name nil)))))

(defn create-table [table-name]
  (log/info "creating migration table" (str "'" table-name "'"))
  (sql/create-table table-name
                    ["id" "BIGINT" "UNIQUE" "NOT NULL"]))

(defmethod proto/make-store :database
  [config]
  (let [table-name (:migration-table-name config default-table-name)]
    (sql/with-connection (:db config)
      (sql/transaction
       (when-not (table-exists? table-name)
         (create-table table-name)))))
  (when (empty? (:migration-dir config))
    (throw (Exception. "Migration directory is not configured")))
  (Database. config))
