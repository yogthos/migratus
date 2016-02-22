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
(ns migratus.core
  (:require [clojure.set :as set]
            [clojure.tools.logging :as log]
            [migratus.protocols :as proto]
            migratus.database))


(defn run [store ids command]
  (try
    (log/info "Starting migrations")
    (proto/connect store)
    (command store ids)
    (catch java.sql.BatchUpdateException e
      (throw (or (.getNextException e) e)))
    (finally
      (log/info "Ending migrations")
      (proto/disconnect store))))

(defn require-plugin [{:keys [store]}]
  (if-not store
    (throw (Exception. "Store is not configured")))
  (let [plugin (symbol (str "migratus." (name store)))]
    (require plugin)))

(defn- uncompleted-migrations [store]
  (let [completed? (set (proto/completed-ids store))]
    (remove (comp completed? proto/id) (proto/migrations store))))

(defn migration-name [migration]
  (str (proto/id migration) "-" (proto/name migration)))

(defn- up* [migration]
  (log/info "Up" (migration-name migration))
  (proto/up migration))

(defn- migrate-up* [migrations]
  (let [migrations (sort-by proto/id migrations)]
    (when (seq migrations)
      (log/info "Running up for" (pr-str (vec (map proto/id migrations))))
      (doseq [migration migrations]
        (up* migration)))))

(defn- migrate* [store _]
  (let [migrations (->> store uncompleted-migrations (sort-by proto/id))]
    (migrate-up* migrations)))

(defn migrate
  "Bring up any migrations that are not completed."
  [config]
  (run (proto/make-store config) nil migrate*))

(defn- run-up [store ids]
  (let [completed (set (proto/completed-ids store))
        ids (set/difference (set ids) completed)
        migrations (filter (comp ids proto/id) (proto/migrations store))]
    (migrate-up* migrations)))

(defn up
  "Bring up the migrations identified by ids.
  Any migrations that are already complete will be skipped."
  [config & ids]
  (run (proto/make-store config) ids run-up))

(defn- run-down [store ids]
  (let [completed (set (proto/completed-ids store))
        ids (set/intersection (set ids) completed)
        migrations (filter (comp ids proto/id)
                           (proto/migrations store))
        migrations (reverse (sort-by proto/id migrations))]
    (when (seq migrations)
      (log/info "Running down for" (pr-str (vec (map proto/id migrations))))
      (doseq [migration migrations]
        (log/info "Down" (migration-name migration))
        (proto/down migration)))))

(defn down
  "Bring down the migrations identified by ids.
  Any migrations that are not completed will be skipped."
  [config & ids]
  (run (proto/make-store config) ids run-down))

(defn- rollback* [store _]
  (run-down
    store
    (->> (proto/completed-ids store)
         sort
         last
         vector)))

(defn- reset* [store _]
  (run-down store (->> proto/completed-ids store))
  (migrate))

(defn rollback
  "Rollback the last migration that was successfully applied."
  [config]
  (run (proto/make-store config) nil rollback*))

(defn reset
  "Reset the database by down-ing all migrations successfully
  applied, then up-ing all migratinos."
  [config]
  (run (proto/make-store config) nil reset*))

(defn create
  "Create a new migration with the current date"
  [config & [name]]
  (proto/create (proto/make-store config) name))


(defn destroy
    "Destroy migration"
    [config & [name]]
    (proto/destroy (proto/make-store config) name))
