(ns migratus-cli.core
  (:require [clojure.tools.cli :refer [parse-opts]]
            [next.jdbc :as jdbc]
            [migratus.core :as migratus :refer :all]
            [clojure.string :as str]))

(def cli-options
  [[nil "--config" "Configuration file path" :default "migratus.edn"]
   [nil "--until-just-before MIGRATION-ID" "Run all migrations preceding migration-id. This is useful when testing that a migration behaves as expected on fixture data. This only considers uncompleted migrations, and will not migrate down."]
   [nil "--until-just-after MIGRATION-ID" "Migrate down all migrations after migration-id. This only considers completed migrations, and will not migrate up."]
   [nil "--available" "List all migrations, applyed and non applyed"]
   [nil "--pending" "List pending migrations"]
   [nil "--applyed" "List applyed migrations"]
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

(defn error-msg [errors action]
  (println "The following errors occurred while parsing your command:\n\n" action "\n\n"
       (str/join  \newline errors)))

(defn no-match-message
  "No matching clause message info"
  [arguments summary]
  (println "Migratus API does not support this action(s) : " arguments "\n\n" 
       (str/join (usage summary))))

(defn subcommand-processor
  "Takes the rest of the args and process them through parse-opts"
  [args action]
  (let [arguments (rest args)
        {:keys [options errors summary]} (parse-opts arguments cli-options :in-order true)]
    (case action
      "migrate" (cond
                  errors (error-msg errors action)
                  (:until-just-before options) (println "(migrate-until-just-before cfg (rest args))")
                  (empty? arguments) (println "called (migrate cfg)")
                  :else (no-match-message arguments summary))
      "rollback" (cond
                   errors (error-msg errors action)
                   (:until-just-after options) (println "(rollback-until-just-after cfg (rest args))")
                   (empty? arguments) (println "called (rollback cfg)")
                   :else (no-match-message arguments summary))
      "list" (cond
               errors (error-msg errors action)
               (:applyed options) (println "listing applyed migrations")
               (:pending options) (println "listing pending migrations")
               (:available options) (println "listing available migrations")
               (empty? arguments) (println "default: called (list cfg) - list available migrations")
               :else (no-match-message arguments summary))
      (error-msg errors action))))

(defn -main [& args]
  (let [{:keys [options arguments _errors summary]} (parse-opts args cli-options :in-order true)
        cfg (:config options)
        action (first arguments)]
    (case action
      "init" (init cfg)
      "create" (create cfg (second arguments))
      "migrate" (subcommand-processor arguments "migrate")
      "rollback" (subcommand-processor arguments "rollback")
      "reset" (reset cfg)
      "up" (up cfg (rest arguments))
      "down" (down cfg (rest arguments))
      "list" (subcommand-processor arguments "list")
      (no-match-message arguments summary))))


(comment
  (def datasource-config {:dbtype "h2:mem"
                          :dbname "users"})
  (true? 3)
  (rest '(1 2 3 4))
  (empty? '())
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
  (+ 2 2)
  (-> '() (conj "Clojure" "Lisp") pop (conj "Java" "JavaScript") count)
  0
  )



