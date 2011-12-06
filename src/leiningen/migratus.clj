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
(ns leiningen.migratus
  (:require [migratus.core :as migratus]
            [migratus.cli]))

(defn- migrate [config]
  (migratus/migrate config))

(defn- up [config ids]
  (apply migratus/up config ids))

(defn- down [config ids]
  (apply migratus/down config ids))

(defn migratus
  "MIGRATE ALL THE THINGS!

Run migrations against a store.  The :migratus key in project.clj is passed to
migratus as configuration.

Usage `lein migratus [command & ids]`.  Where 'command' is:

migrate  Bring up any migrations that are not completed.
up       Bring up the migrations specified by their ids.  Skips any migrations
         that are already completed.
down     Bring down the migrations specified by their ids.  Skips any migrations
         that are not already completed."
  [project command & ids]
  (if-let [config (:migratus project)]
    (let [config (assoc config :backend :cli :real-backend (:backend config))]
      (case command
        "migrate" (if (empty? ids)
                    (migrate config)
                    (println "Unexpected arguments to 'migrate'"))
        "up" (up config (map #(Long/parseLong %) ids))
        "down" (down config (map #(Long/parseLong %) ids))))
    (println "Missing :migratus config in project.clj")))
