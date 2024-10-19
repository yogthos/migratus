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
(ns migratus.protocols
  (:refer-clojure :exclude [name]))

(defprotocol Migration
  (id [this] "Id of this migration.")
  (migration-type [this] "Type of this migration.")
  (name [this] "Name of this migration")
  (tx? [this direction] "Whether this migration should run in a transaction.")
  (up [this config] "Bring this migration up.")
  (down [this config] "Bring this migration down."))

(defprotocol Store
  (config [this])
  (init [this]
    "Initialize the data store.")
  (completed-ids [this]
    "Seq of ids of completed migrations in descending order of applied
    date.")
  (completed [this]
    "Seq of applied migrations in descending order of applied
      date.")
  (migrate-up [this migration]
    "Run and record an up migration")
  (migrate-down [this migration]
    "Run and record a down migration")
  (connect [this]
    "Opens resources necessary to run migrations against the store.")
  (disconnect [this]
    "Frees resources necessary to run migrations against the store."))

(defmulti make-store :store)

(defmulti make-migration*
  "Dispatcher to create migrations based on filename extension. To add support
  for a new migration filename type, add a new defmethod for this."
  (fn [mig-type mig-id mig-name payload config]
    mig-type))

(defmethod make-migration* :default
  [mig-type mig-id mig-name payload config]
  (throw (Exception. (format "Unknown type '%s' for migration %d"
                             (clojure.core/name mig-type) mig-id))))

(defmulti migration-files*
  "Dispatcher to get a list of filenames to create when creating new migrations"
  (fn [mig-type migration-name]
    mig-type))

(defmethod migration-files* :default
  [mig-type migration-name]
  (throw (Exception. (format "Unknown migration type '%s'"
                             (clojure.core/name mig-type)))))


(defmulti get-extension*
  "Dispatcher to get the supported file extension for this migration"
  (fn [mig-type]
    mig-type))

(defmethod get-extension* :default
  [mig-type]
  (throw (Exception. (format "Unknown migration type '%s'"
                             (clojure.core/name mig-type)))))


(defn get-all-supported-extensions
  "Returns a seq of all the file extensions supported by all migration protocols"
  []
  (for [[k v] (methods get-extension*)
        :when (-> k (= :default) not)]
    (v k)))

(defmulti squash-migration-files*
  "Dispatcher to read a list of files and squash them into a single migration"
  (fn [mig-type migration-dir migration-name ups downs]
    mig-type))

(defmethod squash-migration-files* :default
  [mig-type migration-dir migration-name ups downs]
  (throw (Exception. (format "Unknown migration type '%s'"
                             (clojure.core/name mig-type)))))
