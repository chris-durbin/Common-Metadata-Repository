(ns cmr.ingest.api.ingest
  "Defines the HTTP URL routes for validating and ingesting concepts."
  (:require [compojure.core :refer :all]
            [clojure.string :as str]
            [clojure.data.xml :as x]
            [cmr.common.mime-types :as mt]
            [cmr.common.log :refer (debug info warn error)]
            [cmr.common.services.errors :as srvc-errors]
            [cmr.common.mime-types :as mt]
            [cmr.acl.core :as acl]
            [cmr.ingest.services.ingest-service :as ingest]
            [cmr.ingest.services.messages :as msg]
            [cmr.ingest.services.providers-cache :as pc])
  (:import clojure.lang.ExceptionInfo))

(def ECHO_CLIENT_ID "ECHO")
(def VIRTUAL_PRODUCT_CLIENT_ID "Virtual-Product-Service")

(defn- verify-provider-cmr-only-against-client-id
  "Verifies provider CMR-ONLY flag matches the client-id in the request.
  Throws bad request error if the client-id is Echo when the provider is CMR-ONLY
  or the client id is not Echo when the provider is not CMR-ONLY."
  [provider-id cmr-only client-id]
  (when (nil? cmr-only)
    (srvc-errors/internal-error!
      (format "CMR Only should not be nil, but is for Provider %s." provider-id)))
  (when (or (and cmr-only (= ECHO_CLIENT_ID client-id))
            (and (not cmr-only) (not= ECHO_CLIENT_ID client-id)))
    (let [msg (if cmr-only
                (format
                  (str "Provider %s was configured as CMR Only which only allows ingest directly "
                       "through the CMR. It appears from the client id that it was sent from ECHO.")
                  provider-id)
                (format
                  (str "Provider %s was configured as false for CMR Only which only allows "
                       "ingest indirectly through ECHO. It appears from the client id [%s] "
                       "that ingest was not sent from ECHO.")
                  provider-id client-id))]
      (srvc-errors/throw-service-error :bad-request msg))))

