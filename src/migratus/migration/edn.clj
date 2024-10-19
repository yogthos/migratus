(ns migratus.migration.edn
  "Support for EDN migration files that specify clojure code migrations."
  (:require
    [clojure.edn :as edn]
    [migratus.protocols :as proto]))

;; up-fn and down-fn here are actually vars; invoking them as fns will deref
;; them and invoke the fn bound by the var.
(defrecord EdnMigration [id name up-fn down-fn transaction? up-args down-args]
  proto/Migration
  (id [this] id)
  (name [this] name)
  (tx? [this direction] (if (nil? transaction?) true transaction?))
  (up [this config]
    (when up-fn
      (apply up-fn config up-args)))
  (down [this config]
    (when down-fn
      (apply down-fn config down-args))))

(defn to-sym
  "Converts x to a non-namespaced symbol, throwing if x is namespaced"
  [x]
  (let [validate #(if (and (instance? clojure.lang.Named %)
                           (namespace %))
                    (throw (IllegalArgumentException.
                             (str "Namespaced symbol not allowed: " %)))
                    %)]
    ;; validate on input to catch namespaced symbols/keywords, and on output
    ;; to catch strings that parse as a namespaced symbol e.g. "foo/bar"
    (some-> x validate name symbol validate)))

(defn resolve-fn
  "Basically ns-resolve with some error-checking"
  [mig-name mig-ns fn-name]
  (when fn-name
    (or (ns-resolve mig-ns (to-sym fn-name))
        (throw (IllegalArgumentException.
                 (format "Unable to resolve %s/%s for migration %s"
                         mig-ns fn-name mig-name))))))

(defmethod proto/make-migration* :edn
  [_ mig-id mig-name payload config]
  (let [{:keys [ns up-fn down-fn transaction?]
         :or   {up-fn "up" down-fn "down"}} (edn/read-string payload)
        mig-ns (to-sym ns)
        [up-fn & up-args] (cond-> up-fn (not (coll? up-fn)) vector)
        [down-fn & down-args] (cond-> down-fn (not (coll? down-fn)) vector)]
    (when-not mig-ns
      (throw (IllegalArgumentException.
               (format "Invalid migration %s: no namespace" mig-name))))
    (require mig-ns)
    (->EdnMigration mig-id mig-name
                    (resolve-fn mig-name mig-ns up-fn)
                    (resolve-fn mig-name mig-ns down-fn)
                    transaction?
                    up-args
                    down-args)))

(defmethod proto/get-extension* :edn
  [_]
  "edn")

(defmethod proto/migration-files* :edn
  [x migration-name]
  [(str migration-name "." (proto/get-extension* x))])

(defmethod proto/squash-migration-files* :edn
  [x migration-dir migration-name ups downs]
  (throw (Exception. "EDN migrations not implemented")))
