(ns migratus.testcontainers.postgres
  "Integration tests for postgresql using testcontainers.org"
  {:authors ["Eugen Stan"]}
  (:require [clj-test-containers.core :as tc]
            [clojure.tools.logging :as log]
            [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [migratus.test.migration.sql :as test-sql]
            [migratus.core :as migratus]
            [migratus.migration.sql :as sql-mig]
            [migratus.migrations :as mig]
            [migratus.protocols :as proto]
            [migratus.utils :as utils]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [next.jdbc.transaction :as jdbc-tx]))

(def postgres-image (or (System/getenv "MIGRATUS_TESTCONTAINERS_POSTGRES_IMAGE") 
                        "postgres:14"))

(def pg-container-spec {:image-name    postgres-image
                        :exposed-ports [5432]
                        :env-vars      {"POSTGRES_PASSWORD" "pw"}
                        :wait-for      {:wait-strategy :port}})


(deftest postgres-migrations-test

  (testing "Migrations are applied successfully in PostgreSQL."
    (binding [;; Testing that everything works even if some wrapper code prohibits nested transactions.
              jdbc-tx/*nested-tx* :prohibit]
      (let [pg-container (tc/create pg-container-spec)
            initialized-pg-container (tc/start! pg-container)
            meta->table-names #(into #{} (map :pg_class/table_name %))]
        (Thread/sleep 1000)
        (let [ds (jdbc/get-datasource {:dbtype   "postgresql"
                                       :dbname   "postgres"
                                       :user     "postgres"
                                       :password "pw"
                                       :host     (:host initialized-pg-container)
                                       :port     (get (:mapped-ports initialized-pg-container) 5432)})
              config {:store                :database
                      :migration-dir        "migrations-postgres"
                      :init-script          "init.sql"
                      :migration-table-name "foo_bar"
                      :db                   {:datasource ds}}]
          (is (= [] (test-sql/db-tables-and-views ds)) "db is empty before migrations")

          ;; init
          (migratus/init config)
          (let [db-meta (test-sql/db-tables-and-views ds)
                table-names (meta->table-names db-meta)]
            (is (= #{"foo"} table-names) "db is initialized"))
          ;; #93: the init script uses --;; separators and a comment-only
          ;; section, and inserts a value containing '--'. Splitting must run
          ;; each statement while leaving '--' inside the literal intact.
          (is (= "5--10"
                 (:note (jdbc/execute-one! ds ["SELECT note FROM foo WHERE id = 1"]
                                           {:builder-fn rs/as-unqualified-lower-maps})))
              "'--' inside the init-script string literal is preserved on postgres")

          ;; migrate
          (migratus/migrate config)
          (let [db-meta (test-sql/db-tables-and-views ds)
                table-names (meta->table-names db-meta)
                expected-tables #{"quux" "foo" "foo_bar"}]
            (log/info "Tables are" table-names)
            (is (= (count expected-tables) (count db-meta))
                (str "expected table count is ok."))

            (is (set/subset? expected-tables table-names)
                "contains some tables that we expect")))

        (tc/stop! initialized-pg-container)))))

(defn- pg-datasource [initialized-pg-container]
  (jdbc/get-datasource {:dbtype   "postgresql"
                        :dbname   "postgres"
                        :user     "postgres"
                        :password "pw"
                        :host     (:host initialized-pg-container)
                        :port     (get (:mapped-ports initialized-pg-container) 5432)}))

(deftest single-transaction-rollback-test
  (testing "issue #274: :migrate-in-transaction? runs a whole batch of pending migrations atomically"
    (let [pg-container (tc/create pg-container-spec)
          initialized-pg-container (tc/start! pg-container)]
      (Thread/sleep 1000)
      (let [ds (pg-datasource initialized-pg-container)
            table-exists? (fn [table]
                            (contains? (->> (test-sql/db-tables-and-views ds)
                                            (map :pg_class/table_name)
                                            (into #{}))
                                       table))
            base-config {:store                :database
                        :migration-dir        "migrations-postgres-single-tx"
                        :migration-table-name "single_tx_migrations"
                        :db                   {:datasource ds}}]

        (testing "by default, an earlier successful migration in the batch survives a later failure"
          (is (thrown? Throwable (migratus/migrate base-config)))
          (is (table-exists? "good_table")))

        (jdbc/execute! ds ["DROP TABLE IF EXISTS good_table"])
        (jdbc/execute! ds ["DROP TABLE IF EXISTS single_tx_migrations"])

        (testing "with :migrate-in-transaction? true, the whole batch (including the earlier success) is rolled back"
          (is (thrown? Throwable (migratus/migrate (assoc base-config :migrate-in-transaction? true))))
          (is (not (table-exists? "good_table"))))

        (tc/stop! initialized-pg-container)))))

(deftest repeatable-migration-test
  (testing "repeatable migrations run after regular ones, react to schema created earlier in the batch,
           and are skipped on the next run while their checksum is unchanged"
    (let [pg-container (tc/create pg-container-spec)
          initialized-pg-container (tc/start! pg-container)]
      (Thread/sleep 1000)
      (let [ds (pg-datasource initialized-pg-container)
            config {:store                :database
                    :migration-dir        "migrations-repeatable"
                    :init-script          "init.sql"
                    :migration-table-name "repeatable_migrations"
                    :db                   {:datasource ds}}
            updated-at (fn []
                        (:updated_at (jdbc/execute-one! ds ["SELECT updated_at FROM quux WHERE id = 1"]
                                                        {:builder-fn rs/as-unqualified-lower-maps})))
            trigger-applied-at (fn []
                                 (:applied (jdbc/execute-one!
                                            ds ["SELECT applied FROM repeatable_migrations WHERE checksum IS NOT NULL"]
                                            {:builder-fn rs/as-unqualified-lower-maps})))]
        (migratus/init config)
        (migratus/migrate config)

        (testing "the repeatable migration's trigger, which depends on the quux table from an earlier migration, fires on update"
          (jdbc/execute! ds ["INSERT INTO quux(id, name) VALUES (1, 'a')"])
          (is (nil? (updated-at)))
          (jdbc/execute! ds ["UPDATE quux SET name = 'b' WHERE id = 1"])
          (is (some? (updated-at))))

        (testing "re-running migrate with an unchanged checksum does not re-apply the repeatable migration"
          (let [applied-before (trigger-applied-at)]
            (migratus/migrate config)
            (is (= applied-before (trigger-applied-at)))))

        (testing "a changed checksum re-applies the repeatable migration"
          (let [applied-before  (trigger-applied-at)
                original-list-migrations mig/list-migrations
                ;; same trigger, with a harmless comment added so its checksum differs
                changed-sql     (str "-- :repeatable\n"
                                     "-- recreate the trigger (content changed)\n"
                                     "CREATE OR REPLACE FUNCTION quux_set_updated_at()\n"
                                     "RETURNS TRIGGER AS $$\nBEGIN\n  NEW.updated_at = now();\n  RETURN NEW;\nEND;\n"
                                     "$$ LANGUAGE plpgsql;\n"
                                     "--;;\n"
                                     "DROP TRIGGER IF EXISTS quux_set_updated_at ON quux;\n"
                                     "--;;\n"
                                     "CREATE TRIGGER quux_set_updated_at\nBEFORE UPDATE ON quux\n"
                                     "FOR EACH ROW\nEXECUTE PROCEDURE quux_set_updated_at();\n")
                changed-migration (sql-mig/->RepeatableSqlMigration
                                    20220820030300 "create-trigger-quux" changed-sql
                                    (utils/crc32 changed-sql))]
            (with-redefs [mig/list-migrations
                          (fn [cfg]
                            (conj (remove #(= "create-trigger-quux" (proto/name %))
                                          (original-list-migrations cfg))
                                  changed-migration))]
              (migratus/migrate config))
            (is (not= applied-before (trigger-applied-at))
                "the repeatable migration re-ran because its checksum changed")))

        (tc/stop! initialized-pg-container)))))
