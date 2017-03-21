(ns cmr.access-control.config
  "Contains functions to retrieve access control specific configuration"
  (:require [cmr.common.config :as cfg :refer [defconfig]]
            [cmr.message-queue.config :as rmq-conf]))

(def relevant-acl-identity-types
  "The identity types that the access-control system cares about"
  [:system-object :provider-object :single-instance-object])

(defconfig access-control-exchange-name
  "The access control exchange to which update/save messages are published for access control data."
  {:default "cmr_access_control.exchange"})

(defconfig provider-exchange-name
  "The ingest exchange to which provider change messages are published."
  {:default "cmr_ingest_provider.exchange"})

(defconfig provider-queue-name
  "The queue containing provider events"
  {:default "cmr_access_control_provider.queue"})

(defconfig index-queue-name
  "The queue containing ingest events for access control"
  {:default "cmr_access_control_index.queue"})

(defconfig access-control-cache-queue-name
  "The queue containing access control cache events"
  {:default "cmr_access_control_cache.queue"})

(defconfig concept-ingest-exchange-name
  "The ingest exchange to which collection and granule change messages are published."
  {:default "cmr_ingest.exchange"})

(defconfig acl-cache-refresh-exchange-name
  "The access control exchange to which cache refresh messages are published."
  {:default "cmr_access_control.cache_refresh"})

(defconfig index-queue-listener-count
  "Number of worker threads to use for the queue listener"
  {:default 5
   :type Long})

(defn queue-config
  "Returns the queue configuration for the application."
  []
  (assoc (rmq-conf/default-config)
         :queues [(index-queue-name) 
                  (provider-queue-name)
                  (access-control-cache-queue-name)]
         :exchanges [(access-control-exchange-name)]
         :queues-to-exchanges {(index-queue-name) [(access-control-exchange-name)
                                                   (concept-ingest-exchange-name)]
                               (provider-queue-name) [(provider-exchange-name)]
                               (access-control-cache-queue-name) [(acl-cache-refresh-exchange-name)]}))
