(ns migratus-cli.core
  (:require [clojure.tools.cli :refer [parse-opts]]
            [next.jdbc :as jdbc]
            [migratus.core :as migratus :refer :all]
            [clojure.string :as str]))

(def cli-options
  [[nil "--config" "Configuration file path" :default "migratus.edn"]
   [nil "--until-just-after" "Migrate down all migrations after migration-id. This only considers completed migrations, and will not migrate up."]
   [nil "--until-just-before" "Run all migrations preceding migration-id. This is useful when testing that a migration behaves as expected on fixture data. This only considers uncompleted migrations, and will not migrate down."]
   ["-h" "--help"]])

(defn usage [options-summary]
  (->> ["Usage: migratus action [options]"
        ""
        "Actions:"
        "  init"
        "  create"
        "  migrate"
        "  reset"
        "  rollback"
        "  up"
        "  down"
        "  list"
        ""
        "options:"
        options-summary]
       (str/join \newline)))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (str/join  \newline errors)))

(defn no-match-message
  "No matching clause message info"
  [arguments summary]
  (println "Migratus API does not support this action(s) : " arguments "\n\n" 
       (str/join (usage summary))))

(defn -main [& args]
  (let [{:keys [options arguments _errors summary]} (parse-opts args cli-options :in-order true)
        cfg (:config options)
        action (first arguments)]
    (case action
      "init" (init cfg)
      "create" (create cfg (second arguments))
      "migrate" (if (some? (some #(= "--until-just-before" %) arguments))
                  (migrate-until-just-before cfg (last arguments))
                  (migrate cfg))
      "rollback" (if (some? (some #(= "--until-just-after" %) arguments))
                   (rollback-until-just-after cfg (last arguments))
                   (rollback cfg))
      "reset" (reset cfg)
      "up" (up cfg (rest arguments))
      "down" (down cfg (rest arguments))
      "list" (case (second arguments)
               "--available" (println "CALLED: migratus list --available ");; TODO: add functions
               "--applyed" (println "CALLED: migratus list --applyed ")
               "--pending" (println "CALLED: migratus list --pending")
               (println "Default: (pending-list cfg)"))
      
      (no-match-message arguments options))))

(comment
  (def datasource-config {:dbtype "h2:mem"
                          :dbname "users"})
  (true? 3)
  (def ds (jdbc/get-datasource datasource-config))
  (def config {:store                :database
               :migration-dir        "migrations/"
               :db {:datasource ds}})
  (some #(= "--g" %) ["--g"])
  (migratus/create config "example2")
  (migratus/migrate config)
  (migratus/down config 20230619102313)
  (jdbc/execute! ds ["select * from users"])
  (jdbc/execute! ds ["select * from schema_migrations"])
  (usage cli-options)
  0
  )



