(ns cmr.ingest.api.routes
  "Defines the HTTP URL routes for the application."
  (:require [compojure.handler :as handler]
            [compojure.route :as route]
            [compojure.core :refer :all]
            [clojure.string :as str]
            [clojure.set :as set]
            [ring.util.response :as r]
            [ring.util.codec :as codec]
            [ring.middleware.json :as ring-json]
            [cmr.ingest.api.multipart :as mp]
            [clojure.stacktrace :refer [print-stack-trace]]
            [cheshire.core :as cheshire]
            [cmr.common.log :refer (debug info warn error)]
            [cmr.common.api :as api]
            [cmr.common.api.errors :as api-errors]
            [cmr.common.services.errors :as srvc-errors]
            [cmr.common.jobs :as common-jobs]
            [cmr.acl.core :as acl]
            [cmr.ingest.services.ingest-service :as ingest]
            [cmr.system-trace.http :as http-trace]
            [cmr.ingest.services.jobs :as jobs]
            [cmr.ingest.api.provider :as provider-api]
            [cmr.ingest.services.messages :as msg]
            [cmr.common-app.api.routes :as common-routes]
            [cmr.common-app.api-docs :as api-docs]))

(defn- set-concept-id-and-revision-id
  "Set concept-id and revision-id for the given concept based on the headers. Ignore the
  revision-id if no concept-id header is passed in."
  [concept headers]
  (let [concept-id (get headers "concept-id")
        revision-id (get headers "revision-id")]
    (if concept-id
      (if revision-id
        (try
          (assoc concept :concept-id concept-id :revision-id (Integer/parseInt revision-id))
          (catch NumberFormatException e
            (srvc-errors/throw-service-error
              :bad-request
              (msg/invalid-revision-id revision-id))))
        (assoc concept :concept-id concept-id))
      concept)))

