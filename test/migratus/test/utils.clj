(ns migratus.test.utils
  (:require [clojure.test :refer :all]
            [migratus.utils :refer :all]))

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
