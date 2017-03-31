(ns migratus.migration.edn
  "Support for EDN migration files that specify clojure code migrations."
  (:require [clojure.edn :as edn]
            [migratus.protocols :as proto]))

;; up-fn and down-fn here are actually vars; invoking them as fns will deref
;; them and invoke the fn bound by the var.
(defrecord EdnMigration [id name up-fn down-fn]
  proto/Migration
  (id [this] id)
  (name [this] name)
  (up [this config]
    (when up-fn
      (up-fn config)))
  (down [this config]
    (when down-fn
      (down-fn config))))

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
  (let [{:keys [ns up-fn down-fn]
         :or {up-fn "up" down-fn "down"}} (edn/read-string payload)
        mig-ns (to-sym ns)]
    (when-not mig-ns
      (throw (IllegalArgumentException.
              (format "Invalid migration %s: no namespace" mig-name))))
    (require mig-ns)
    (->EdnMigration mig-id mig-name
                    (resolve-fn mig-name mig-ns up-fn)
                    (resolve-fn mig-name mig-ns down-fn))))

(defmethod proto/migration-files* :edn
  [_ migration-name]
  [(str migration-name ".edn")])
