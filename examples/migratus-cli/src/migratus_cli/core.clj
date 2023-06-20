(ns migratus-cli.core
  (:require [clojure.tools.cli :refer [parse-opts]]
            [next.jdbc :as jdbc]
            [migratus.core :as migratus :refer :all]))

(def cli-options
  [["-i" "--init CONFIG" "Initialize the data store"]
   ["-c" "--create CONFIG NAME" "Create a new migration with the current date"]
   ["-m" "--migrate CONFIG" "Bring up any migrations that are not completed. Returns nil if successful, :ignore if the table is reserved, :failure otherwise. Supports thread cancellation."]
   ["-r" "--rollback CONFIG" "Rollback the last migration that was successfully applied."]
   [nil "--reset CONFIG" "Reset the database by down-ing all migrations successfully applied, then up-ing all migratinos."]
   [nil "--rollback-until-just-after CONFIG ID" "Migrate down all migrations after migration-id. This only considers completed migrations, and will not migrate up."]
   ["-u" "--up CONFIG IDS" "Bring up the migrations identified by ids. Any migrations that are already complete will be skipped."]
   ["-d" "--down CONFIG IDS" "Bring down the migrations identified by ids. Any migrations that are not completed will be skipped."]
   [nil "--pending-list CONFIG" "List pending migrations"]
   [nil "--migrate-until-just-before CONFIG ID" "Run all migrations preceding migration-id. This is useful when testing that a migration behaves as expected on fixture data. This only considers uncompleted migrations, and will not migrate down."]
   ["-h" "--help"]])

#_(defn -main
[& args]
   "I don't do a whole lot ... yet."
(let [arguments (parse-opts args cli-options)
      options (:options arguments)
      summary (:summary arguments)]
  (if (:help options)
    (println summary)
    (println arguments))))

(def datasource-config {:dbtype "h2:mem"
                        :dbname "users"})
(def ds (jdbc/get-datasource datasource-config))
(def config {:store                :database
             :migration-dir        "migrations/"
             :db {:datasource ds}})

(defn -main [& args]
  (let [arguments (parse-opts args cli-options)
        options (:options arguments)
        summary (:summary arguments)
        command (first args)]
    (if (:help options)
      (println summary)
      (condp = command
        "-i" (init config)
        "-c" (create config (second args))
        "-m" (migrate config)
        "-r" (rollback config)
        "--reset" (reset config)
        "--rollback-until-just-after" (rollback-until-just-after config (second args))
        "-u" (up config (rest args))
        "-d" (down config (rest args))
        "--pending-list" (println (pending-list config))
        "--migrate-until-just-before" (migrate-until-just-before config (second args))))
    ))


(comment
  (def datasource-config {:dbtype "h2:mem"
                          :dbname "users"})
  (def ds (jdbc/get-datasource datasource-config))
  (def config {:store                :database
               :migration-dir        "migrations/"
               :db {:datasource ds}})
  
  ()


  (migratus/create config "example2")
  (migratus/migrate config)
  (migratus/down config 20230619102313)
  (jdbc/execute! ds ["select * from users"])
  (jdbc/execute! ds ["select * from schema_migrations"])
  0)



