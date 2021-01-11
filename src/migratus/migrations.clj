(ns migratus.migrations
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

(defn ->kebab-case [s]
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

(defn- timestamp []
  (let [fmt (doto (SimpleDateFormat. "yyyyMMddHHmmss ")
              (.setTimeZone (TimeZone/getTimeZone "UTC")))]
    (.format fmt (Date.))))

(defn parse-migration-id [id]
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

(defn find-migration-files [migration-dir exclude-scripts properties]
  (log/debug "Looking for migrations in" migration-dir)
  (->> (for [f (filter (fn [^File f] (.isFile f))
                       (file-seq migration-dir))
             :let [file-name (.getName ^File f)]]
         (if-let [mig (parse-name file-name)]
           (migration-map mig (slurp f) properties)
           (when-not (exclude-scripts (.getName ^File f))
             (warn-on-invalid-migration file-name))))
       (remove nil?)))


(defn find-migration-resources [dir jar exclude-scripts properties]
  (log/debug "Looking for migrations in" dir jar)
  (->> (for [entry (enumeration-seq (.entries ^JarFile jar))
             :when (.matches (.getName ^JarEntry entry)
                             (str "^" (Pattern/quote dir) ".+"))
             :let [entry-name       (.replaceAll (.getName ^JarEntry entry) dir "")
                   last-slash-index (str/last-index-of entry-name "/")
                   file-name        (subs entry-name (if-not last-slash-index 0
                                                                              (+ 1 last-slash-index)))]]
         (if-let [mig (parse-name file-name)]
           (let [w (StringWriter.)]
             (io/copy (.getInputStream ^JarFile jar entry) w)
             (migration-map mig (.toString w) properties))
           (when-not (exclude-scripts file-name)
             (warn-on-invalid-migration file-name))))
       (remove nil?)))

(defn read-migrations [dir exclude-scripts properties]
  (when-let [migration-dir (utils/find-migration-dir dir)]
    (if (instance? File migration-dir)
      (find-migration-files migration-dir exclude-scripts properties)
      (find-migration-resources dir migration-dir exclude-scripts properties))))

(defn find-migrations [dir exclude-scripts properties]
  (->> (read-migrations (utils/ensure-trailing-slash dir) exclude-scripts properties)
       (apply utils/deep-merge)))

(defn find-or-create-migration-dir
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

(defn list-migrations [config]
  (doall
    (for [[id mig] (find-migrations (utils/get-migration-dir config)
                                    (utils/get-exclude-scripts config)
                                    (when (:inject-properties? config)
                                      (props/system-properties (:custom-properties config))))]
      (make-migration config id mig))))

(defn create [config name migration-type]
  (let [migration-dir  (find-or-create-migration-dir
                         (utils/get-parent-migration-dir config)
                         (utils/get-migration-dir config))
        migration-name (->kebab-case (str (timestamp) name))]
    (doseq [mig-file (proto/migration-files* migration-type migration-name)]
      (.createNewFile (io/file migration-dir mig-file)))))

(defn destroy [config name]
  (let [migration-dir  (utils/find-migration-dir
                         (utils/get-migration-dir config))
        migration-name (->kebab-case name)
        pattern        (re-pattern (str "[\\d]*-" migration-name "\\..*"))
        migrations     (file-seq migration-dir)]
    (doseq [f (filter #(re-find pattern (.getName ^File %)) migrations)]
      (.delete ^File f))))
