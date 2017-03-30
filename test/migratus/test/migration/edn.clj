(ns migratus.test.migration.edn
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [migratus.core :as core]
            [migratus.migration.edn :refer :all]
            migratus.mock
            [migratus.protocols :as proto])
  (:import java.io.File))

(defn recursive-delete [^File f]
  (when (.exists f)
    (if (.isDirectory f)
      (doseq [child (.listFiles f)]
        (recursive-delete child))
      (.delete f))))

(defn unload [ns-sym]
  (remove-ns ns-sym)
  (dosync
   (commute (deref #'clojure.core/*loaded-libs*) disj ns-sym)))

(def test-namespace 'migratus.test.migration.edn.test-script)
(def test-dir "target/edn-test")
(def test-config {:output-dir test-dir})

(defn test-file-exists? []
  (let [f (io/file test-dir "hello.txt")]
    (and (.exists f)
         (= "Hello, world!" (slurp f)))))

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
    (recursive-delete (io/file test-dir))
    (f)))

(deftest test-to-sym
  (are [x y] (= y (to-sym x))
    nil nil
    "aaa" 'aaa
    'aaa 'aaa
    :aaa 'aaa)
  (are [x] (thrown-with-msg?
            IllegalArgumentException
            #"Namespaced symbol not allowed"
            (to-sym x))
    "aaa/bbb"
    'aaa/bbb
    :aaa/bbb
    'a.b.c/def
    :a.b.c/def))

(deftest test-resolve-fn
  (require test-namespace)
  (is (var? (resolve-fn "test-mig" test-namespace "migrate-up")))
  (is (var? (resolve-fn "test-mig" test-namespace 'migrate-up)))
  (is (var? (resolve-fn "test-mig" test-namespace :migrate-up)))
  (is (thrown-with-msg?
       IllegalArgumentException
       #"Unable to resolve"
       (resolve-fn "test-mig" test-namespace "not-a-fn")))
  (is (thrown-with-msg?
       IllegalArgumentException
       #"Namespaced symbol not allowed"
       (resolve-fn "test-mig" test-namespace "clojure.core/map"))))

(defn edn-mig [content]
  (proto/make-migration* :edn 1 "edn-migration" (pr-str content) nil))

(deftest test-invalid-migration
  (testing "namespace is required"
    (is (thrown-with-msg?
         IllegalArgumentException
         #"Invalid migration .* no namespace"
         (edn-mig {}))))
  (testing "namespace must exist"
    (is (thrown?
         Exception
         (edn-mig {:ns 'foo.bar.baz}))))
  (testing "fn must exist"
    (is (thrown-with-msg?
         IllegalArgumentException
         #"Unable to resolve"
         (edn-mig {:ns test-namespace
                   :up-fn 'not-a-real-fn
                   :down-fn 'not-a-real-fn})))))

(deftest test-edn-migration
  (let [mig (edn-mig {:ns test-namespace
                      :up-fn 'migrate-up
                      :down-fn 'migrate-down})]
    (is (not (test-file-exists?)))
    (proto/up mig test-config)
    (is (test-file-exists?))
    (proto/down mig test-config)
    (is (not (test-file-exists?)))))

(deftest test-edn-down-optional
  (let [mig (edn-mig {:ns test-namespace
                      :up-fn 'migrate-up
                      :down-fn nil})]
    (is (not (test-file-exists?)))
    (proto/up mig test-config)
    (is (test-file-exists?))
    (proto/down mig test-config)
    (is (test-file-exists?))))

(deftest test-run-edn-migrations
  (let [config (merge test-config
                      {:store :mock
                       :completed-ids (atom #{})
                       :migration-dir "migrations-edn"})]
    (is (not (test-file-exists?)))
    (core/migrate config)
    (is (test-file-exists?))
    (core/rollback config)
    (is (not (test-file-exists?)))))
