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
  (:require
    [clojure.set :as set]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [migratus.migrations :as mig]
    [migratus.protocols :as proto]
    migratus.database
    [next.jdbc.transaction :as jdbc-tx]))

(defmacro ^{:private true} assert-args
  [& pairs]
  `(do (when-not ~(first pairs)
         (throw (IllegalArgumentException.
                  (str (first ~'&form) " requires " ~(second pairs) " in " ~'*ns* ":" (:line (meta ~'&form))))))
       ~(let [more (nnext pairs)]
          (when more
            (list* `assert-args more)))))

(defmacro with-store
  "bindings => name init
  Evaluates body in a try expression with name bound to the value
  of the init, and (proto/connect name) called before body, and a
  finally clause that calls (proto/disconnect name)."
  ([bindings & body]
   (assert-args
     (vector? bindings) "a vector for its binding"
     (= 2 (count bindings)) "exactly 2 forms in binding vector"
     (symbol? (bindings 0)) "only Symbols in bindings")
   (let [form (bindings 0) init (bindings 1)]
     `(let [~form ~init]
        (try
          (proto/connect ~form)
          ~@body
          (finally
            (proto/disconnect ~form)))))))

(defn run [store ids command]
  (binding [jdbc-tx/*nested-tx* :ignore]
    (try
      (log/info "Starting migrations")
      (proto/connect store)
      (command store ids)
      (catch java.sql.BatchUpdateException e
        (throw (or (.getNextException e) e)))
      (finally
        (log/info "Ending migrations")
        (proto/disconnect store)))))

(defn require-plugin [{:keys [store]}]
  (when-not store
    (throw (Exception. "Store is not configured")))
  (let [plugin (symbol (str "migratus." (name store)))]
    (require plugin)))

(defn completed-migrations [config store]
  (let [completed? (set (proto/completed-ids store))]
    (filter (comp completed? proto/id) (mig/list-migrations config))))

(defn gather-migrations 
  "Returns a list of all migrations from migration dir and db
  with enriched data:
    - date and time when was applied;
    - description;"
  [config store]
  (let [completed-migrations (vec (proto/completed store))
        available-migrations (mig/list-migrations config)
        merged-migrations-data (apply merge completed-migrations available-migrations)
        grouped-migrations-by-id (group-by :id merged-migrations-data)
        unify-mig-values (fn [[_ v]] (apply merge v))]
    (map unify-mig-values grouped-migrations-by-id)))

(defn all-migrations [config]
  (with-store
    [store (proto/make-store config)]
    (->> store
         (gather-migrations config)
         (map (fn [e] {:id (:id e) :name (:name e) :applied (:applied e)})))))

(defn uncompleted-migrations
  "Returns a list of uncompleted migrations.
   Fetch list of applied migrations from db and existing migrations from migrations dir."
  [config store]
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
          (when (Thread/interrupted)
            (log/info "Thread cancellation detected.  Stopping migration.")
            (throw (InterruptedException. "Migration interrupted by thread cancellation.")))
          (case (up* store migration)
            :success (recur more)
            :ignore (do
                      (log/info "Migration reserved by another instance. Ignoring.")
                      :ignore)
            (do
              (log/error "Stopping:" (migration-name migration) "failed to migrate")
              :failure)))))))

(defn- migrate* [config store _]
  (let [migrations (->> store
                        (uncompleted-migrations config)
                        (sort-by proto/id))]
    (migrate-up* store migrations)))

(defn migrate
  "Bring up any migrations that are not completed.
  Returns nil if successful, :ignore if the table is reserved, :failure otherwise.
  Supports thread cancellation."
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
         first
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

(defn select-migrations
  "List pairs of id and name for migrations selected by the selection-fn."
  [config selection-fn]
  (with-store [store (proto/make-store config)]
              (->> store
                   (selection-fn config)
                   (mapv (juxt proto/id proto/name)))))

(defn completed-list
  "List completed migrations"
  [config]
  (let [migrations (select-migrations config completed-migrations)]
    (log/debug (apply str "You have " (count migrations) " completed migrations:\n"
                      (str/join "\n" migrations)))
    (mapv second migrations)))

(defn pending-list
  "List pending migrations"
  [config]
  (let [migrations (select-migrations config uncompleted-migrations)]
    (log/debug (apply str "You have " (count migrations) " pending migrations:\n"
                      (str/join "\n" migrations)))
    (mapv second migrations)))

(defn migrate-until-just-before
  "Run all migrations preceding migration-id. This is useful when testing that a
  migration behaves as expected on fixture data. This only considers uncompleted
  migrations, and will not migrate down."
  [config migration-id]
  (with-store [store (proto/make-store config)]
              (->> (uncompleted-migrations config store)
                   (map proto/id)
                   distinct
                   sort
                   (take-while #(< % migration-id))
                   (apply up config))))

(defn rollback-until-just-after
  "Migrate down all migrations after migration-id. This only considers completed
  migrations, and will not migrate up."
  [config migration-id]
  (with-store [store (proto/make-store config)]
              (->> (completed-migrations config store)
                   (map proto/id)
                   distinct
                   sort
                   reverse
                   (take-while #(> % migration-id))
                   (apply down config))))
