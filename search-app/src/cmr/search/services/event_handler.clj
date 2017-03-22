(ns cmr.search.services.event-handler
  "Provides functions for subscribing to and handling events."
  (:require
    [cmr.access-control.services.event-handler :as acl-handler]
    [cmr.acl.acl-fetcher :as acl-fetcher]
    [cmr.search.queue-config :as config]
    [cmr.message-queue.services.queue :as queue]))

(defn handle-acl-cache-expiration
  [context {:keys [action concept-id object-identity-type]}]
  (if (and
       (= action :acl-cache-should-expire)
       (some #(= object-identity-type %) config/relevant-acl-identity-types))
   (acl-fetcher/refresh-acl-cache context)))

(defn subscribe-to-events
  "Subscribe to event messages on various queues"
  [context]
  (let [queue-broker (get-in context [:system :queue-broker])]
    (dotimes [n (config/acl-cache-refresh-listener-count)]
      (queue/subscribe queue-broker
                       (config/access-control-cache-queue-name)
                       #(handle-acl-cache-expiration context %)))))
