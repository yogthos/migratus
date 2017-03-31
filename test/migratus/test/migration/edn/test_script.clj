(ns migratus.test.migration.edn.test-script
  (:require [clojure.java.io :as io]))

(def test-file-name "hello.txt")

(defn migrate-up [{:keys [output-dir]}]
  (.mkdirs (io/file output-dir))
  (spit (io/file output-dir test-file-name) "Hello, world!"))

(defn migrate-down [config]
  (let [f (io/file (:output-dir config) test-file-name)]
    (when (.exists f)
      (.delete f))))
