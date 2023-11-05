(ns migratus.test.utils
  (:require [clojure.test :refer :all]
            [migratus.utils :refer :all]
            [clojure.java.io :as io]))

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
                           :connection-uri "jdbc:postgresql://fake.example.org/my_dev?user=my_user&password=thisIsNot123ARealPass"})))
  (is (= {:jdbcUrl "uri-censored" :password "1<censored>" :user "user"}
         (censor-password {:password "1234" :user "user"
                           :jdbcUrl "jdbc:postgresql://fake.example.org/my_dev?user=my_user&password=thisIsNot123ARealPass"}))))

(deftest test-jar-name
  (is (nil? (jar-name nil)))
  (testing "handles file prefix"
    (is (= "///tmp/default/clojure-1.10.1.jar"
           (jar-name "file:///tmp/default/clojure-1.10.1.jar"))))
  (testing "handles '+' in paths"
    (is (= "/tmp/default+uberjar/foo.jar"
           (jar-name "/tmp/default+uberjar/foo.jar")))))


(deftest testfind-migration-dir

  (testing "returns nil for path that does not exist"
    (let [dir (find-migration-dir "migration-dir-does-not-exist")]
      (is (nil? dir))))

  (testing "finds migration dir with relative path"
    (let [dir (find-migration-dir "migrations")
          expected (-> (io/as-file "test/migrations")
                       (.getAbsoluteFile)
                       (.toString))]
      (println "Migration dir" dir)
      (is (some? dir))
      (is (= (.toString dir) expected))))

  (testing "finds migration dir with absolute path and missing dir throws"
    (is (thrown?
         IllegalStateException
         (find-migration-dir "/non-existing-absolute-path")))))

(testing "finds migration dir with absolute path and missing dir"
  (let [dir (find-migration-dir "test/migrations")
        expected (-> (io/as-file "test/migrations")
                     (.getAbsoluteFile)
                     (.toString))]
    (println "Migration dir" dir)
    (is (some? dir))
    (is (= (.toString dir) expected))))


(comment

  (run-test testfind-migration-dir)


  )
