(ns migratus.properties
  (:require
    [clojure.string :as s]
    [clojure.tools.logging :as log])
  (:import
    java.util.Date))

(def ^:private default-properties
  #{"migratus.schema" "migratus.user" "migratus.database"})

(defn read-system-env []
  (reduce
    (fn [m [k v]]
      (assoc m (-> (s/lower-case k) (s/replace "_" ".")) v))
    {}
    (System/getenv)))

(defn inject-properties
  [properties text]
  (let [text-with-props (reduce
                         (fn [text [k v]]
                           (.replace text k (str v)))
                         text
                         properties)]
    (doseq [x (re-seq #"\$\{[a-zA-Z0-9\-_\.]+}" text-with-props)]
      (log/warn "no property found for key:" x))
    text-with-props))

(defn system-properties
  "Read system properties, accepts an optional collection of strings
   specifying additional property names"
  [property-names]
  (let [props (read-system-env)]
    (reduce
      (fn [m k]
        (if-let [v (get props k)]
          (assoc m (str "${" k "}") v)
          m))
      {"${migratus.timestamp}" (.getTime (Date.))}
      (into default-properties property-names))))

(defn map->props
  ([m] (map->props {} nil m))
  ([props path m]
   (reduce
     (fn [m [k v]]
       (let [path (if path (str path "." (name k)) (name k))]
         (if (map? v)
           (map->props m path v)
           (assoc m (str "${" path "}") v))))
     props
     m)))

(defn load-properties [{{:keys [env map]} :properties :as opts}]
  (when (map? (:properties opts))
    (merge (system-properties env) (map->props map))))
