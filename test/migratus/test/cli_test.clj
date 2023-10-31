(ns migratus.test.cli-test
  (:require [clojure.test :refer :all]
            [migratus.cli :as cli])
  (:import (java.util HashMap)))

(deftest load-env-config-test
  (testing "Test load config from env - load only MIGRATUS_CONFIG"
    (let [env (doto (HashMap.)
                (.put "MIGRATUS_CONFIG" "{:store :database :migration-dir \"my-migrations\"}")
                (.put "MIGRATUS_DB_SPEC"
                      "{:jdbcUrl \"config-whould-be-ignored\"}"))
          config (cli/env->config! env)]
      (is (= {:store :database
              :migration-dir "my-migrations"}
             config))))

  (testing "Test load config from env - empty when no env vars present"
    (let [env (HashMap.)
          config (cli/env->config! env)]
      (is (= {} config))))

  (testing "Test load migratus config from env"
    (let [env (doto (HashMap.)
                (.put "MIGRATUS_STORE" "database")
                (.put "MIGRATUS_MIGRATION_DIR" "resources/migrations")
                (.put "MIGRATUS_DB_SPEC"
                      "{:jdbcUrl \"jdbc:h2:local-db\"}"))
          config (cli/env->config! env)]
      (is (= {:store :database
              :migration-dir "resources/migrations"
              :db {:jdbcUrl "jdbc:h2:local-db"}}
             config)))))

(comment

  (run-test load-env-config-test)

  )

