(ns migratus.test.migration.edn.test-script-args
  (:require [clojure.java.io :as io]))

(defn migrate-up [{:keys [output-dir]} filename msg]
  (.mkdirs (io/file output-dir))
  (spit (io/file output-dir filename) msg))

(defn migrate-down [{:keys [output-dir]} filename]
  (let [f (io/file output-dir filename)]
    (when (.exists f)
      (.delete f))))
