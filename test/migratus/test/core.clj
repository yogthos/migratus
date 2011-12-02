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
  (:require [migratus.protocols :as proto])
  (:use [clojure.test]
        [migratus.core]))

(defrecord MockMigration [version name up down]
  proto/Migration
  (proto/version [this]
    version)
  (proto/name [this]
    name)
  (proto/up [this]
    (swap! up conj version))
  (proto/down [this]
    (swap! down conj version)))

(defrecord MockStore [completed-versions migrations]
  proto/Store
  (proto/completed-versions [this]
    completed-versions)
  (proto/migrations [this]
    migrations))

(defn make-migration [{:keys [version name up down]}]
  (MockMigration. version name up down))

(defmethod proto/make-store :mock
  [{:keys [completed-versions migrations]}]
  (MockStore. completed-versions (map make-migration migrations)))

(defn migrations [up down]
  (for [n (range 4)]
    {:version (inc n) :name (str "version-" (inc n)) :up up :down down}))

(deftest test-migrate
  (let [up (atom [])
        down (atom [])
        migrations (migrations up down)
        config {:backend :mock
                :completed-versions [1 3]
                :migrations (reverse migrations)}]
    (migrate config)
    (is (= [2 4] @up))))

(deftest test-up
  (testing "should bring up an uncompleted migration"
    (let [up-calls (atom [])
          down-calls (atom [])
          migrations (migrations up-calls down-calls)
          config {:backend :mock
                  :completed [1 3]
                  :migrations migrations}]
      (up config 4 2)
      (is (= [2 4] @up-calls))))
  (testing "should do nothing for a completed migration"
    (let [up-calls (atom [])
          down-calls (atom [])
          migrations (migrations up-calls down-calls)
          config {:backend :mock
                  :completed-versions [1 3]
                  :migrations migrations}]
      (up config 1)
      (is (empty? @up-calls)))))

(deftest test-down
  (testing "should bring down a completed migration"
    (let [up-calls (atom [])
          down-calls (atom [])
          migrations (migrations up-calls down-calls)
          config {:backend :mock
                  :completed-versions [1 3]
                  :migrations migrations}]
      (down config 1 3)
      (is (= [3 1] @down-calls))))
  (testing "should do nothing for an uncompleted migration"
    (let [up-calls (atom [])
          down-calls (atom [])
          migrations (migrations up-calls down-calls)
          config {:backend :mock
                  :completed-versions [1 3]
                  :migrations migrations}]
      (down config 2)
      (is (empty? @down-calls)))))
