(ns migratus.cli
  (:require [clojure.core :as core]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure.tools.logging :as log]
            [migratus.core :as migratus]
            [migratus.migrations :as mig]
            [migratus.protocols :as proto])
  (:import [java.io IOException]
           [java.time ZoneId ZoneOffset]
           [java.time.format DateTimeFormatter]
           [java.util.logging
            ConsoleHandler
            Formatter
            LogRecord
            Logger
            SimpleFormatter]))

(defn my-parse-long
  "parse-long version for Clojure 1.10 compatibility"
  [s]
  (Long/valueOf s))

(defn my-parse-boolean
  [s]
  (Boolean/parseBoolean s))

(def app-config
  "Application options to be used for output/logging.
   To avoid passing them around everywhere."
  (atom {:verbosity 0
         :output-format "plain"}))

(defn deep-merge
  "From https://clojuredocs.org/clojure.core/merge ."
  [a & maps]
  (if (map? a)
    (apply merge-with deep-merge a maps)
    (apply merge-with deep-merge maps)))

(defn println-err
  [& more]
  (binding [*out* *err*]
    (apply println more)))

(defn env->config!
  "Try to load migratus configuration options from environment.
   We produce a configuration that can be merged with other configs.
   Missing values are ok as configuring via env is optional.

   Looks and processes the following env vars:

   - MIGRATUS_CONFIG - read string as edn and return config.
     Do not process any other migratus env var.

   - MIGRATUS_STORE - apply clojure.core/keyword fn to value
   - MIGRATUS_MIGRATION_DIR - expect string, use as is
   - MIGRATUS_DB_SPEC - read string as edn
   - MIGRATUS_TABLE_NAME - string
   - MIGRATUS_INIT_IN_TRANSACTION - parse boolean

   Return: A map representing the migratus configuration."
  ([]
   (env->config! (System/getenv)))
  ([env]
   (let [config (get env "MIGRATUS_CONFIG")]
     (if config
       (edn/read-string config)
       ;; we don't have MIGRATUS_CONFIG - check the other vars
       (let [store (get env "MIGRATUS_STORE")
             migration-dir (get env "MIGRATUS_MIGRATION_DIR")
             table (get env "MIGRATUS_TABLE_NAME")
             init-in-transaction (get env "MIGRATUS_INIT_IN_TRANSACTION")
             db (get env "MIGRATUS_DB_SPEC")]
         (cond-> {}
           store (assoc :store (keyword store))
           migration-dir (assoc :migration-dir migration-dir)
           table (assoc :migration-table-name table)
           init-in-transaction (assoc :init-in-transaction
                                      (my-parse-boolean init-in-transaction))
           db (assoc :db (edn/read-string db))))))))

(defn cli-args->config
  "Parse any migratus configuration options from cli args.

   Return a migratus configuration map with any values.

   We expect the args we receive to be values
   processed by tools.cli parse-opts fn."
  [config-edn-str]
  (let [config (edn/read-string config-edn-str)]
    (if (map? config)
      config
      {})))

(defn file->config!
  "Read config-file as a edn.
   If config-file is nil, return nil.
   On IO exception print warning and return nil."
  [^String config-file]
  (when config-file
    (let [config-path (.getAbsolutePath (io/file config-file))]
      (try
        (edn/read-string (slurp config-path))
        (catch IOException e
          (println-err
           "WARN: Error reading config" (.getMessage e)
           "\nYou can use --config path_to_file to specify a path to config file"))))))

(defn load-config!
  "Load configuration and merge options.

   Options are loaded in this order.
   Sbsequent values are deepmerged and replace previous ones.

   - configuration file - if it exists and we can parse it as edn
   - environment variables
   - command line arguments passed to the application

   Return a migratus configuration map."
  [config-file config-data]
  (let [config (file->config! config-file)
        env (env->config!)
        args (cli-args->config config-data)]
    (deep-merge config env args)))

(defn config-check-store
  [store]
  (if store
    true
    "Missing :store key in configuration"))
