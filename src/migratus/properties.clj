(ns migratus.properties
  (:require
    [clojure.string :as s]))

(def ^:private default-properties
  #{"migratus.schema" "migratus.user" "migratus.database" "migratus.timestamp"})

(defn read-system-env []
  (reduce
    (fn [m [k v]]
      (assoc m (-> (s/lower-case k) (s/replace "_" ".")) v))
    {}
    (System/getenv)))

(defn inject-properties [properties text]
  (reduce
    (fn [text [k v]]
      (.replace text k v))
    text
    properties))

(defn system-properties
  "read systme properties, accepts an optional collection of strings
   specifying additional property names"
  [& [property-names]]
  (let [props (read-system-env)]
    (reduce
      (fn [m k]
        (if-let [v (get props k)]
          (assoc m (str "${" k "}") v)
          m))
      {}
      (into default-properties property-names))))
