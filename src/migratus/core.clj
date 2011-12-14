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
            [migratus.protocols :as proto]))

(defn run [store f]
  (try
    (log/info "Starting migrations")
    (proto/begin store)
    (f)
    (finally
     (log/info "Ending migrations")
     (proto/end store))))

(defn- uncompleted-migrations [store]
  (let [completed? (set (proto/completed-ids store))]
    (remove (comp completed? proto/id) (proto/migrations store))))

(defn migration-name [migration]
  (str (proto/id migration) "-" (proto/name migration)))

(defn- up* [migration]
  (log/info "Up" (migration-name migration))
  (proto/up migration))

(defn- migrate* [migrations]
  (let [migrations (sort-by proto/id migrations)]
    (when (seq migrations)
      (log/info "Running up for" (pr-str (vec (map proto/id migrations))))
      (doseq [migration migrations]
        (up* migration)))))

(defn require-plugin [{:keys [store]}]
  (if-not store
    (throw (Exception. "Store is not configured")))
  (let [plugin (symbol (str "migratus." (name store)))]
    (require plugin)))

(defn migrate
  "Bring up any migrations that are not completed."
  [config]
  (require-plugin config)
  (let [store (proto/make-store config)]
    (run store #(migrate* (uncompleted-migrations store)))))

(defn- run-up [config store ids]
  (let [completed (set (proto/completed-ids store))
        ids (set/difference (set ids) completed)
        migrations (filter (comp ids proto/id) (proto/migrations store))]
    (migrate* migrations)))

(defn up
  "Bring up the migrations identified by ids.  Any migrations that are already
  complete will be skipped."
  [config & ids]
  (require-plugin config)
  (let [store (proto/make-store config)]
    (run store #(run-up config store ids))))

(defn- run-down [config store ids]
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
  "Bring down the migrations identified by ids.  Any migrations that are not
  completed will be skipped."
  [config & ids]
  (require-plugin config)
  (let [store (proto/make-store config)]
    (run store #(run-down config store ids))))
