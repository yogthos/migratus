(defproject migratus "0.6.0"
  :description "MIGRATE ALL THE THINGS!"
  :aliases {"test!" ["do" "clean," "test"]}
  :dependencies [[org.clojure/clojure "1.2.1"]
                 [org.clojure/java.classpath "0.1.0"]
                 [org.clojure/java.jdbc "0.2.3"]
                 [org.clojure/tools.logging "0.2.3"]
                 [robert/bruce "0.7.1"]]
  :dev-dependencies [[jar-migrations "1.0.0"]
                     [log4j "1.2.16"]
                     [mysql/mysql-connector-java "5.1.18"]]
  :profiles {:dev {:dependencies [[jar-migrations "1.0.0"]
                                  [log4j "1.2.16"]
                                  [mysql/mysql-connector-java "5.1.18"]]}})
