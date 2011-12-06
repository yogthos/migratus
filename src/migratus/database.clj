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

(defn complete? [id]
  (sql/with-query-results results
    [(str "SELECT * from " schema-table-name " WHERE id=?") id]
    (first results)))

(defn mark-complete [id]
  (sql/insert-rows schema-table-name [id]))

(defn mark-not-complete [id]
  (sql/delete-rows schema-table-name ["id=?" id]))

(defn up* [id up]
  (sql/transaction
   (when (not (complete? id))
     (sql/do-commands up)
     (mark-complete id))))

(defn down* [id down]
  (sql/transaction
   (when (complete? id)
     (sql/do-commands down)
     (mark-not-complete id))))

(defrecord Migration [id name up down]
  proto/Migration
  (proto/id [this]
    id)
  (proto/name [this]
    name)
  (proto/up [this]
    (if up
      (try-try-again up* id up)
      (Exception. (format "Up commands not found for %d" id))))
  (proto/down [this]
    (if down
      (try-try-again down* id down)
      (Exception. (format "Down commands not found for %d" id)))))

(defn parse-name [file-name]
  (next (re-matches #".*(\d{14})-([^\.]+)\.(up|down)\.sql$" file-name)))

(defn create-name [id name direction]
  (str id "-" name "." direction ".sql"))

(defn find-migrations [dir]
  (set (for [f (filter (memfn isFile) (file-seq (io/file dir)))]
         (if-let [[id name &_] (parse-name (.getName f))]
           {:id (Long/parseLong id) :name name}))))

(defn slurp-file [migration-dir id name direction]
  (let [file-name (str id "-" name "." direction ".sql")
        f (io/file migration-dir file-name)]
    (if (.exists f)
      (slurp f))))

(defrecord Database [config]
  proto/Store
  (proto/completed-ids [this]
    (sql/transaction
     (sql/with-query-results results
       [(str "select * from " schema-table-name)]
       (doall (map :id results)))))
  (proto/migrations [this]
    (for [{:keys [id name]} (find-migrations (:migration-dir config))]
      (Migration. id name
                  (slurp-file (:migration-dir config) id name "up")
                  (slurp-file (:migration-dir config) id name "down"))))
  (proto/run [this migration-fn]
    (sql/with-connection (:db config)
      (sql/transaction
       (migration-fn)))))

(defn table-exists? [table-name]
  (let [conn (sql/find-connection)]
    (sql/resultset-seq
     (-> conn
         .getMetaData
         (.getTables (.getCatalog conn) nil table-name nil)))))

(defn create-table []
  (sql/create-table schema-table-name
                    ["id" "BIGINT" "UNIQUE" "NOT NULL"]))

(defmethod proto/make-store :database
  [config]
  (sql/with-connection (:db config)
    (sql/transaction
     (if-not (table-exists? schema-table-name)
       (create-table))))
  (if (empty? (:migration-dir config))
    (throw (Exception. "Migration directory is not configured")))
  (Database. config))
