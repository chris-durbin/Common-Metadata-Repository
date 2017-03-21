(ns cmr.ingest.config
  "Contains functions to retrieve metadata db specific configuration"
  (:require [cmr.common.config :as cfg :refer [defconfig]]
            [cmr.oracle.config :as oracle-config]
            [cmr.oracle.connection :as conn]
            [cmr.message-queue.config :as rmq-conf]))

(def relevant-acl-identity-types
  "The identity types that the access-control system cares about"
  [:catalog-item :system-object :provider-object])

(defconfig ingest-username
  "Ingest database username"
  {:default "CMR_INGEST"})

(defconfig ingest-password
  "Ingest database password"
  {})

(defn db-spec
  "Returns a db spec populated with config information that can be used to connect to oracle"
  [connection-pool-name]
  (conn/db-spec
    connection-pool-name
    (oracle-config/db-url)
    (oracle-config/db-fcf-enabled)
    (oracle-config/db-ons-config)
    (ingest-username)
    (ingest-password)))

(defconfig access-control-cache-queue-name
  "The queue containing access control cache events"
  {:default "cmr_access_control_cache.queue"})

(defconfig provider-exchange-name
  "The ingest exchange to which provider change messages are published."
  {:default "cmr_ingest_provider.exchange"})

(defconfig acl-cache-refresh-exchange-name
  "The access control exchange to which cache refresh messages are published."
  {:default "cmr_access_control.cache_refresh"})

(defconfig acl-cache-config-listener-count
  "Number of worker threads to use for the queue listener"
  {:default 2
   :type Long})

(defn queue-config
  "Returns the rabbit mq configuration for the ingest application."
  []
  (assoc (rmq-conf/default-config)
         :queues [(access-control-cache-queue-name)]
         :exchanges [(provider-exchange-name)
                     (acl-cache-refresh-exchange-name)]
         :queues-to-exchanges {(access-control-cache-queue-name) [(acl-cache-refresh-exchange-name)]}))

(defconfig ingest-nrepl-port
  "Port to listen for nREPL connections."
  {:default nil :parser cfg/maybe-long})

(defconfig return-umm-json-validation-errors
  "Flag for whether or not UMM-JSON validation errors should be returned for collections."
  {:default false :type Boolean})

(defconfig return-umm-spec-validation-errors
  "Flag for whether or not UMM Spec validation errors should be returned for collections."
  {:default false :type Boolean})
