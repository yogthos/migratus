(ns migratus.test.migration.edn-with-args
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [migratus.core :as core]
            [migratus.migration.edn :refer :all]
            migratus.mock
            [migratus.protocols :as proto]
            [migratus.utils :as utils])
  (:import java.io.File))

(defn unload [ns-sym]
  (remove-ns ns-sym)
  (dosync
   (commute (deref #'clojure.core/*loaded-libs*) disj ns-sym)))

(def test-namespace 'migratus.test.migration.edn.test-script-args)
(def test-dir "target/edn-args-test")
(def test-config {:output-dir test-dir})

(defn test-file-exists? []
  (let [f (io/file test-dir "hello-with-args.txt")]
    (and (.exists f)
         (= "Hello, world, with args!" (slurp f)))))

(use-fixtures :once
  (fn [f]
    (f)
    ;; `lein test` thinks it needs to test this namespace, so make sure
    ;; that it exists when we're done
    (require test-namespace)))

(use-fixtures :each
  (fn [f]
    ;; unload the namespace before each test to ensure that it's loaded
    ;; appropriately by the edn-migration code.
    (unload test-namespace)
    (utils/recursive-delete (io/file test-dir))
    (f)))

(deftest test-run-edn-migrations
  (let [config (merge test-config
                      {:store :mock
                       :completed-ids (atom #{})
                       :migration-dir "migrations-edn-args"})]
    (is (not (test-file-exists?)))
    (core/migrate config)
    (is (test-file-exists?))
    (core/rollback config)
    (is (not (test-file-exists?)))))
