(ns cmr.search.services.parameters.converters.spatial
  "Contains parameter converters for spatial parameters"
  (:require [cmr.common.log :refer [debug error info warn]]
            [cmr.common-app.services.search.params :as p]
            [cmr.search.models.query :as qm]
            [cmr.common-app.services.search.group-query-conditions :as gc]
            [cmr.spatial.codec :as spatial-codec]
            [cmr.common.services.errors :as errors]
            [clojure.string :as str]))

(defn url-value->spatial-condition
  [type value]
  (debug "*** Andy url-value->spatial-condition")
  (debug "*** Andy type:" type)
  (debug "*** Andy value:" value)
  (debug "*** Andy ------------------")
  (let [shape (spatial-codec/url-decode type value)]
    (when-let [errors (:errors shape)]
      (errors/internal-error!
        (format "Shape format was invalid [%s]. Issues should have be handled in validation."
                (str/join ", " errors))))
    (qm/->SpatialCondition shape)))

(defn url-value->spatial-conditions
  [type value]
  ;; Note: value can be a single string or a vector of strings. (flatten [value])
  ;; converts the value to a sequence of strings irrespective of the type
  (debug "*** Andy url-value->spatial-conditions")
  (debug "*** Andy type:" type)
  (debug "*** Andy value:" value)
  (debug "*** Andy ------------------")
  (gc/and-conds (map (partial url-value->spatial-condition type) (flatten [value]))))

(defmethod p/parameter->condition :polygon
  [_context concept-type param value options]
  (debug "*** Andy p/parameter->condition :polygon")
  (debug "*** Andy :polygon:" :polygon)
  (debug "*** Andy value:" value)
  (debug "*** Andy ------------------")
  (url-value->spatial-conditions :polygon value))

(defmethod p/parameter->condition :bounding-box
  [_context concept-type param value options]
  (url-value->spatial-conditions :bounding-box value))

(defmethod p/parameter->condition :point
  [_context concept-type param value options]
  (url-value->spatial-conditions :point value))

(defmethod p/parameter->condition :line
  [_context concept-type param value options]
  (url-value->spatial-conditions :line value))

(defmethod p/parameter->condition :wkt
  [_context concept-type param value options]
  (debug "*** Andy p/parameter->condition :wkt")
  (debug "*** Andy :wkt:" :wkt)
  (debug "*** Andy value:" value)
  (def keyw (first value))
  (debug "*** Andy keyw:" keyw)
  (def p1 (str/trim (re-find #".+?(?=[(])" keyw)))
  (def p1 (str/lower-case p1))
  (def v1 (subs (first (re-find #"([(](.+?)[)])" keyw) ) 1) )
  (def v1 (str/replace v1 #"[()]" ""))
  (def v1 (str/replace v1 #"[,]" " "))
  (def v1 (str/trim v1))
  (def v1 (str/replace v1 #"[ ]{2,}" " "))
  (def v1 (str/replace v1 #"[ ]" ","))
  (def v1 [v1])
  (debug "*** Andy p1:" p1)
  (debug "*** Andy type p1:" (type p1))
  (debug "*** Andy v1:" v1)
  (debug "*** Andy type v1:" (type v1))
  (debug "*** Andy ------------------")
  (case p1
    "polygon"     (url-value->spatial-conditions :polygon v1)
    "point"       (url-value->spatial-conditions :point v1)
    "linestring"  (url-value->spatial-conditions :line v1)
    "default")
  )

