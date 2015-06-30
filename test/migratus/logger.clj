(ns migratus.logger
  (:import [org.apache.log4j ConsoleAppender PatternLayout Level Logger]))

(.addAppender
  (Logger/getRootLogger)
  (doto (ConsoleAppender.)
    (.setLayout (PatternLayout. "%d [%p|%c|%C{1}] %m%n"))
    (.setThreshold Level/ERROR)
    (.activateOptions)))
