(ns migratus.testcontainers.postgres
  "Integration tests for postgresql using testcontainers.org"
  {:authors ["Eugen Stan"]}
  (:require [clj-test-containers.core :as tc]
            [clojure.tools.logging :as log]
            [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [migratus.test.migration.sql :as test-sql]
            [migratus.core :as migratus]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def postgres-image (or (System/getenv "MIGRATUS_TESTCONTAINERS_POSTGRES_IMAGE") 
                        "postgres:14"))

(def pg-container-spec {:image-name    postgres-image
                        :exposed-ports [5432]
                        :env-vars      {"POSTGRES_PASSWORD" "pw"}
                        :wait-for      {:wait-strategy :port}})


(deftest postgres-migrations-test

  (testing "Migrations are applied succesfully in PostgreSQL."
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
            config {:store :database
                    :migration-dir "migrations-postgres"
                    :init-script "init.sql"
                    :migration-table-name "foo_bar"
                    :db {:dbtype   "postgresql"
                         :dbname   "postgres"
                         :user     "postgres"
                         :password "pw"
                         :host     (:host initialized-pg-container)
                         :port     (get (:mapped-ports initialized-pg-container) 5432)}}]
        (is (= [] (test-sql/db-tables-and-views ds)) "db is empty before migrations")

        ;; init 
        (migratus/init config)
        (let [db-meta (test-sql/db-tables-and-views ds)
              table-names (meta->table-names db-meta)]
          (is (= #{"foo"} table-names) "db is initialized"))

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

      (tc/stop! initialized-pg-container))))
