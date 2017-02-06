(ns cljs-lambda.aws.event
  "Utility functionality for converting AWS event inputs & outputs to/from EDN.

  - `from-aws` handles `:aws.event/type` values of `:api-gateway`,
  `:notification` (SNS & S3), and `:scheduled`.
  - `to-aws` can handle `:api-gateway`."
  (:require [camel-snake-kebab.core :as csk]
            [camel-snake-kebab.extras :as csk.extras]
            [clojure.set :as set]
            [clojure.string :as str]))

(defmulti from-aws
  "Interpret input map `event` as an AWS event input.  The map's
  `:aws.event/type` key will be used to inform transformations."
  :aws.event/type)

(def ^:private ag-renames
  {:queryStringParameters :query
   :statusCode            :status
   :httpMethod            :method
   :resourcePath          :path
   :isBase64Encoded       :base64?
   :requestContext        :context})

(defn- apply-keys [m f]
  (csk.extras/transform-keys f m))

(defn- lower-case-k [k]
  (-> k name str/lower-case keyword))

(defn- unmethod [s]
  (some-> s csk/->kebab-case-keyword))

(defn- rekey [k renames]
  (if-let [k (renames k)]
    k
    (cond-> k (not (namespace k)) csk/->kebab-case-keyword)))

(defn- unfuck
  ([m]
   (unfuck m #{} {}))
  ([m ignore? renames]
   (persistent!
    (reduce-kv
     (fn [m k v]
       (let [k (rekey k renames)]
         (if (nil? v)
           m
           (assoc! m
             k (cond (ignore? k) v
                     (map?    v) (unfuck v ignore? renames)
                     :else       v)))))
     (transient {})
     m))))

(defmethod from-aws :api-gateway [m]
  (-> m
      (unfuck #{:headers :query} ag-renames)
      (update :method unmethod)
      (update :headers apply-keys lower-case-k)
      (update-in [:context :method] unmethod)))

(defn- source->key [s & [{delim :delim :or {delim ":"}}]]
  (when s
    (if-let [i (str/index-of s delim)]
      (keyword (subs s 0 i) (subs s (inc i)))
      (keyword s))))

(defmulti ^:no-doc notification->cljs :source)

(defn- sns-attrs->cljs [m]
  (into {}
    (for [[k attr] m]
      [k {:type  (csk/->kebab-case-keyword (attr :Type))
          :value (attr :Value)}])))

(defmethod notification->cljs :aws/sns [{sns :Sns :as m}]
  (let [sns (-> (unfuck sns #{:attrs} {:MessageAttributes :attrs
                                       :TopicArn          :topic
                                       :MessageId         :id})
                (update :type csk/->kebab-case-keyword)
                (update :attrs sns-attrs->cljs))]
    (-> m
        (dissoc :Sns)
        (assoc :sns sns))))

(defn- update-when [m k f]
  (cond-> m
    (not (nil? (m k))) (update k f)))

(let [renames {:responseElements  :response
               :requestParameters :request
               :userIdentity      :user
               :eTag              :etag
               :s3                :s3
               :s3SchemaVersion   :s3-schema-version}]
  (defmethod notification->cljs :aws/s3 [m]
    (-> m
        (unfuck #{} renames)
        (update-when :region keyword))))

(let [renames {:EventSource          :source
               :eventSource          :source
               :awsRegion            :region
               :EventVersion         :version
               :EventSubscriptionArn :subscription}]
  (defmethod from-aws :notification [{records :Records}]
    {:records (into []
                (for [record records]
                  (-> record
                      (set/rename-keys renames)
                      (update :source source->key)
                      notification->cljs)))
     :aws.event/type :notification}))

(defmethod from-aws :scheduled [m]
  (-> m
      (update :source source->key {:delim "."})
      (update :region keyword)))

(defmulti to-aws*
  "Interpret input map `event` as an AWS event output.  The map's
  `:aws.event/type` key will be used to inform transformations."
  :aws.event/type)

(let [ag-renames (set/map-invert ag-renames)]
  (defmethod to-aws* :api-gateway [m]
    (set/rename-keys m ag-renames)))

(defn to-aws
  "Inverse of [[from-aws]], for response/output events.  Defers
  to [[to-aws*]], and removes `:aws.event/type`, on the assumption that the
  returned map will be passed to AWS."
  [{:keys [aws.event/type] :as event}]
  (-> (to-aws* event)
      (dissoc :aws.event/type)))
