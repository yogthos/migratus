(ns migratus.logger
  (:import org.slf4j.LoggerFactory
           [ch.qos.logback.classic Level Logger]))

(.setLevel (LoggerFactory/getLogger Logger/ROOT_LOGGER_NAME) Level/ERROR)