(defn config-check-db-spec
  [db]
  (if (map? db)
    ()
    "Value for :db key should be a map"))

(defn- validate-db-config
  [db]
  (cond-> []
    (nil? db) (concat ["Missing :db option for :database store"])
    ;;
    (not (map? db))
    (concat ["Value of :db should be a map"])))

(comment

  (validate-db-config nil)
  ;; => ("Missing :db option for :database store" "Value of :db should be a map")


  (validate-db-config "")
  ;; => ["Value of :db should be a map"]

  (validate-db-config {})
  ;; => []
  )


(defn valid-config?
  "Validate a migratus configuration for required options.
   If valid, return true.
   If invalid return map with reasons why validation failed.

   We expect most people will use the database store so we have extra checks."
  [config]
  (if (map? config)
    (let [valid true
          store (:store config)
          db (:db config)
          errors (cond-> []
                   ;; some store checks
                   (nil? store)
                   (concat ["Missing :store option"])
                   ;;
                   (not (keyword? store))
                   (concat ["Value of :store should be a keyword"])
                   ;;
                   (= :database store)
                   (concat (validate-db-config db)))]
      (if (pos? (count errors))
        {:errors errors}
        valid))
    {:errors ["Config is nil or not a map"]}))

(comment

  (valid-config? nil)
  ;; => {:errors ["Config is nil or not a map"]}

  (valid-config? {})
  ;; => {:errors ("Missing :store option" "Value of :store should be a keyword")}

  (valid-config? {:store :database})
  ;; => {:errors ("Missing :db option for :database store" "Value of :db should be a map")}

  (valid-config? {:store :database
                  :db {}})
  ;; => true


  )

(defn- do-invalid-config-and-die
  "We got invalid configuration.
   Print error and exit"
  [valid? cfg]
  (println-err "Invalid configuration:" (:errors valid?)
               "\nMigratus can load configuration from: file, env vars, cli args."
               "\nSee documentation and/or use --help")
  (println-err "Configuration is" cfg))

