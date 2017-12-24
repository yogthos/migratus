(ns migratus.utils
  (:import java.io.File
           java.util.jar.JarFile
           java.util.regex.Pattern))

(def default-migration-parent "resources/")
(def default-migration-dir "migrations")
(def default-init-script-name "init.sql")

(defn get-parent-migration-dir
  "Gets the :parent-migration-dir from config, or default if missing."
  [config]
  (get config :parent-migration-dir default-migration-parent))

(defn get-migration-dir
  "Gets the :migration-dir from config, or default if missing."
  [config]
  (get config :migration-dir default-migration-dir))

(defn get-init-script
  "Gets the :init-script from config, or default if missing."
  [config]
  (get config :init-script default-init-script-name))

(defn get-exclude-scripts
  "Returns a set of script names to exclude when finding migrations"
  [config]
  (into #{(get-init-script config)}
        (get config :exclude-scripts)))

(defn ensure-trailing-slash
  "Put a trailing slash on the dirname if not present"
  [dir]
  (if (not= (last dir) \/)
    (str dir "/")
    dir))

(defn jar-file [url]
  (some-> url
          (.getFile)
          (.split "!/")
          (first)
          (.replaceFirst "file:" "")
          (JarFile.)))

(defn find-migration-dir
  "Finds the given directory on the classpath"
  [dir]
  (when-let [url (ClassLoader/getSystemResource dir)]
    (if (= "jar" (.getProtocol url))
      (jar-file url)
      (File. (.getFile url)))))

(defn deep-merge
  "Merge keys at all nested levels of the maps."
  [& maps]
  (apply merge-with deep-merge maps))

(defn recursive-delete
  "Delete a file, including all children if it's a directory"
  [^File f]
  (when (.exists f)
    (if (.isDirectory f)
      (doseq [child (.listFiles f)]
        (recursive-delete child))
      (.delete f))))

