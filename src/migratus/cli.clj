(ns migratus.cli
  (:require [clojure.core :as core]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure.tools.logging :as log]
            [migratus.core :as migratus])
  (:import [java.time ZoneOffset]
           [java.util.logging
            ConsoleHandler
            Formatter
            LogRecord
            Logger
            SimpleFormatter]))

(def global-cli-options
  [[nil "--config NAME" "Configuration file name"]
   ["-v" nil "Verbosity level; may be specified multiple times to increase value"
    :id :verbosity
    :default 0
    :update-fn inc]
   ["-h" "--help"]])

(def migrate-cli-options
  [[nil "--until-just-before MIGRATION-ID" "Run all migrations preceding migration-id. This is useful when testing that a migration behaves as expected on fixture data. This only considers uncompleted migrations, and will not migrate down."]
   ["-h" "--help"]])

(def rollback-cli-options
  [[nil "--until-just-after MIGRATION-ID" "Migrate down all migrations after migration-id. This only considers completed migrations, and will not migrate up."]
   ["-h" "--help"]])

(def list-cli-options
  [[nil "--available" "List all migrations, applyed and non applyed"]
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
  (log/info "The following errors occurred while parsing your command:\n\n"
            (str/join  \newline errors)))

(defn no-match-message
  "No matching clause message info"
  [arguments summary]
  (log/info "Migratus API does not support this action(s) : " arguments "\n\n"
            (str/join (usage summary))))

(defn run-migrate [cfg [_ & args]]
  (let [{:keys [options arguments errors summary]} (parse-opts args migrate-cli-options :in-order true)]

    (cond
      errors (error-msg errors)
      (:until-just-before options)
      (do (log/info "configuration is: \n" cfg "\n"
                    "arguments:" (rest arguments))
          (migratus/migrate-until-just-before cfg (rest arguments)))
      (empty? args)
      (do (log/info "calling (migrate cfg) \n configuration is: \n" cfg)
          (migratus/migrate cfg))
      :else (no-match-message args summary))))

(defn run-rollback [cfg [_ & args]]
  (let [{:keys [options arguments errors summary]} (parse-opts args rollback-cli-options :in-order true)]
    (cond

      errors (error-msg errors)

      (:until-just-after options)
      (do (log/info "configuration is: \n" cfg "\n"
                    "args:" (rest arguments))
          (migratus/rollback-until-just-after cfg (rest arguments)))

      (empty? args)
      (do (log/info "configuration is: \n" cfg)
          (migratus/rollback cfg))

      :else (no-match-message args summary))))

(defn run-list [cfg [_ & args]]
  (let [{:keys [options _arguments errors summary]} (parse-opts args list-cli-options :in-order true)]
    (cond

      errors (error-msg errors)
      (:applyed options) (log/info "listing applyed migrations")
      (:pending options) (do (log/info "listing pending migrations, configuration is: \n" cfg)
                             (migratus/pending-list cfg))
      (:available options) (log/info "listing available migrations")
      (empty? args) (do (log/info "calling (pending-list cfg) with config: \n" cfg)
                        (migratus/pending-list cfg))
      :else (no-match-message args summary))))

(defn simple-formatter
  "Clojure bridge for java.util.logging.SimpleFormatter.
   Can register a clojure fn as a logger formatter.

   * format-fn - clojure fn that receives the record to send to logging."
  (^SimpleFormatter [format-fn]
   (proxy [SimpleFormatter] []
     (format [record]
       (format-fn record)))))

(defn format-log-record
  "Format jul logger record."
  (^String [^LogRecord record]
   (let [fmt "%5$s"
         instant (.getInstant record)
         date (-> instant (.atZone ZoneOffset/UTC))
         level (.getLevel record)
         src (.getSourceClassName record)
         msg (.getMessage record)
         thr (.getThrown record)
         logger (.getLoggerName record)]
     (core/format fmt date src logger level msg thr))))

(defn verbose-log-level [v]
  (case v
    0  java.util.logging.Level/INFO  ;; :info
    1  java.util.logging.Level/FINE  ;; :debug
    java.util.logging.Level/FINEST)) ;; :trace

(defn set-logger-format
  "Configure JUL logger to use a custom log formatter.

   * formatter - instance of java.util.logging.Formatter"
  ([verbosity]
   (set-logger-format verbosity (simple-formatter format-log-record)))
  ([verbosity ^Formatter formatter]
   (let [main-logger (doto (Logger/getLogger "")
                       (.setUseParentHandlers false)
                       (.setLevel (verbose-log-level verbosity)))
         handler (doto (ConsoleHandler.)
                   (.setFormatter formatter)
                   (.setLevel (verbose-log-level verbosity)))
         handlers (.getHandlers main-logger)]
     (doseq [h handlers]
       (.removeHandler main-logger h))
     (.addHandler main-logger handler))))

(defn load-config!
  "Returns the content of config file as a clojure map datastructure"
  [^String config]
  (let [config-path (.getAbsolutePath (io/file config))]
    (try
      (read-string (slurp config-path))
      (catch java.io.FileNotFoundException e
        (log/info "Missing config file" (.getMessage e)
                  "\nYou can use --config path_to_file to specify a path to config file")))))

(defn -main [& args]
  (let [{:keys [options arguments _errors summary]} (parse-opts args global-cli-options :in-order true)
        config (:config options)
        verbosity (:verbosity options)
        cfg (load-config! config)
        action (first arguments)]
    (set-logger-format verbosity)
    (cond
      (:help options) (usage summary)
      (nil? (:config options)) (error-msg "No config provided \n --config [file-name]>")
      :else (case action
              "init" (migratus/init cfg)
              "create" (migratus/create cfg (second arguments))
              "migrate" (run-migrate cfg arguments)
              "rollback" (run-rollback cfg arguments)
              "reset" (migratus/reset cfg)
              "up" (migratus/up cfg (rest arguments))
              "down" (migratus/down cfg (rest arguments))
              "list" (run-list cfg arguments)
              (no-match-message arguments summary)))))


