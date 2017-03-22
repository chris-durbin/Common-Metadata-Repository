(ns cmr.search.queue-config
  "Contains functions to retrieve access control specific configuration"
  (:require [cmr.common.config :as cfg :refer [defconfig]]
            [cmr.message-queue.config :as rmq-conf]))

(def relevant-acl-identity-types
  "The identity types that the access-control system cares about"
  [:catalog-item :system-object])

(defconfig access-control-cache-queue-name
  "The queue containing access control events"
  {:default "cmr_access_control_cache.queue"})

(defconfig acl-cache-refresh-listener-count
  "Number of worker thread to listen for acl cache refreshes"
  {:default 2
   :type Long})

(defconfig acl-cache-refresh-exchange-name
  "The access control exchange to which cache refresh messages are published."
  {:default "cmr_access_control.cache_refresh"})

(defn queue-config
  "Returns the queue configuration for the application."
  []
  (assoc (rmq-conf/default-config)
         :queues [(access-control-cache-queue-name)]
         :exchanges [(acl-cache-refresh-exchange-name)]
         :queues-to-exchanges {(access-control-cache-queue-name) [(acl-cache-refresh-exchange-name)]}))
