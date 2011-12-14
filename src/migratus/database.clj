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
            [clojure.tools.logging :as log]
            [migratus.protocols :as proto])
  (:use [robert.bruce :only [try-try-again]]))

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

(defn up* [table-name id up]
  (sql/transaction
   (when (not (complete? table-name id))
     (sql/do-commands up)
     (mark-complete table-name id))))

(defn down* [table-name id down]
  (sql/transaction
   (when (complete? table-name id)
     (sql/do-commands down)
     (mark-not-complete table-name id))))

(defrecord Migration [table-name id name up down]
  proto/Migration
  (proto/id [this]
    id)
  (proto/name [this]
    name)
  (proto/up [this]
    (if up
      (try-try-again up* table-name id up)
      (throw (Exception. (format "Up commands not found for %d" id)))))
  (proto/down [this]
    (if down
      (try-try-again down* table-name id down)
      (throw (Exception. (format "Down commands not found for %d" id))))))

(defn parse-name [file-name]
  (next (re-matches #"^(\d{14})-([^\.]+)\.(up|down)\.sql$" file-name)))

(defn create-name [id name direction]
  (str id "-" name "." direction ".sql"))

(defn find-migrations [dir]
  (->> (for [f (filter (memfn isFile) (file-seq (io/file dir)))
             :let [file-name (.getName f)]]
         (if-let [[id name &_] (parse-name file-name)]
           {:id (Long/parseLong id) :name name}
           (log/warn (str "'" file-name "'")
                     "does not appear to be a valid migration")))
       (remove nil?)
       set))

(defn slurp-file [migration-dir id name direction]
  (let [file-name (str id "-" name "." direction ".sql")
        f (io/file migration-dir file-name)]
    (when (.exists f)
      (slurp f))))

(def default-table-name "schema_migrations")

(defrecord Database [config]
  proto/Store
  (proto/completed-ids [this]
    (sql/transaction
     (sql/with-query-results results
       [(str "select * from " (:migration-table-name config
                                                     default-table-name))]
       (doall (map :id results)))))
  (proto/migrations [this]
    (for [{:keys [id name]} (find-migrations (:migration-dir config))]
      (Migration. (:migration-table-name config default-table-name) id name
                  (slurp-file (:migration-dir config) id name "up")
                  (slurp-file (:migration-dir config) id name "down"))))
  (proto/begin [this]
    (try
      (let [conn (sqli/get-connection (:db config))]
        (push-thread-bindings {#'sqli/*db*
                               (assoc sqli/*db*
                                 :connection conn
                                 :level 0
                                 :rollback (atom false))})
        (.setAutoCommit conn false))
      (catch Exception _
        (push-thread-bindings {#'sqli/*db* sqli/*db*}))))
  (proto/end [this]
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
