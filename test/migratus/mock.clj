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

(defrecord MockMigration [id name ups downs]
  proto/Migration
  (proto/id [this]
    id)
  (proto/name [this]
    name)
  (proto/up [this]
    (swap! ups conj id))
  (proto/down [this]
    (swap! downs conj id)))

(defrecord MockStore [completed-ids migrations]
  proto/Store
  (proto/completed-ids [this]
    completed-ids)
  (proto/migrations [this]
    migrations)
  (proto/run [this migration-fn]
    (migration-fn)))

(defmethod proto/make-store :mock
  [{:keys [completed-ids migrations]}]
  (MockStore. completed-ids (map make-migration migrations)))
