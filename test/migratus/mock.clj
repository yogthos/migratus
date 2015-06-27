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
  (name [this]
    name)
  (up [this]
    (swap! ups conj id))
  (down [this]
    (swap! downs conj id)))

#_(defrecord MockStore [completed-ids migrations]
  proto/Store
  (completed-ids [this]
    completed-ids)
  (migrations [this]
    migrations)
  (begin [this])
  (end [this]))

(defn make-migration [{:keys [id name ups downs]}]
  (MockMigration. nil id name ups downs))

#_(defmethod proto/make-store :mock
  [{:keys [completed-ids migrations]}]
  (MockStore. completed-ids (map make-migration migrations)))
