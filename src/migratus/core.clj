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
            [migratus.database :as database]))

(defn set-migrations-dir [config]
  (update-in config [:migration-dir] #(or % "migrations")))

(defn run [config ids command]
  (let [db (database/connect config)
        config (set-migrations-dir config)]
    (try
      (log/info "Starting migrations")
      (log/info "Using migrations from" (str "'" (:migration-dir config) "'"))
      (database/init-schema! config db)
      (command config db ids)
      (finally
        (log/info "Ending migrations")
        (database/disconnect db)))))

(defn- uncompleted-migrations [config db]
  (let [completed? (database/completed-ids config db)]
    (remove (comp completed? proto/id) (database/migrations config))))

(defn migration-name [migration]
  (str (proto/id migration) "-" (proto/name migration)))

(defn- up* [migration db]
  (log/info "Up" (migration-name migration))
  (proto/up migration db))

(defn- migrate* [config db _]
  (let [ids (uncompleted-migrations config db)
        migrations (sort-by proto/id ids)]
    (when (seq migrations)
      (log/info "Running up for" (pr-str (vec (map proto/id migrations))))
      (doseq [migration migrations]
        (up* migration db)))))

(defn migrate
  "Bring up any migrations that are not completed."
  [config]
  (run config nil migrate*))

(defn- run-up [config db ids]
  (let [completed (database/completed-ids config db)
        ids (set/difference (set ids) completed)
        migrations (filter (comp ids proto/id) (database/migrations config))]
    (migrate* migrations db ids)))

(defn up
  "Bring up the migrations identified by ids.  Any migrations that are already
  complete will be skipped."
  [config & ids]
  (run config ids run-up))

(defn- run-down [config db ids]
  (let [completed (database/completed-ids config db)
        ids (set/intersection (set ids) completed)
        migrations (filter (comp ids proto/id)
                           (database/migrations config))
        migrations (reverse (sort-by proto/id migrations))]
    (when (seq migrations)
      (log/info "Running down for" (pr-str (vec (map proto/id migrations))))
      (doseq [migration migrations]
        (log/info "Down" (migration-name migration))
        (proto/down migration db)))))

(defn down
  "Bring down the migrations identified by ids.  Any migrations that are not
  completed will be skipped."
  [config & ids]
  (run config ids run-down))

(defn- rollback* [config db _]
  (println "found ids"
           (->> (database/completed-ids config db)
                sort
                last
                vector))
  (run-down
    config
    db
    (->> (database/completed-ids config db)
         sort
         last
         vector)))

(defn rollback
  "Rollback the last migration"
  [config]
  (run config nil rollback*))
