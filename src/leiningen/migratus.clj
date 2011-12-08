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
  (:require [migratus.core :as core]
            [migratus.cli]))

(defn migratus
  "MIGRATE ALL THE THINGS!

Run migrations against a store.  The :migratus key in project.clj is passed to
migratus as configuration.

Usage `lein migratus [command & ids]`.  Where 'command' is:

migrate  Bring up any migrations that are not completed.
up       Bring up the migrations specified by their ids.  Skips any migrations
         that are already up.
down     Bring down the migrations specified by their ids.  Skips any migrations
         that are already down.

If you run `lein migrate` without specifying a command, then the 'migrate'
command will be executed."
  [project & [command & ids]]
  (if-let [config (:migratus project)]
    (let [config (assoc config :store :cli :real-store (:store config))]
      (case command
        "up" (apply core/up config (map #(Long/parseLong %) ids))
        "down" (apply core/down config (map #(Long/parseLong %) ids))
        (if (and (or (= command "migrate") (nil? command)) (empty? ids))
          (core/migrate config)
          (println "Unexpected arguments to 'migrate'"))))
    (println "Missing :migratus config in project.clj")))
