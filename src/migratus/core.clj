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
            [migratus.protocols :as proto]))

(def ^{:dynamic true} *quiet* true)

(defn info [& msg]
  (when-not *quiet*
    (apply println msg)))

(defn up* [migration]
  (info "--" (str (:version migration) "-" (:name migration) "..."))
  (proto/up migration))

(defn migrate* [migrations]
  (if (seq migrations)
    (let [migrations (sort-by :version migrations)]
      (do (info "== Running 'up' for the following migrations"
                (apply str (interpose " " (map :version migrations))))
          (doseq [migration migrations]
            (up* migration))
          (info "== Migrations complete\n")))
    (info "== No migrations need to be run\n")))

(defn uncompleted-migrations [store]
  (let [completed? (set (proto/completed-versions store))]
    (remove (comp completed? proto/version) (proto/migrations store))))

(defn migrate [config]
  (let [store (proto/make-store config)]
    (migrate* (uncompleted-migrations store))))

(defn up [config & versions]
  (let [store (proto/make-store config)
        completed (set (proto/completed-versions store))
        versions (set/difference (set versions) completed)
        migrations (filter (comp versions proto/version)
                           (proto/migrations store))]
    (migrate* migrations)))

(defn down* [migration]
  (info "--" (str (:version migration) "-" (:name migration) "..."))
  (proto/down migration))

(defn down [config & versions]
  (let [store (proto/make-store config)
        completed (set (proto/completed-versions store))
        versions (set/intersection (set versions) completed)
        migrations (filter (comp versions proto/version)
                           (proto/migrations store))
        migrations (reverse (sort-by :version migrations))]
    (if (seq migrations)
      (do (info "== Running 'down' for the following migrations"
                (apply str (interpose " " (map :version migrations))))
          (doseq [migration migrations]
            (down* migration))
          (info "== Migrations complete"))
      (info "== No migrations need to be run"))))
