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
(ns migratus.mock
  (:require [migratus.protocols :as proto]))

(defrecord MockMigration [db id name ups downs]
  proto/Migration
  (id [this]
    id)
  (migration-type [this]
    :sql)
  (name [this]
    name)
  (up [this config]
    (swap! ups conj id)
    :success)
  (down [this config]
    (swap! downs conj id)
    :success))

(defrecord MockStore [completed-ids config]
  proto/Store
  (init [this])
  (completed-ids [this]
    @completed-ids)
  (completed [this]
    (map (fn [id] {:id id :applied true}) @completed-ids))
  (migrate-up [this migration]
    (proto/up migration config)
    (swap! completed-ids conj (proto/id migration))
    :success)
  (migrate-down [this migration]
    (proto/down migration config)
    (swap! completed-ids disj (proto/id migration)))
  (connect [this])
  (disconnect [this]))

(defn make-migration [{:keys [id name ups downs]}]
  (MockMigration. nil id name ups downs))

(defmethod proto/make-store :mock
  [{:keys [completed-ids] :as config}]
  (MockStore. completed-ids config))