(defn verify-provider-against-client-id
  "Verifies the given provider's CMR-ONLY flag matches the client-id in the request."
  [context provider-id]
  (if-let [provider (->> (pc/get-providers-from-cache context)
                         (some #(when (= provider-id (:provider-id %)) %)))]
    (let [client-id (:client-id context)]
      ;; We don't check the cmr-only flag if the ingest request is coming from a virtual product
      ;; service. The occurrence of this ingest event implies that source granule is successfully
      ;; ingested which in turn implies that the verification succeeded with the provider and
      ;; the client-id used for ingesting source granule and we don't need to verify again for
      ;; virtual granule which belongs to the same provider as source granule.
      (when (not (= VIRTUAL_PRODUCT_CLIENT_ID client-id))
        (verify-provider-cmr-only-against-client-id
          provider-id (:cmr-only provider) client-id)))
    (srvc-errors/throw-service-error
      :bad-request (format "Provider with provider-id [%s] does not exist." provider-id))))

(def valid-response-mime-types
  "Supported ingest response formats"
  #{mt/any mt/xml mt/json})

(def content-type-mime-type->response-format
  "A map of mime-types to supported response format"
  {mt/echo10 :xml
   mt/iso :xml
   mt/iso-smap :xml
   mt/dif :xml
   mt/dif10 :xml
   mt/xml :xml
   mt/json :json})

(defn- result-map->xml
  "Converts all keys in a map to tags with values given by the map values to form a trivial
  xml document.
  Example:
  (result-map->xml {:concept-id \"C1-PROV1\", :revision-id 1} true)

  <?xml version=\"1.0\" encoding=\"UTF-8\"?>
  <result>
  <revision-id>1</revision-id>
  <concept-id>C1-PROV1</concept-id>
  </result>"
  [m]
  (x/emit-str
    (x/element :result {}
               (reduce-kv (fn [memo k v]
                            (conj memo (x/element (keyword k) {} v)))
                          []
                          m))))

(defn- get-ingest-result-format
  "Returns the requested ingest result format parsed from the Accept header or :xml
  if no Accept header is given"
  ([headers default-format]
   (get-ingest-result-format
     headers (set (keys content-type-mime-type->response-format)) default-format))
  ([headers valid-mime-types default-format]
   (get content-type-mime-type->response-format
        (mt/extract-header-mime-type valid-response-mime-types headers "accept" true)
        default-format)))

(defmulti generate-ingest-response
  "Convert a result to a proper response format"
  (fn [headers result]
    (get-ingest-result-format headers :xml)))

(defmethod generate-ingest-response :json
  [headers result]
  ;; ring-json middleware will handle converting the body to json
  {:status 200
   :headers {"Content-Type" (mt/format->mime-type :json)}
   :body result})

(defmethod generate-ingest-response :xml
  [headers result]
  {:status 200
   :headers {"Content-Type" (mt/format->mime-type :xml)}
   :body (result-map->xml result)})

(defn- invalid-revision-id-error
  "Throw an error saying that revision is invalid"
  [revision-id]
  (srvc-errors/throw-service-error
    :bad-request
    (msg/invalid-revision-id revision-id)))

(defn- parse-validate-revision-id
  "Parse revision id and return it if it is positive"
  [revision-id]
  (try
    (let [revision-id (Integer/parseInt revision-id)]
      (when (pos? revision-id)
        revision-id))
    (catch NumberFormatException _)))

(defn- set-revision-id
  "Associate revision id to concept if revision id is a positive integer. Otherwise return an error"
  [concept headers]
  (if-let [revision-id (get headers "cmr-revision-id")]
    (if-let [revision-id (parse-validate-revision-id revision-id)]
      (assoc concept :revision-id revision-id)
      (invalid-revision-id-error revision-id))
    concept))

(defn- set-concept-id
  "Set concept-id and revision-id for the given concept based on the headers. Ignore the
  revision-id if no concept-id header is passed in."
  [concept headers]
  ;; The header concept-id exists primarily to support backwards compatibility with Catalog Rest
  (if-let [concept-id (or (get headers "cmr-concept-id") (get headers "concept-id"))]
    (assoc concept :concept-id concept-id)
    concept))

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
        (set-concept-id headers)
        (set-revision-id headers))))

(defn- concept->loggable-string
  "Returns a string with information about the concept as a loggable string."
  [concept]
  (pr-str (dissoc concept :metadata)))

(defmulti validate-granule
  "Validates the granule in the request. It can handle a granule and collection sent as multipart-params
  or a normal request with the XML as the body."
  (fn [context provider-id native-id request]
    (if (seq (:multipart-params request))
      :multipart-params
      :default)))

(defmethod validate-granule :default
  [context provider-id native-id {:keys [body content-type headers request-context] :as request}]
  (let [concept (body->concept :granule provider-id native-id body content-type headers)]
    (info (format "Validating granule %s from client %s"
                  (concept->loggable-string concept) (:client-id context)))
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
  [context provider-id native-id {:keys [multipart-params request-context]}]
  (validate-multipart-params #{"granule" "collection"} multipart-params)

  (let [coll-concept (multipart-param->concept
                       provider-id native-id :collection (get multipart-params "collection"))
        gran-concept (multipart-param->concept
                       provider-id native-id :granule (get multipart-params "granule"))]
    (ingest/validate-granule-with-parent-collection request-context gran-concept coll-concept)))

(defn set-default-error-format [default-response-format handler]
  "Ring middleware to add a default format to the exception-info created during exceptions. This
  is used to determine the default format for each route."
  (fn [request]
    (try
      (handler request)
      (catch ExceptionInfo e
        (let [{:keys[type errors]} (ex-data e)]
          (throw (ex-info (.getMessage e)
                          {:type type
                           :errors errors
                           :default-format default-response-format})))))))
(def ingest-routes
  "Defines the routes for ingest, validate, and delete operations"
  (set-default-error-format
    :xml
    (context "/providers/:provider-id" [provider-id]

      (context ["/validate/collection/:native-id" :native-id #".*$"] [native-id]

        (POST "/" {:keys [body content-type params headers request-context]}
          (let [concept (body->concept :collection provider-id native-id body content-type headers)]
            (verify-provider-against-client-id request-context provider-id)
            (info (format "Validating Collection %s from client %s"
                          (concept->loggable-string concept) (:client-id request-context)))
            (ingest/validate-collection request-context concept)
            {:status 200})))
      (context ["/collections/:native-id" :native-id #".*$"] [native-id]
        (PUT "/" {:keys [body content-type headers request-context params]}
          (verify-provider-against-client-id request-context provider-id)
          (acl/verify-ingest-management-permission request-context :update :provider-object provider-id)
          (let [concept (body->concept :collection provider-id native-id body content-type headers)]
            (info (format "Ingesting collection %s from client %s"
                          (concept->loggable-string concept) (:client-id request-context)))
            (generate-ingest-response headers (ingest/save-collection request-context concept))))
        (DELETE "/" {:keys [request-context params headers]}
          (let [concept-attribs (set-revision-id
                                  {:provider-id provider-id
                                   :native-id native-id
                                   :concept-type :collection}
                                  headers)]
            (verify-provider-against-client-id request-context provider-id)
            (acl/verify-ingest-management-permission request-context :update :provider-object provider-id)
            (info (format "Deleting collection %s from client %s"
                          (pr-str concept-attribs) (:client-id request-context)))
            (generate-ingest-response headers (ingest/delete-concept request-context concept-attribs)))))

      (context ["/validate/granule/:native-id" :native-id #".*$"] [native-id]
        (POST "/" {:keys [params headers request-context] :as request}
          (verify-provider-against-client-id request-context provider-id)
          (validate-granule request-context provider-id native-id request)
          {:status 200}))

      (context ["/granules/:native-id" :native-id #".*$"] [native-id]
        (PUT "/" {:keys [body content-type headers request-context params]}
          (verify-provider-against-client-id request-context provider-id)
          (acl/verify-ingest-management-permission request-context :update :provider-object provider-id)
          (let [concept (body->concept :granule provider-id native-id body content-type headers)]
            (info (format "Ingesting granule %s from client %s"
                          (concept->loggable-string concept) (:client-id request-context)))
            (generate-ingest-response headers (ingest/save-granule request-context concept))))
        (DELETE "/" {:keys [request-context params headers]}
          (let [concept-attribs (set-revision-id
                                  {:provider-id provider-id
                                   :native-id native-id
                                   :concept-type :granule}
                                  headers)]
            (verify-provider-against-client-id request-context provider-id)
            (acl/verify-ingest-management-permission request-context :update :provider-object provider-id)
            (info (format "Deleting granule %s from client %s"
                          (pr-str concept-attribs) (:client-id request-context)))
            (generate-ingest-response headers (ingest/delete-concept request-context concept-attribs))))))))


