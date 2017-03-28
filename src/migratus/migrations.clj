(ns migratus.migrations
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [migratus.migration.sql :as sql-mig]
            [migratus.utils :as utils])
  (:import [java.io File StringWriter]
           java.text.SimpleDateFormat
           [java.util.jar JarEntry JarFile]
           java.util.regex.Pattern
           java.util.Date))

(defn ->kebab-case [s]
  (-> (reduce
        (fn [s c]
          (if (and
                (not-empty s)
                (Character/isLowerCase (last s))
                (Character/isUpperCase c))
            (str s "-" c)
            (str s c)))
        "" s)
      (str/replace #"[\s]+" "-")
      (.replaceAll "_" "-")
      (.toLowerCase)))

(defn- timestamp []
  (let [fmt (SimpleDateFormat. "yyyyMMddHHmmss ")]
    (.format fmt (Date.))))

(defn get-migration-dir [config]
  ;; TODO - find a better home for me.
  (get config :migration-dir "migrations"))

(defn parse-migration-id [id]
  (try
    (Long/parseLong id)
    (catch Exception e
      (log/error e (str "failed to parse migration id: " id)))))

(def default-migration-parent "resources/")

(def migration-file-pattern #"^(\d+)-([^\.]+)((?:\.[^\.]+)+)$")

(defn parse-name [file-name]
  (when-let [[id name ext] (next (re-matches migration-file-pattern file-name))]
    [id name (remove empty? (str/split ext #"\."))]))

(defn warn-on-invalid-migration [file-name]
  (log/warn (str "skipping: '" file-name "'")
            "migrations must match pattern:"
            (str migration-file-pattern)))

(defn migration-map
  [[id name exts] content]
  (assoc-in {}
            (concat [id name] (map keyword (reverse exts)))
            content))

(defn find-migration-files [migration-dir exclude-scripts]
  (->> (for [f (filter (fn [^File f] (.isFile f))
                       (file-seq migration-dir))
             :let [file-name (.getName ^File f)]]
         (if-let [mig (parse-name file-name)]
           (migration-map mig (slurp f))
           (when-not (exclude-scripts (.getName f))
             (warn-on-invalid-migration file-name))))
       (remove nil?)))

(defn find-migration-resources [dir jar init-script-name]
  (->> (for [entry (enumeration-seq (.entries jar))
             :when (.matches (.getName ^JarEntry entry)
                             (str "^" (Pattern/quote dir) ".+"))
             :let [entry-name (.replaceAll (.getName ^JarEntry entry) dir "")]]
         (if-let [mig (parse-name entry-name)]
           (let [w (StringWriter.)]
             (io/copy (.getInputStream ^JarFile jar entry) w)
             (migration-map mig (.toString w)))
           (when (not= entry-name init-script-name)
             (warn-on-invalid-migration entry-name))))
       (remove nil?)))

(defn find-migrations [dir exclude-scripts]
  (->> (let [dir (utils/ensure-trailing-slash dir)]
         (if-let [migration-dir (utils/find-migration-dir dir)]
           (find-migration-files migration-dir exclude-scripts)
           (if-let [migration-jar (utils/find-migration-jar dir)]
             (find-migration-resources dir migration-jar exclude-scripts))))
       (apply utils/deep-merge)))

(defn find-or-create-migration-dir [dir]
  (if-let [migration-dir (utils/find-migration-dir dir)]
    migration-dir

    ;; Couldn't find the migration dir, create it
    (let [new-migration-dir (io/file default-migration-parent dir)]
      (io/make-parents new-migration-dir ".")
      new-migration-dir)))

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
            (case mig-type
              :sql (sql-mig/->SqlMigration id mig-name
                                           (:up payload) (:down payload))
              (throw (Exception. (format "Unknown type '%s' for migration %d"
                                         (name mig-type) id)))))
          (throw (Exception.
                  (format
                   "Multiple migration types specified for migration %d %s"
                   id (pr-str (map name (keys mig'))))))))
      (throw (Exception. (format "Multiple migrations with id %d %s"
                                 id (pr-str (keys mig))))))
    (throw (Exception. (str "Invalid migration id: " id)))))

(defn list-migrations [config]
  (doall
   (for [[id mig] (find-migrations (get-migration-dir config)
                                   (utils/get-exclude-scripts config))]
     (make-migration config id mig))))

(defn create [config name]
  (let [migration-dir (find-or-create-migration-dir (get-migration-dir config))
        migration-name (->kebab-case (str (timestamp) name))
        migration-up-name (str migration-name ".up.sql")
        migration-down-name (str migration-name ".down.sql")]
    (.createNewFile (File. migration-dir migration-up-name))
    (.createNewFile (File. migration-dir migration-down-name))))

(defn destroy [config name]
  (let [migration-dir (utils/find-migration-dir (get-migration-dir config))
        migration-name (->kebab-case name)
        pattern (re-pattern (str "[\\d]*-" migration-name ".*.sql"))
        migrations (file-seq migration-dir)]
    (doseq [f (filter #(re-find pattern (.getName %)) migrations)]
      (.delete f))))
