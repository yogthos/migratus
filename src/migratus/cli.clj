(ns migratus.cli
  (:require [clojure.tools.cli :refer [parse-opts]]
            [migratus.core :as migratus]
            [clojure.string :as str]
            [clojure.java.io :as io]))

(def cli-options
  [[nil "--config" "Configuration file name"]
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

(defn error-msg [errors]
  (println "The following errors occurred while parsing your command:\n\n"
           (str/join  \newline errors)))

(defn no-match-message
  "No matching clause message info"
  [arguments summary]
  (println "Migratus API does not support this action(s) : " arguments "\n\n"
           (str/join (usage summary))))

(defn run-migrate [cfg args]
  (let [rargs (rest args)
        {:keys [options arguments errors summary]} (parse-opts rargs cli-options :in-order true)]
    (cond
      errors (error-msg errors)
      (:until-just-before options) (do (println "calling (migrate-until-just-before cfg (rest arguments))\n
                                             configuration is: \n" cfg "\n"
                                                "arguments:" (rest arguments))
                                       (migratus/migrate-until-just-before cfg (rest arguments)))
      (empty? rargs) (do (println "calling (migrate cfg) \n configuration is: \n" cfg)
                         (migratus/migrate cfg))
      :else (no-match-message rargs summary))))

(defn run-rollback [cfg args]
  (let [rargs (rest args)
        {:keys [options arguments errors summary]} (parse-opts rargs cli-options :in-order true)]
    (cond
      errors (error-msg errors)
      (:until-just-after options) (do (println "calling (rollback-until-just-after cfg (rest arguments)\n
                                              configuration is: \n" cfg "\n"
                                               "args:" (rest arguments))
                                      (migratus/rollback-until-just-after cfg (rest arguments)))
      (empty? rargs) (do (println "calling (rollback cfg) \n configuration is: \n" cfg)
                         (migratus/rollback cfg))
      :else (no-match-message rargs summary))))

(defn run-list [cfg args]
  (let [rargs (rest args)
        {:keys [options _arguments errors summary]} (parse-opts rargs cli-options :in-order true)]
    (cond
      errors (error-msg errors)
      (:applyed options) (println "listing applyed migrations")
      (:pending options) (do (println "listing pending migrations, configuration is: \n" cfg)
                             (migratus/pending-list cfg))
      (:available options) (println "listing available migrations")
      (empty? rargs) (do (println "calling (pending-list cfg) with config: \n" cfg)
                         (migratus/pending-list cfg))
      :else (no-match-message rargs summary))))

(defn -main [& args]
  (let [{:keys [options arguments _errors summary]} (parse-opts args cli-options :in-order true)
        config (:config options)
        config-path (.getAbsolutePath (io/file config))
        cfg (read-string (slurp config-path))
        action (first arguments)]
    (println "config path: " config-path)
    (println "loaded config: " cfg)
    (case action
      "init" (migratus/init cfg)
      "create" (migratus/create cfg (second arguments))
      "migrate" (run-migrate cfg arguments)
      "rollback" (run-rollback cfg arguments)
      "reset" (migratus/reset cfg)
      "up" (migratus/up cfg (rest arguments))
      "down" (migratus/down cfg (rest arguments))
      "list" (run-list cfg arguments)
      (no-match-message arguments summary))))



