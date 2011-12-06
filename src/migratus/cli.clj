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
  (:require [migratus.protocols :as proto])
  (:use [migratus.core :only [migration-name require-plugin]]))

;; Wrapper implementations to print information during CLI invocations.
(defrecord CliMigration [migration]
  proto/Migration
  (proto/id [this]
    (proto/id migration))
  (proto/name [this]
    (proto/name migration))
  (proto/up [this]
    (println "Up" (migration-name migration))
    (proto/up migration))
  (proto/down [this]
    (println "Down" (migration-name migration))
    (proto/down migration)))

(defrecord CliStore [store]
  proto/Store
  (proto/completed-ids [this]
    (proto/completed-ids store))
  (proto/migrations [this]
    (let [migrations (proto/migrations store)]
      (if (seq migrations)
        (map #(CliMigration. %) migrations)
        (println "No migrations found"))))
  (proto/run [this migration-fn]
    (println "Starting migrations")
    (proto/run store migration-fn)
    (println "Migrations complete")))

(defmethod proto/make-store :cli
  [{:keys [real-store] :as config}]
  (require-plugin real-store)
  (CliStore. (proto/make-store (assoc config :store real-store))))
