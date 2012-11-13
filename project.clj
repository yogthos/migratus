(defproject migratus "0.5.0"
  :description "MIGRATE ALL THE THINGS!"
  :dependencies [[org.clojure/clojure "1.2.1"]
                 [org.clojure/java.classpath "0.1.0"]
                 [org.clojure/java.jdbc "0.2.3"]
                 [org.clojure/tools.logging "0.2.3"]
                 [robert/bruce "0.7.1"]]
  :dev-dependencies [[org.clojure/java.classpath "0.1.0"]
                     [org.clojure/java.jdbc "0.2.3"]
                     [org.clojure/tools.logging "0.2.3"]
                     [jar-migrations "1.0.0"]
                     [log4j "1.2.16"]
                     [mysql/mysql-connector-java "5.1.18"]
                     [robert/bruce "0.7.1"]]
  :eval-in-project true
  :migratus {:store :database
             :migration-dir "migrations/"
             :db {:classname "com.mysql.jdbc.Driver"
                  :subprotocol "mysql"
                  :subname "//localhost/migratus"
                  :user "root"
                  :password ""}})
