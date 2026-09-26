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

(defrecord MockMigration [db id name ups downs tx?]
  proto/Migration
  (id [this]
    id)
  (migration-type [this]
    :sql)
  (name [this]
    name)
  (tx? [this direction]
    (if (nil? tx?) true tx?))
  (up [this config]
    (swap! ups conj id)
    :success)
  (down [this config]
    (swap! downs conj id)
    :success))

(defrecord MockRepeatableMigration [db id name checksum ups tx?]
  proto/Migration
  (id [this]
    id)
  (migration-type [this]
    :sql)
  (name [this]
    name)
  (tx? [this direction]
    (if (nil? tx?) true tx?))
  (up [this config]
    (swap! ups conj id)
    :success)
  (down [this config]
    :noop)

  proto/RepeatableMigration
  (checksum [this]
    checksum))

(defrecord MockStore [completed-ids repeatable-checksums config]
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
  (repeatable-checksums [this]
    @repeatable-checksums)
  (migrate-repeatable-up [this name checksum migration]
    (proto/up migration config)
    (swap! repeatable-checksums assoc name checksum)
    :success)
  (execute-in-tx [this f]
    (f))
  (connect [this])
  (disconnect [this]))

(defn make-migration [{:keys [id name ups downs tx?]}]
  (MockMigration. nil id name ups downs tx?))

(defn make-repeatable-migration [{:keys [id name checksum ups tx?]}]
  (MockRepeatableMigration. nil id name checksum ups tx?))

(defmethod proto/make-store :mock
  [{:keys [completed-ids] :as config}]
  (MockStore. completed-ids (atom {}) config))