(defn validate-format [s]
  (boolean (some (set (list s)) #{"plain" "edn" "json"})))

(def global-cli-options
  [[nil "--config-file NAME" "Configuration file name"]
   ["-v" nil
    "Verbosity level; may be specified multiple times to increase value"
    :id :verbosity
    :default 0
    :update-fn inc]
   [nil "--output-format FORMAT"
    "Option to print in plain text (default), edn or json"
    :default "plain"
    :validate [#(validate-format %)
               "Unsupported format. Valid options: plain (default), edn, json."]]
   [nil "--config CONFIG" "Configuration as edn"]
   ["-h" "--help"]])

(def migrate-cli-options
  [[nil "--until-just-before MIGRATION-ID"
    "Run all migrations preceding migration-id.
     This is useful when testing that a migration behaves as expected on fixture data.
     This only considers uncompleted migrations, and will not migrate down."]
   ["-h" "--help"]])

(def rollback-cli-options
  [[nil "--until-just-after MIGRATION-ID"
    "Migrate down all migrations after migration-id.
     This only considers completed migrations, and will not migrate up."]
   ["-h" "--help"]])

(def list-cli-options
  [[nil "--available" "List all migrations, applied and non applied"]
   [nil "--pending" "List pending migrations"]
   [nil "--applied" "List applied migrations"]
   ["-h" "--help"]])

(defn usage [options-summary]
  (->> ["Usage: migratus action [options]"
        ""
        "Actions:"
        "  init"
        "  status"
        "  create"
        "  migrate"
        "  reset"
        "  rollback"
        "  up"
        "  down"
        "  list"
        ""
        "global options:"
        options-summary]
       (str/join \newline)))

(defn error-msg [errors]
  (println-err
   "The following errors occurred while parsing your command:\n\n"
   (str/join  \newline errors)))

(defn no-match-message
  "No matching clause message info"
  [arguments summary]
  (println-err
   "Migratus API does not support this action(s) : " arguments "\n\n"
   (str/join (usage summary))))

(defn run-migrate! [cfg args]
  (let [cmd-opts (parse-opts args migrate-cli-options :in-order true)
        {:keys [options arguments errors summary]} cmd-opts
        rest-args (rest arguments)]

    (cond
      errors (error-msg errors)
      (:until-just-before options)
      (do (log/debug "configuration is: \n" cfg "\n"
                     "arguments:" rest-args)
          (migratus/migrate-until-just-before cfg rest-args))
      (empty? args)
      (do (log/debug "calling (migrate cfg)" cfg)
          (migratus/migrate cfg))
      :else (no-match-message args summary))))

(defn run-rollback! [cfg args]
  (let [cmd-opts (parse-opts args rollback-cli-options :in-order true)
        {:keys [options arguments errors summary]} cmd-opts
        rest-args (rest arguments)]

    (cond
      errors (error-msg errors)

      (:until-just-after options)
      (do (log/debug "configuration is: \n" cfg "\n"
                     "args:" rest-args)
          (migratus/rollback-until-just-after cfg rest-args))

      (empty? args)
      (do (log/debug "configuration is: \n" cfg)
          (migratus/rollback cfg))

      :else (no-match-message args summary))))

(defn util-date-to-local-datetime [util-date]
  (when (some? util-date)
    (let [instant (.toInstant util-date)
          zone-id (ZoneId/systemDefault)
          local-datetime (.atZone instant zone-id)]
      local-datetime)))

(defn parse-migration-applied-date [m]
  (let [{:keys [id name applied]} m
        local-date (when (some? applied)
                     (->
                      (util-date-to-local-datetime applied)
                      (.format DateTimeFormatter/ISO_LOCAL_DATE_TIME)))]
    {:id id :name name :applied local-date}))

(defn parsed-migrations-data [cfg]
  (let [all-migrations (migratus/all-migrations cfg)]
    (map parse-migration-applied-date all-migrations)))

(defn pending-migrations [cfg]
  (let [keep-pending-migs (fn [mig] (nil? (:applied mig)))]
    (filter keep-pending-migs (parsed-migrations-data cfg))))

(defn applied-migrations [cfg]
  (let [keep-applied-migs (fn [mig] (not= nil (:applied mig)))]
    (filter keep-applied-migs (parsed-migrations-data cfg))))

(defn col-width
  "Set column width for CLI table"
  [n]
  (apply str (repeat n "-")))

(defn table-line [n]
  (let [str (str "%-" n "s")]
    (core/format str, (col-width n))))

(defn format-mig-data [m]
  (let [{:keys [id name applied]} m
        applied? (if (nil? applied)
                   "pending"
                   applied)
        fmt-str "%1$-15s | %2$-22s | %3$-20s"]
    (prn (core/format fmt-str, id, name, applied?))))

(defn format-pending-mig-data [m]
  (let [{:keys [id name]} m
        fmt-str "%1$-15s| %2$-22s%3$s"]
    (prn (core/format fmt-str, id, name))))

(defn mig-print-fmt [data & format-opts]
  (let [pending? (:pending format-opts)]
    (if pending?
      (do (prn (table-line 43))
          (prn (core/format "%-15s%-24s",
                            "MIGRATION-ID" "| NAME"))
          (prn (table-line 41))
          (doseq [d data] (format-pending-mig-data d)))
      (do (prn (table-line 67))
          (prn (core/format "%-16s%-25s%-22s",
                            "MIGRATION-ID" "| NAME" "| APPLIED"))
          (prn (table-line 67))
          (doseq [d data] (format-mig-data d))))))

(defn cli-print-migs! [data f & format-opts]
  (case f
    "plain" (mig-print-fmt data format-opts)
    "edn" (prn data)
    "json" (prn (json/write-str data))
    nil))

(defn list-pending-migrations [migs format]
  (cli-print-migs! migs format {:pending true}))

(defn run-list [cfg args]
  (let [{:keys [options errors summary]} (parse-opts args list-cli-options :in-order true)
        {:keys [available pending applied]} options
        {f :format} options]
    (cond
      errors (error-msg errors)
      applied (let [applied-migs (applied-migrations cfg)]
                (cli-print-migs! applied-migs f))
      pending (let [pending-migs (pending-migrations cfg)]
                (list-pending-migrations pending-migs f))
      available (let [available-migs (parsed-migrations-data cfg)]
                  (cli-print-migs! available-migs f))
      (or (empty? args) f) (let [pending-migs (pending-migrations cfg)]
                             (list-pending-migrations pending-migs f))
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

(defn set-logger-format!
  "Configure JUL logger to use a custom log formatter.

   * formatter - instance of java.util.logging.Formatter"
  ([verbosity]
   (set-logger-format! verbosity (simple-formatter format-log-record)))
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

(defn run-up! [cfg args]
  (if (empty? args)
    (println-err
     "To run action up you must provide a migration-id as a parameter:
                   up <migration-id>")
    (->> args
         (map #(my-parse-long %))
         (apply migratus/up cfg))))

(defn run-down! [cfg args]
  (if (empty? args)
    (println-err
     "To run action down you must provide a migration-id as a parameter:
                   down <migration-id>")
    (->> args
         (map #(my-parse-long %))
         (apply migratus/down cfg))))

(defn run-status
  "Display migratus status.
   - display last local migration
   - display database connection string with credentials REDACTED)
   - display last applied migration to database"
  [cfg rest-args]
  (prn "Not yet implemented"))

(defn create-migration
  "Create a new migration with the current date."
  [config & [name type]]
  (when-not name
    (throw (ex-info "Required name for migration" {})))
  (mig/create config name (or type :sql)))

(defn run-create-migration!
  "Run migratus create command"
  [config arguments]
  (try
    (let [name (first arguments)
          file (create-migration config name)]
      (println "Created migration" file))
    (catch Exception e
      (println-err (ex-data e)))))

(defn- run-init!
  [cfg]
  (migratus/init cfg))

(defn do-print-usage
  ([summary]
   (do-print-usage summary nil))
  ([summary errors]
   (println (usage summary))
   (when errors
     (println-err errors))))

(defn- run-reset! [cfg]
  (migratus/reset cfg))

(defn do-store-actions
  [config action action-args]
  ;; make store and connect
  (let [valid? (valid-config? config)]
    (if (map? valid?)
      (do-invalid-config-and-die valid? config)
      ;; normall processing
      (with-open [store (doto (proto/make-store config)
                          (proto/connect))]
        (case action
          "init" (run-init! store)
          "list" (run-list store action-args)
          "status" (run-status store action-args)
          "up" (run-up! store action-args)
          "down" (run-down! store action-args)
          "migrate" (run-migrate! store action-args)
          "reset" (run-reset! store)
          "rollback" (run-rollback! store action-args)
          (throw (IllegalArgumentException. (str "Unknown action " action))))))))

(defn -main [& args]
  (try
    (let [parsed-opts (parse-opts args global-cli-options)
          {:keys [options arguments errors summary]} parsed-opts
          {:keys [config-file config verbosity output-format]} options
          _ (when (<= 2 verbosity)
              (prn "Parsed options:" parsed-opts))
          action (first arguments)
          action (when action
                   (str/lower-case action))
          rest-args (rest arguments)
          loaded-config (load-config! config-file config)
          no-store-action? (contains? #{"create"} action)]
      (swap! app-config assoc
             :verbosity verbosity
             :output-format output-format)
      ;; (prn @app-config)
      (set-logger-format!  verbosity)
      (cond
        ;; display help if user asks
        (:help options) (do-print-usage summary)
        ;; if no action is supplied, throw error
        (nil? action) (do
                        (do-print-usage summary)
                        (println "No action supplied"))
        ;; in case of errors during args processing - show usage
        (some? errors) (do-print-usage summary errors)
        ;; actions that do not require store
        no-store-action? (case action
                           "create" (run-create-migration! loaded-config rest-args))
        :else (do-store-actions loaded-config action rest-args)))
    (catch Exception e
      (println-err "Error: " (ex-message e)))))

