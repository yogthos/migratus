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
         (censor-password "jdbc:postgresql://fake.rds.amazonaws.com/capital_thing?user=capital_db&password=thisIsNot123ARealPass"))))
