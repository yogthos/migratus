(ns migratus.cli
  (:require [clojure.core :as core]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure.tools.logging :as log]
            [migratus.core :as migratus]
            [cheshire.core :as cheshire])
  (:import [java.time ZoneOffset]
           [java.util.logging
            ConsoleHandler
            Formatter
            LogRecord
            Logger
            SimpleFormatter]))

(def global-cli-options
  [[nil "--config NAME" "Configuration file name" :default "migratus.edn"]
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
  [[nil "--available" "List all migrations, applied and non applied"]
   [nil "--pending" "List pending migrations"]
   [nil "--applied" "List applied migrations"]
   [nil "--format FILE-FORMAT" "Option to write to file format as json edn"
    :validate [#(boolean (some (set (list %)) #{"edn" "json"})) "Unsupported format. Valid options: edn, json."]]
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

(defn run-migrate [cfg args]
  (let [{:keys [options arguments errors summary]} (parse-opts args migrate-cli-options :in-order true)
        rest-args (rest arguments)]

    (cond
      errors (error-msg errors)
      (:until-just-before options)
      (do (log/info "configuration is: \n" cfg "\n"
                    "arguments:" rest-args)
          (migratus/migrate-until-just-before cfg rest-args))
      (empty? args)
      (do (log/info "calling (migrate cfg) \n configuration is: \n" cfg)
          (migratus/migrate cfg))
      :else (no-match-message args summary))))

(defn run-rollback [cfg args]
  (let [{:keys [options arguments errors summary]} (parse-opts args rollback-cli-options :in-order true)
        rest-args (rest arguments)]

    (cond
      errors (error-msg errors)

      (:until-just-after options)
      (do (log/info "configuration is: \n" cfg "\n"
                    "args:" rest-args)
          (migratus/rollback-until-just-after cfg rest-args))

      (empty? args)
      (do (log/info "configuration is: \n" cfg)
          (migratus/rollback cfg))

      :else (no-match-message args summary))))

(defn simplified-mig-data [mig]
  (let [{:keys [id name applied]} mig
        applied-fmt (if (nil? applied) "%s" "%1$tY-%1$tm-%1$td %1$tT")
        dt-format (core/format applied-fmt, applied)]
    {:id id :name name :applied dt-format}))

(defn write-migrations-edn! [data]
  (log/info "Writing to file list-migrations.edn")
  (->> data
       (map simplified-mig-data)
       (interpose \newline)
       (apply str)
       (spit "list-migrations.edn")))

(defn write-migrations-json! [data]
  (log/info (str "Writing to file list-migrations.json"))
  (spit "list-migrations.json" (cheshire/generate-string data {:pretty true})))

(defn write-migrations! [data ff]
  (case ff
    "edn" (write-migrations-edn! data)
    "json" (write-migrations-json! data)
    nil))

(defn c-width
  "Set column width for CLI table"
  [n]
  (apply str (repeat n "-")))

(defn all-mig-print-fmt [data]
  (log/info (core/format "%-16s %-22s %-20s", 
                         "| MIGRATION-ID" "| NAME" "| APPLIED"))
  (log/info (core/format "%-16s %-22s %-20s", 
                         (c-width 15) (c-width 21) (c-width 20)))
  (doall
   (map
    (fn [e]
      (let [{:keys [id name applied]} e
            applied? (if (nil? applied)
                       "pending"
                       applied)
            fmt-applied (if (nil? applied) "| %3$-20s" "| %3$tY-%3$tm-%3$td %3$-9tT")
            fmt-str (str "| %1$-15s | %2$-22s" fmt-applied)]
        (log/info (core/format fmt-str, id, name, applied?)))) data)))

(defn applied-mig-print-fmt [data]
  (log/info (core/format "%-16s %-22s %-20s",
                         "| MIGRATION-ID" "| NAME" "| APPLIED"))
  (log/info (core/format "%-16s %-22s %-20s",
                         (c-width 15) (c-width 21) (c-width 20)))
  (doall
   (map
    (fn [e]
      (let [{:keys [id name applied]} e
            fmt-str "| %1$-15s | %2$-22s | %3$tY-%3$tm-%3$td %3$-9tT"]
        (log/info (core/format fmt-str, id, name, applied)))) data)))

(defn pending-mig-print-fmt [data]
  (log/info (core/format "%-16s %-22s",
                         "| MIGRATION-ID" "| NAME"))
  (log/info (core/format "%-16s %-22s", 
                         (c-width 15) (c-width 21)))
  (doall
   (map
    (fn [e]
      (let [{:keys [id name]} e
            fmt-str "| %1$-15s | %2$-22s"]
        (log/info (core/format fmt-str, id, name)))) data)))



(defn run-list [cfg args]
  (let [{:keys [options _arguments errors summary]} (parse-opts args list-cli-options :in-order true)
        available-migs (migratus/all-migrations cfg)
        pending-migs (filter (fn [mig] (= nil (:applied mig))) (migratus/all-migrations cfg))
        applied-migs (filter (fn [mig] (not= nil (:applied mig))) (migratus/all-migrations cfg))
        ff (:format options)]
    (cond
      errors (error-msg errors)
      (:applied options) (do (log/info "Listing applied migrations:")
                             (when ff (write-migrations! applied-migs ff))
                             (applied-mig-print-fmt applied-migs))
      (:pending options) (do (log/info "Listing pending migrations:")
                             (when ff (write-migrations! pending-migs ff))
                             (pending-mig-print-fmt pending-migs))
      (:available options) (do (log/info "Listing available migrations")
                               (when ff (write-migrations! available-migs ff))
                               (all-mig-print-fmt available-migs))
      (or (empty? args) (:format options)) (do (log/info "Listing pending migrations:")
                        (when ff (write-migrations! pending-migs ff))
                        (pending-mig-print-fmt pending-migs))
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

(defn up [cfg args]
  (if (empty? args)
    (log/info "To run action up you must provide a migration-id as a parameter:
                   up <migration-id>")
    (->> args
         (map #(parse-long %))
         (apply migratus/up cfg))))

(defn down [cfg args]
  (if (empty? args)
    (log/info "To run action down you must provide a migration-id as a parameter:
                   down <migration-id>")
    (->> args
         (map #(parse-long %))
         (apply migratus/down cfg))))



(defn -main [& args]
  (let [{:keys [options arguments _errors summary]} (parse-opts args global-cli-options :in-order true)
        config (:config options)
        verbosity (:verbosity options)
        cfg (load-config! config)
        action (first arguments)
        rest-args (rest arguments)]
    (set-logger-format verbosity)
    (cond
      (:help options) (usage summary)
      (nil? (:config options)) (error-msg "No config provided \n --config [file-name]>")
      :else (case action
              "init" (migratus/init cfg)
              "create" (migratus/create cfg (second arguments))
              "migrate" (run-migrate cfg rest-args)
              "rollback" (run-rollback cfg rest-args)
              "reset" (migratus/reset cfg)
              "up" (up cfg rest-args)
              "down" (down cfg rest-args)
              "list" (run-list cfg rest-args)
              (no-match-message arguments summary)))))
