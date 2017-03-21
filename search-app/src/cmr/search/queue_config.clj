(ns cmr.search.queue-config
  "Contains functions to retrieve access control specific configuration"
  (:require [cmr.common.config :as cfg :refer [defconfig]]
            [cmr.message-queue.config :as rmq-conf]))

(defconfig acl-cache-refresh-exchange-name
  "The access control exchange to which cache refresh messages are published."
  {:default "cmr_access_control.cache_refresh"})

(defn queue-config
  "Returns the queue configuration for the application."
  []
  (assoc (rmq-conf/default-config)
         :queues-to-exchanges [(acl-cache-refresh-exchange-name)]))
