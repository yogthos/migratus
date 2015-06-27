;;;; Copyright © 2011 Paul Stadig
;;;;
;;;; Licensed under the Apache License, Version 2.0 (the "License"); you may not
;;;; use this file except in compliance with the License.  You may obtain a copy
;;;; of the License at
;;;;
;;;;   http://www.apache.org/licenses/LICENSE-2.0
;;;;
;;;; Unless required by applicable law or agreed to in writing, software
;;;; distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
;;;; WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
;;;; License for the specific language governing permissions and limitations
;;;; under the License.
(ns migratus.test.core
  (:require [migratus.protocols :as proto]
            [migratus.mock])
  (:use [clojure.test]
        [migratus.core])
  (:import [migratus.mock MockStore MockMigration]))

(defn migrations [ups downs]
  (for [n (range 4)]
    {:id (inc n) :name (str "id-" (inc n)) :ups ups :downs downs}))

(deftest test-migrate
  (let [ups (atom [])
        downs (atom [])
        migrations (migrations ups downs)
        config {:store :mock
                :completed-ids [1 3]
                :migrations (reverse migrations)}]
    (migrate config)
    (is (= [2 4] @ups))
    (is (empty? @downs))))

(deftest test-up
  (let [ups (atom [])
        downs (atom [])
        migrations (migrations ups downs)
        config {:store :mock
                :completed-ids [1 3]
                :migrations migrations}]
    (testing "should bring up an uncompleted migration"
      (up config 4 2)
      (is (= [2 4] @ups))
      (is (empty? @downs)))
    (reset! ups [])
    (reset! downs [])
    (testing "should do nothing for a completed migration"
      (up config 1)
      (is (empty? @ups))
      (is (empty? @downs)))))

(deftest test-down
  (let [ups (atom [])
        downs (atom [])
        migrations (migrations ups downs)
        config {:store :mock
                :completed-ids [1 3]
                :migrations migrations}]
    (testing "should bring down a completed migration"
      (down config 1 3)
      (is (empty? @ups))
      (is (= [3 1] @downs)))
    (reset! ups [])
    (reset! downs [])
    (testing "should do nothing for an uncompleted migration"
      (down config 2)
      (is (empty? @ups))
      (is (empty? @downs)))))
