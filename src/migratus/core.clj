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
            [migratus.migrations :as mig]
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

(defn uncompleted-migrations [config store]
  (let [completed? (set (proto/completed-ids store))]
    (remove (comp completed? proto/id) (mig/list-migrations config))))

(defn migration-name [migration]
  (str (proto/id migration) "-" (proto/name migration)))

(defn- up* [store migration]
  (log/info "Up" (migration-name migration))
  (proto/migrate-up store migration))

(defn- migrate-up* [store migrations]
  (let [migrations (sort-by proto/id migrations)]
    (when (seq migrations)
      (log/info "Running up for" (pr-str (vec (map proto/id migrations))))
      (loop [[migration & more] migrations]
        (when migration
          (case (up* store migration)
            :success (recur more)
            :ignore (log/info "Migration reserved by another instance. Ignoring.")
            (log/error "Stopping:" (migration-name migration) "failed to migrate")))))))

(defn- migrate* [config store _]
  (let [migrations (->> store
                        (uncompleted-migrations config)
                        (sort-by proto/id))]
    (migrate-up* store migrations)))

(defn migrate
  "Bring up any migrations that are not completed."
  [config]
  (run (proto/make-store config) nil (partial migrate* config)))

(defn- run-up [config store ids]
  (let [completed  (set (proto/completed-ids store))
        ids        (set/difference (set ids) completed)
        migrations (filter (comp ids proto/id) (mig/list-migrations config))]
    (migrate-up* store migrations)))

(defn up
  "Bring up the migrations identified by ids.
  Any migrations that are already complete will be skipped."
  [config & ids]
  (run (proto/make-store config) ids (partial run-up config)))

(defn- run-down [config store ids]
  (let [completed  (set (proto/completed-ids store))
        ids        (set/intersection (set ids) completed)
        migrations (filter (comp ids proto/id)
                           (mig/list-migrations config))
        migrations (reverse (sort-by proto/id migrations))]
    (when (seq migrations)
      (log/info "Running down for" (pr-str (vec (map proto/id migrations))))
      (doseq [migration migrations]
        (log/info "Down" (migration-name migration))
        (proto/migrate-down store migration)))))

(defn down
  "Bring down the migrations identified by ids.
  Any migrations that are not completed will be skipped."
  [config & ids]
  (run (proto/make-store config) ids (partial run-down config)))

(defn- rollback* [config store _]
  (run-down
    config
    store
    (->> (proto/completed-ids store)
         sort
         last
         vector)))

(defn- reset* [config store _]
  (run-down config store (->> (proto/completed-ids store) sort)))

(defn rollback
  "Rollback the last migration that was successfully applied."
  [config]
  (run (proto/make-store config) nil (partial rollback* config)))

(defn reset
  "Reset the database by down-ing all migrations successfully
  applied, then up-ing all migratinos."
  [config]
  (run (proto/make-store config) nil (partial reset* config))
  (migrate config))

(defn init
  "Initialize the data store"
  [config & [name]]
  (proto/init (proto/make-store config)))

(defn create
  "Create a new migration with the current date"
  [config & [name type]]
  (mig/create config name (or type :sql)))

(defn destroy
  "Destroy migration"
  [config & [name]]
  (mig/destroy config name))

(defn pending-list
  "List pending migrations"
  [config]
  (let [migrations-name  (->> (doto (proto/make-store config)
                                (proto/connect))
                              (uncompleted-migrations config)
                              (mapv proto/name))
        migrations-count (count migrations-name)]
    (log/debug "You have " migrations-count " pending migrations:\n"
               (clojure.string/join "\n" migrations-name))
    migrations-name))

(defn migrate-until-just-before
  "Run all migrations preceding migration-id. This is useful when testing that a
  migration behaves as expected on fixture data. This only considers uncompleted
  migrations, and will not migrate down."
  [config migration-id]
  (let [store (proto/make-store config)]
    (try
      (proto/connect store)
      (->> (uncompleted-migrations config store)
           (map proto/id)
           distinct
           sort
           (take-while #(< % migration-id))
           (apply up config))
      (finally (proto/disconnect store)))))
