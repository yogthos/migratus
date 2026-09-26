(ns migratus.migration.repeatable
  (:require [migratus.migration.sql :as sql-mig]
            [migratus.protocols :as proto])
  (:import (java.util.zip CRC32)))

(defn crc32 [^String s]
  (let [crc (CRC32.)
        bytes (.getBytes s "UTF-8")]
    (.update crc bytes)
    (.getValue crc)))

;; Repeatable SQL migration: re-runs up whenever its checksum changes, no down
;; -- like a repeatable EDN migration (migratus.migration.edn), there's no
;; meaningful "down" for a definition that's just re-applied.
(defrecord RepeatableSqlMigration [id name up checksum]
  proto/Migration
  (id [_this]
    id)
  (migration-type [_this] :r-sql)
  (name [_this]
    name)
  (tx? [_this _direction]
    (if up
      (sql-mig/use-tx? up)
      (throw (Exception. (format "SQL up commands not found for %d" id)))))
  (up [_this config]
    (if up
      (sql-mig/run-sql config up :up)
      (throw (Exception. (format "Up commands not found for %d" id)))))
  (down [_this _config] :noop)

  proto/RepeatableMigration
  (checksum [_this]
    checksum))

(defmethod proto/make-migration* :r-sql
  [_ mig-id mig-name payload _config]
  (->RepeatableSqlMigration mig-id mig-name (:up payload) (crc32 (:up payload))))

(defmethod proto/migration-files* :r-sql
  [x migration-name]
  [(str migration-name ".up." (proto/get-extension* x))])


