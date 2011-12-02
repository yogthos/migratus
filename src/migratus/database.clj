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
            [migratus.protocols :as proto])
  (:use [robert.bruce :only [try-try-again]]))

(def schema-table-name "schema_migrations")

(defn complete? [version]
  (sql/with-query-results results
    [(str "SELECT * from " schema-table-name " WHERE version=?") version]
    (first results)))

(defn mark-complete [version]
  (sql/insert-rows schema-table-name [version]))

(defn mark-not-complete [version]
  (sql/delete-rows schema-table-name ["version=?" version]))

(defn up* [version up]
  (sql/transaction
   (when (not (complete? version))
     (sql/do-commands up)
     (mark-complete version))))

(defn down* [version down]
  (sql/transaction
   (when (complete? version)
     (sql/do-commands down)
     (mark-not-complete version))))

(defrecord Migration [version name up down]
  proto/Migration
  (proto/version [this]
    version)
  (proto/name [this]
    name)
  (proto/up [this]
    (if up
      (try-try-again up* version up)
      (Exception. (format "Up commands not found for %d" version))))
  (proto/down [this]
    (if down
      (try-try-again down* version down)
      (Exception. (format "Down commands not found for %d" version)))))

(defn parse-name [file-name]
  (next (re-matches #".*(\d{14})-([^\.]+)\.(up|down)\.sql$" file-name)))

(defn create-name [version name direction]
  (str version "-" name "." direction ".sql"))

(defn find-migrations [dir]
  (set (for [f (filter (memfn isFile) (file-seq (io/file dir)))]
         (if-let [[version name &_] (parse-name (.getName f))]
           {:version (Long/parseLong version) :name name}))))

(defn slurp-file [migration-dir version name direction]
  (let [file-name (str version "-" name "." direction ".sql")
        f (io/file migration-dir file-name)]
    (if (.exists f)
      (slurp f))))

(defrecord Database [config]
  proto/Store
  (proto/completed-versions [this]
    (sql/transaction
     (sql/with-query-results results
       [(str "select * from " schema-table-name)]
       (doall (map :version results)))))
  (proto/migrations [this]
    (for [{:keys [version name]} (find-migrations (:migration-dir config))]
      (Migration. version name
                  (slurp-file (:migration-dir config) version name "up")
                  (slurp-file (:migration-dir config) version name "down")))))

(defn table-exists? [table-name]
  (let [conn (sql/find-connection)]
    (sql/resultset-seq
     (-> conn
         .getMetaData
         (.getTables (.getCatalog conn) nil table-name nil)))))

(defn create-table []
  (sql/create-table schema-table-name
                    ["version" "BIGINT" "UNIQUE" "NOT NULL"]))

(defmethod proto/make-store :database
  [config]
  (sql/transaction
   (if-not (table-exists? schema-table-name)
     (create-table)))
  (if (empty? (:migration-dir config))
    (throw (Exception. "Migration directory is not configured")))
  (Database. config))