(defn- sanitize-concept-type
  "Drops the parameter part of the MediaTypes from concept-type and returns the type/sub-type part"
  [concept-type]
  (first (str/split concept-type #";")))

(defn- body->concept
  "Create a metadata concept from the given request body"
  [concept-type provider-id native-id body content-type headers]
  (let [metadata (str/trim (slurp body))]
    (-> {:metadata metadata
         :format (sanitize-concept-type content-type)
         :provider-id provider-id
         :native-id native-id
         :concept-type concept-type}
        (set-concept-id-and-revision-id headers))))

(defn- concept->loggable-string
  "Returns a string with information about the concept as a loggable string."
  [concept]
  (pr-str (dissoc concept :metadata)))

(defmulti validate-granule
  "Validates the granule in the request. It can handle a granule and collection sent as multipart-params
  or a normal request with the XML as the body."
  (fn [provider-id native-id request]
    (if (seq (:multipart-params request))
      :multipart-params
      :default)))

(defmethod validate-granule :default
  [provider-id native-id {:keys [body content-type headers request-context] :as request}]
  (let [concept (body->concept :granule provider-id native-id body content-type headers)]
    (info "Validating granule" (concept->loggable-string concept))
    (ingest/validate-granule request-context concept)))

(defn- multipart-param->concept
  "Converts a multipart parameter "
  [provider-id native-id concept-type {:keys [content-type content]}]
  {:metadata content
   :format (sanitize-concept-type content-type)
   :provider-id provider-id
   :native-id native-id
   :concept-type concept-type})

(defn validate-multipart-params
  "Validates that the multipart parameters includes only the expected keys."
  [expected-keys-set multipart-params]
  (let [provided-keys (set (keys multipart-params))]
    (when (not= expected-keys-set provided-keys)
      (srvc-errors/throw-service-error
        :bad-request
        (msg/invalid-multipart-params expected-keys-set provided-keys)))))

(defmethod validate-granule :multipart-params
  [provider-id native-id {:keys [multipart-params request-context]}]
  (validate-multipart-params #{"granule" "collection"} multipart-params)

  (let [coll-concept (multipart-param->concept
                       provider-id native-id :collection (get multipart-params "collection"))
        gran-concept (multipart-param->concept
                       provider-id native-id :granule (get multipart-params "granule"))]
    (info "Validating granule" (concept->loggable-string gran-concept) "with collection"
          (concept->loggable-string coll-concept))
    (ingest/validate-granule-with-parent-collection request-context gran-concept coll-concept)))

(defn- build-routes [system]
  (routes
    (context (:relative-root-url system) []
      provider-api/provider-api-routes
      (context "/providers/:provider-id" [provider-id]
        (context ["/validate/collection/:native-id" :native-id #".*$"] [native-id]
          (POST "/" {:keys [body content-type headers request-context]}
            (let [concept (body->concept :collection provider-id native-id body content-type headers)]
              (info "Validating Collection" (concept->loggable-string concept))
              (ingest/validate-concept request-context concept)
              {:status 200})))

        (context ["/collections/:native-id" :native-id #".*$"] [native-id]
          (PUT "/" {:keys [body content-type headers request-context params]}
            (let [context (acl/add-authentication-to-context request-context params headers)]
              (acl/verify-ingest-management-permission
                context :update :provider-object provider-id)
              (let [concept (body->concept :collection provider-id native-id body content-type headers)]
                (info "Ingesting collection" (concept->loggable-string concept))
                (r/response (ingest/save-concept request-context concept)))))
          (DELETE "/" {:keys [request-context params headers]}
            (let [concept-attribs {:provider-id provider-id
                                   :native-id native-id
                                   :concept-type :collection}
                  context (acl/add-authentication-to-context request-context params headers)]
              (acl/verify-ingest-management-permission
                context :update :provider-object provider-id)
              (info "Deleting collection" (pr-str concept-attribs))
              (r/response (ingest/delete-concept request-context concept-attribs)))))

        (context ["/validate/granule/:native-id" :native-id #".*$"] [native-id]
          (POST "/" request
            (validate-granule provider-id native-id request)
            {:status 200}))

        (context ["/granules/:native-id" :native-id #".*$"] [native-id]
          (PUT "/" {:keys [body content-type headers request-context params]}
            (let [context (acl/add-authentication-to-context request-context params headers)]
              (acl/verify-ingest-management-permission
                context :update :provider-object provider-id)
              (let [concept (body->concept :granule provider-id native-id body content-type headers)]
                (info "Ingesting granule" (concept->loggable-string concept))
                (r/response (ingest/save-concept request-context concept)))))
          (DELETE "/" {:keys [request-context params headers]}
            (let [concept-attribs {:provider-id provider-id
                                   :native-id native-id
                                   :concept-type :granule}
                  context (acl/add-authentication-to-context request-context params headers)]
              (acl/verify-ingest-management-permission
                context :update :provider-object provider-id)
              (info "Deleting granule" (pr-str concept-attribs))
              (r/response (ingest/delete-concept request-context concept-attribs))))))

      ;; Add routes for API documentation
      (api-docs/docs-routes (:relative-root-url system) "public/ingest_index.html")

      ;; add routes for managing jobs
      (common-routes/job-api-routes
        (routes
          (POST "/reindex-collection-permitted-groups" {:keys [headers params request-context]}
            (let [context (acl/add-authentication-to-context request-context params headers)]
              (acl/verify-ingest-management-permission context :update)
              (jobs/reindex-collection-permitted-groups context)
              {:status 200}))
          (POST "/reindex-all-collections" {:keys [headers params request-context]}
            (let [context (acl/add-authentication-to-context request-context params headers)]
              (acl/verify-ingest-management-permission context :update)
              (jobs/reindex-all-collections context)
              {:status 200}))
          (POST "/cleanup-expired-collections" {:keys [headers params request-context]}
            (let [context (acl/add-authentication-to-context request-context params headers)]
              (acl/verify-ingest-management-permission context :update)
              (jobs/cleanup-expired-collections request-context)
              {:status 200}))))

      ;; add routes for accessing caches
      common-routes/cache-api-routes

      ;; add routes for checking health of the application
      (common-routes/health-api-routes ingest/health))

    (route/not-found "Not Found")))

(defn make-api [system]
  (-> (build-routes system)
      (http-trace/build-request-context-handler system)
      handler/site
      api-errors/invalid-url-encoding-handler
      mp/wrap-multipart-params
      ring-json/wrap-json-body
      ring-json/wrap-json-response
      api-errors/exception-handler))

