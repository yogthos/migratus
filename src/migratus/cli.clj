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
(ns migratus.cli
  (:require [migratus.protocols :as proto]
            [migratus.core :refer [migration-name require-plugin]]))

;; Wrapper implementations to print information during CLI invocations.
(defrecord CliMigration [migration]
  proto/Migration
  (id [this]
    (proto/id migration))
  (name [this]
    (proto/name migration))
  (up [this]
    (println "Up" (migration-name migration))
    (proto/up migration))
  (down [this]
    (println "Down" (migration-name migration))
    (proto/down migration)))

(defrecord CliStore [store]
  proto/Store
  (completed-ids [this]
    (proto/completed-ids store))
  (migrations [this]
    (let [migrations (proto/migrations store)]
      (if (seq migrations)
        (map #(CliMigration. %) migrations)
        (do (println "No migrations found")
            migrations))))
  (begin [this]
    (println "Beginning migrations")
    (proto/begin store))
  (end [this]
    (let [r (proto/end store)]
      (println "Migrations complete")
      r)))

(defmethod proto/make-store :cli
  [{:keys [real-store] :as config}]
  (require-plugin (assoc config :store real-store))
  (CliStore. (proto/make-store (assoc config :store real-store))))
