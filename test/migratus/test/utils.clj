(ns migratus.test.utils
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [migratus.utils :refer :all])
  (:import [java.nio.file FileSystemAlreadyExistsException FileSystems]
           [java.util.jar JarFile]))

(deftest test-censor-password
  (is (= nil (censor-password nil)))
  (is (= "" (censor-password "")))
  (is (= {:password nil} (censor-password {:password nil})))
  (is (= {:password "1<censored>" :user "user"}
         (censor-password {:password "1234" :user "user"})))
  (is (= "uri-censored"
         (censor-password
           "jdbc:postgresql://fake.example.org/my_dev?user=my_user&password=thisIsNot123ARealPass")))
  (is (= {:connection-uri "uri-censored"}
         (censor-password {:connection-uri "jdbc:postgresql://fake.example.org/my_dev?user=my_user&password=thisIsNot123ARealPass"})))
  (is (= {:connection-uri "uri-censored" :password "1<censored>" :user "user"}
         (censor-password {:password "1234" :user "user"
                           :connection-uri "jdbc:postgresql://fake.example.org/my_dev?user=my_user&password=thisIsNot123ARealPass"}))))

(deftest test-jar-name
  (is (nil? (jar-name nil)))
  (testing "handles file prefix"
    (is (= "///tmp/default/clojure-1.10.1.jar"
           (jar-name "file:///tmp/default/clojure-1.10.1.jar"))))
  (testing "handles '+' in paths"
    (is (= "/tmp/default+uberjar/foo.jar"
           (jar-name "/tmp/default+uberjar/foo.jar")))))

(deftest test-script-excluded-jar-race-condition
  (testing "script-excluded? doesn't throw FileSystemAlreadyExistsException when called concurrently"
    (let [jar-path (.getAbsolutePath (io/file "test/migrations-jar/migrations.jar"))
          threads 10
          attempts 10
          errors (atom [])]
      (dotimes [_ attempts]
        (let [jar (JarFile. jar-path)
              uri (resolve-uri jar)
              _ (try (.close (FileSystems/getFileSystem uri))
                  (catch Exception _))]
          (->> (mapv
                 (fn [_]
                   (future
                     (try
                       (script-excluded? "test.sql" jar #{"*.clj"})
                       (catch FileSystemAlreadyExistsException e
                         (swap! errors conj e)))))
                 (range threads))
            (mapv #(deref % 500 nil)))))
      (is (zero? (count @errors))))))
