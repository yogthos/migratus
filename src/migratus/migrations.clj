(ns migratus.migrations
  "Namespace to handle migrations stored on filesystem, as files."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    migratus.migration.edn
    migratus.migration.sql
    [migratus.properties :as props]
    [migratus.protocols :as proto]
    [migratus.utils :as utils])
  (:import
    [java.io File StringWriter]
    [java.util Date TimeZone]
    [java.util.jar JarEntry JarFile]
    java.text.SimpleDateFormat
    java.util.regex.Pattern))

(defn ->kebab-case
  "Convert a string to kebab case.

   - convert CamelCase to camel-case
   - replace multiple white spaces with a single dash
   - replace underscores with dash
   - converts to lower case"
  [s]
  (-> (reduce
        (fn [s c]
          (if (and
                (not-empty s)
                (Character/isLowerCase (char (last s)))
                (Character/isUpperCase (char c)))
            (str s "-" c)
            (str s c)))
        "" s)
      (str/replace #"[\s]+" "-")
      (.replaceAll "_" "-")
      (.toLowerCase)))

(comment

  (->kebab-case "hello    javaMigrations2")
  ;; => "hello-java-migrations2"
  )

(defn- timestamp
  "Return the current date and time as a string timestamp at UTC."
  []
  (let [fmt (doto (SimpleDateFormat. "yyyyMMddHHmmss ")
              (.setTimeZone (TimeZone/getTimeZone "UTC")))]
    (.format fmt (Date.))))

(defn parse-migration-id
  "Parse migration id as a java.lang.Long."
  [id]
  (try
    (Long/parseLong id)
    (catch Exception e
      (log/error e (str "failed to parse migration id: " id)))))

(def migration-file-pattern #"^(\d+)-([^\.]+)((?:\.[^\.]+)+)$")

(defn valid-extension?
  "Returns true if file-name extension matches one of the file extensions supported
   by all migration protocols/multimethods implemented"
  [file-name]
  (-> (->> (proto/get-all-supported-extensions)
           (interpose "|")
           (apply str)
           (format "^.*?\\.(%s)$"))
      re-pattern
      (re-matches file-name)
      boolean))

(defn parse-name [file-name]
  (when (valid-extension? file-name)
    (let [[id name ext] (next (re-matches migration-file-pattern file-name))
          migration-type (remove empty? (some-> ext (str/split #"\.")))]
      (when (and id name (< 0 (count migration-type) 3))
        [id name migration-type]))))

(defn warn-on-invalid-migration [file-name]
  (log/warn (str "skipping: '" file-name "'")
            "migrations must match pattern:"
            (str migration-file-pattern)))

(defn migration-map
  [[id name exts] content properties]
  (assoc-in {}
            (concat [id name] (map keyword (reverse exts)))
            (if properties
              (props/inject-properties properties content)
              content)))

(defn find-migration-files
  "Looks for all migration files in 'migration-dir' path.
   Excludes from results the migrations that match globs in 'exclude-scripts'

   Parses the file names according to migratus rules.
   Returns a sequence of maps.
   Each map represents a single migration file.

   A migration map has a single key - migration id as string.
   The value is a map with migration name as key and
   a another map as value representing the migration.

   Example of structure:
   { <migration-id>
      { <migration-name>
        {:sql {:up <contents> }    }}}
   "
  ;; ieugen: I wonder why we have to realize the migrations in memory
  ;; The store could be enhanced to support a fetch-migration call to fetch the contents.
  ;; Most of the time we are dealing with metdata.
  ;; We need the migration body only when we apply it.
  [migration-dir exclude-scripts properties]
  (log/debug "Looking for migrations in" migration-dir)
  (let [migration-dir (io/as-file migration-dir)]
    (->> (for [f (filter (fn [^File f] (.isFile f))
                         (file-seq migration-dir))
               :let [file-name (.getName ^File f)]
               :when (not (utils/script-excluded? file-name migration-dir exclude-scripts))]
           (if-let [mig (parse-name file-name)]
             (migration-map mig (slurp f) properties)
             (warn-on-invalid-migration file-name)))
         (remove nil?))))

(comment

  (take 2 (find-migration-files "test/migrations" nil nil))

  )

(defn find-migration-resources
  "Looks for migration files in classpath and java jar archives.
   Returns a sequence of migrations similar to find-migration-files fn."
  [dir jar exclude-scripts properties]
  (log/debug "Looking for migrations in" dir jar)
  (->> (for [entry (enumeration-seq (.entries ^JarFile jar))
             :when (.matches (.getName ^JarEntry entry)
                             (str "^" (Pattern/quote dir) ".+"))
             :let [entry-name       (.replaceAll (.getName ^JarEntry entry) dir "")
                   last-slash-index (str/last-index-of entry-name "/")
                   file-name        (subs entry-name (if-not last-slash-index 0
                                                                              (+ 1 last-slash-index)))]
             :when (not (utils/script-excluded? file-name jar exclude-scripts))]
         (if-let [mig (parse-name file-name)]
           (let [w (StringWriter.)]
             (io/copy (.getInputStream ^JarFile jar entry) w)
             (migration-map mig (.toString w) properties))
           (warn-on-invalid-migration file-name)))
       (remove nil?)))

(defn read-migrations
  "Looks for migrations files accessible and return a sequence.
   Reads the migration contents as string in memory.
   See find-migration-files for a descriptin of the format."
  [dir exclude-scripts properties]
  (when-let [migration-dir (utils/find-migration-dir dir)]
    (if (instance? File migration-dir)
      (find-migration-files migration-dir exclude-scripts properties)
      (find-migration-resources dir migration-dir exclude-scripts properties))))

(defn find-migrations*
  [dir exclude-scripts properties]
  (->> (read-migrations (utils/ensure-trailing-slash dir) exclude-scripts properties)
       (apply utils/deep-merge)))

(defn find-migrations
  [dir exclude-scripts properties]
  (let [dirs (if (string? dir) [dir] dir)
        fm (fn [d] (find-migrations* d exclude-scripts properties))]
    (into {} (map fm) dirs)))

(defn find-or-create-migration-dir
  "Checks the migration directory exists.
   Creates it and the parent directories if it does not exist."
  ([dir] (find-or-create-migration-dir utils/default-migration-parent dir))
  ([parent-dir dir]
   (if-let [migration-dir (utils/find-migration-dir dir)]
     migration-dir

     ;; Couldn't find the migration dir, create it
     (let [new-migration-dir (io/file parent-dir dir)]
       (io/make-parents new-migration-dir ".")
       new-migration-dir))))

(defn make-migration
  "Constructs a Migration instance from the merged migration file maps collected
  by find-migrations. Expected structure for `mig` is:
  {`migration-name` {`migration-type` payload}} where the structure of `payload`
  is specific to the migration type."
  [config id mig]
  (if-let [id (parse-migration-id id)]
    (if (= 1 (count mig))
      (let [[mig-name mig'] (first mig)]
        (if (= 1 (count mig'))
          (let [[mig-type payload] (first mig')]
            (proto/make-migration* mig-type id mig-name payload config))
          (throw (Exception.
                   (format
                     "Multiple migration types specified for migration %d %s"
                     id (pr-str (map name (keys mig'))))))))
      (throw (Exception. (format "Multiple migrations with id %d %s"
                                 id (pr-str (keys mig))))))
    (throw (Exception. (str "Invalid migration id: " id)))))

(defn list-migrations
  "Find all migrations and return a sequence of Migration instances"
  [config]
  (doall
    (for [[id mig] (find-migrations (utils/get-migration-dir config)
                                    (utils/get-exclude-scripts config)
                                    (props/load-properties config))]
      (make-migration config id mig))))

(defn create
  "Create a migration file given a configuration, a name and migration type.
   Resolves the absolute file name.
   Returns the migration file name as string.

   Migrations are created in migration-dir.
   If migration-dir does not exist, it will be created. "
  [config name migration-type]
  (let [migration-dir  (find-or-create-migration-dir
                        (utils/get-parent-migration-dir config)
                        (utils/get-migration-dir config))
        migration-name (->kebab-case (str (timestamp) name))]
    (doall
     (for [mig-file (proto/migration-files* migration-type migration-name)]
       (let [file (io/file migration-dir mig-file)
             file (.getAbsoluteFile file)]
         (.createNewFile file)
         (.getName (io/file migration-dir mig-file)))))))

(defn create-squash [config id name migration-type ups downs]
  (let [migration-dir  (find-or-create-migration-dir
                        (utils/get-parent-migration-dir config)
                        (utils/get-migration-dir config))
        migration-name (->kebab-case (str id "-" name))]
    (proto/squash-migration-files* migration-type migration-dir migration-name ups downs)))

(defn destroy
  "Delete both files associated with a migration (up and down).
   Migration is identified by name."
  [config name]
  (let [migration-dir  (utils/find-migration-dir
                        (utils/get-migration-dir config))
        migration-name (->kebab-case name)
        pattern        (re-pattern (str "[\\d]*-" migration-name "\\..*"))
        migrations     (file-seq migration-dir)]
    (doseq [f (filter #(re-find pattern (.getName ^File %)) migrations)]
      (.delete ^File f))))
