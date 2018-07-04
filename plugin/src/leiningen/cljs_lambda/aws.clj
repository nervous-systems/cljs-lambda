(ns leiningen.cljs-lambda.aws
  (:require [leiningen.cljs-lambda.logging :as logging :refer [log]]
            [leiningen.cljs-lambda.args :as args]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.set :as set]
            [clojure.string :as str]
            [base64-clj.core :as base64]
            [clojure.pprint :as pprint]
            [clojure.string :as string]
            [clojure.data :refer [diff]]
            [clojure.walk :as walk])
  (:import [java.io File RandomAccessFile]
           [java.nio ByteBuffer]
           [java.util.concurrent Executors]
           [com.amazonaws.services.lambda
            AWSLambdaClient
            AWSLambdaClientBuilder]
           [com.amazonaws.services.lambda.model
            GetFunctionConfigurationRequest
            UpdateFunctionConfigurationRequest
            FunctionCode
            LogType
            UpdateFunctionCodeRequest
            CreateFunctionRequest
            ResourceNotFoundException
            ResourceConflictException]
           [com.amazonaws.auth.profile
            ProfileCredentialsProvider]
           [com.amazonaws.services.identitymanagement
            AmazonIdentityManagementClientBuilder]
           [com.amazonaws.services.identitymanagement.model
            GetRoleRequest
            PutRolePolicyRequest
            CreateRoleRequest
            NoSuchEntityException]))

(defn abs-path [^File f] (.getAbsolutePath f))

(defn- build-client [builder config]
  (cond-> builder
    (config :region)      (.withRegion (name (config :region)))
    (config :aws-profile) (.withCredentials (ProfileCredentialsProvider.
                                             (name (config :aws-profile))))
    true                  .build))

(def ->client
  (memoize
   (fn [config]
     (-> (AWSLambdaClientBuilder/standard)
         (build-client config)))))

(defn update-alias! [spec]
  (let [req (-> (com.amazonaws.services.lambda.model.UpdateAliasRequest.)
                (.withFunctionName    (name (spec :name)))
                (.withName            (name (spec :alias)))
                (.withFunctionVersion (spec ::version)))]
    (.update (->client spec) req)))

(defn create-alias! [spec]
  (let [req (-> (com.amazonaws.services.lambda.model.CreateAliasRequest.)
                (.withFunctionName    (name (spec :name)))
                (.withName            (name (spec :alias)))
                (.withFunctionVersion (spec ::version)))]
    (try
      (.createAlias (->client spec) req)
      true
      (catch ResourceConflictException _
        nil))))

(defn- ->vpc-config [fn-spec]
  (-> (com.amazonaws.services.lambda.model.VpcConfig.)
      (.withSubnetIds      (-> fn-spec :vpc :subnets))
      (.withSecurityGroups (-> fn-spec :vpc :security-groups))))

(defn- ->tracing-config [fn-spec]
  (-> (com.amazonaws.services.lambda.model.TracingConfig.)
      (.withMode ({"passthrough" "PassThrough"
                   "active"      "Active"} (fn-spec :tracing)))))

(defn- ->env [fn-spec]
  (-> (com.amazonaws.services.lambda.model.Environment.)
      (.withVariables (fn-spec :env))))

(defn- build-config-req [req fn-spec]
  (-> req
      (.withFunctionName (fn-spec :name))
      (.withHandler      (fn-spec :handler))
      (.withMemorySize   (int (fn-spec :memory-size)))
      (.withDescription  (fn-spec :description))
      (.withRole         (fn-spec :role))
      (.withRuntime      (fn-spec :runtime))
      (.withTimeout      (int (fn-spec :timeout)))
      (.withKMSKeyArn    (fn-spec :kms-key))
      (cond-> (fn-spec :vpc)     (.withVpcConfig     (->vpc-config     fn-spec))
              (fn-spec :tracing) (.withTracingConfig (->tracing-config fn-spec))
              (fn-spec :env)     (.withEnvironment   (->env            fn-spec)))))

(defn- zip-path->buffer [path]
  (let [zip-f   (RandomAccessFile. path "r")
        zip     (byte-array (.length zip-f))]
    (.readFully zip-f zip)
    (ByteBuffer/wrap zip)))

(defn- tidy-value [x]
  (cond (keyword? x) (name x)
        (symbol?  x) (name x)
        (map?     x) x
        (coll?    x) (sort x)
        :else        x))

(let [implicit-keys {:tracing "passthrough" :memory-size 128 :timeout 3}]
  (defn- default-config [x]
    (merge implicit-keys x)))

(defn- tidy-config [m & []]
  (let [m (walk/postwalk
           (fn [form]
             (if (map? form)
               (into {}
                 (for [[k v] form :when (and (not (nil? v))
                                             (or (not (coll? v))
                                                 (not-empty v)))]
                   [k (tidy-value v)]))
               form))
           m)]
    (cond-> m
      (m :env) (update :env walk/stringify-keys))))

(defn- create-function! [fn-spec]
  (let [fn-spec (tidy-config (default-config fn-spec))
        req     (-> (build-config-req (CreateFunctionRequest.) fn-spec)
                    (.withCode (-> (FunctionCode.)
                                   (.withZipFile (zip-path->buffer (fn-spec ::zip-path))))))]
    (-> (.createFunction (->client fn-spec) req)
        .getVersion)))

(defn- update-function-config! [fn-spec]
  (let [fn-spec (tidy-config (default-config fn-spec))
        req     (build-config-req (UpdateFunctionConfigurationRequest.) fn-spec)]
    (.updateFunctionConfiguration (->client fn-spec) req)))

(defn- update-function-code! [fn-spec]
  (let [fn-spec (tidy-config fn-spec)]
    (let [req (-> (UpdateFunctionCodeRequest.)
                  (.withFunctionName (fn-spec :name))
                  (.withPublish      (boolean (fn-spec :publish)))
                  (.withZipFile      (zip-path->buffer (fn-spec ::zip-path))))]
      (-> (.updateFunctionCode (->client fn-spec) req)
          .getVersion))))

(defn- fn-config->map [c]
  (tidy-config
   {:name        (.getFunctionName c)
    :env         (some->> c .getEnvironment .getVariables (into {}))
    :tracing     (some->  c .getTracingConfig .getMode str/lower-case)
    :dead-letter (some->  c .getDeadLetterConfig .getTargetArn)
    :vpc         (when-let [vpc (.getVpcConfig c)]
                   {:subnets          (into [] (.getSubnetIds vpc))
                    :security-groups  (into [] (.getSecurityGroupIds vpc))})
    :kms-key     (.getKMSKeyArn   c)
    :memory-size (.getMemorySize  c)
    :description (not-empty (.getDescription c))
    :role        (.getRole        c)
    :runtime     (.getRuntime     c)
    :handler     (.getHandler     c)
    :timeout     (.getTimeout     c)}))

(defn- get-function-configuration! [f]
  (let [req (-> (GetFunctionConfigurationRequest.)
                (.withFunctionName (name (f :name))))]
    (try
      (-> (.getFunctionConfiguration (->client f) req)
          fn-config->map)
      (catch ResourceNotFoundException e
        nil))))

(let [ks #{:env :tracing :dead-letter :vpc :kms-key :memory-size :description
           :role :runtime :handler :timeout}]
 (defn same-config? [remote local]
   (let [local  (-> local default-config tidy-config (select-keys ks))
         remote (select-keys remote ks)]
     (println "Remote" remote)
     (println "Local" local)
     (or (= local (select-keys remote ks))
         (do
           (log :verbose "Config diff:" (take 2 (diff local remote)))
           false)))))

(defn- deploy-function!
  [{fn-name :name create? :create :as fn-spec}]
  (if-let [remote-config (get-function-configuration! fn-spec)]
    (do
      (when-not (same-config? remote-config fn-spec)
        (update-function-config! fn-spec))
      (update-function-code! fn-spec))
    (if create?
      (create-function! fn-spec)
      (leiningen.core.main/abort
       "Function" fn-name "doesn't exist & :create not set"))))

(defn- do-functions! [process! {:keys [functions] :as cljs-lambda}]
  (when (not-empty functions)
    (let [parallel (-> cljs-lambda :meta-config :parallel)
          service  (Executors/newFixedThreadPool parallel)
          bindings (get-thread-bindings)
          futures  (.invokeAll
                    service
                    (for [f functions]
                      #(with-bindings* bindings process! f)))]
      (.shutdown service)
      (doseq [f futures]
        (.get f)))))

(defn deploy! [zip-path cljs-lambda]
  (do-functions!
   (fn [fn-spec]
     (let [version (deploy-function! (assoc fn-spec ::zip-path zip-path))
           fn-spec (assoc fn-spec ::version version)]
       (if (fn-spec :alias)
         (when-not (create-alias! fn-spec)
           (update-alias! fn-spec))
         (println version))))
   cljs-lambda))

(defn update-config! [{fn-name :name :as fn-spec}]
  (if-let [remote-config (get-function-configuration! fn-spec)]
    (when-not (same-config? remote-config fn-spec)
      (update-function-config! fn-spec))
    (leiningen.core.main/abort fn-name "doesn't exist & can't create")))

(defn update-configs! [cljs-lambda]
  (do-functions! update-config! cljs-lambda))

(defn invoke! [fn-name payload {ks :keyword-args :as cljs-lambda}]
  (let [qual (some ks [:qualifier :version])
        req  (-> (com.amazonaws.services.lambda.model.InvokeRequest.)
                 (.withFunctionName (name fn-name))
                 (.withLogType      LogType/Tail)
                 (.withPayload      payload)
                 (cond-> qual (.withQualifier (name qual))))
        resp (.invoke (->client cljs-lambda) req)
        logs (-> resp .getLogResult str/trim base64/decode)
        body (-> resp .getPayload .array (String. "UTF-8"))]
    (log :verbose logs)
    (pprint/pprint
     (try
       (json/parse-string body true)
       (catch Exception _
         [:not-json body])))))

(defn- get-role-arn! [config]
  (let [req (-> (GetRoleRequest.)
                (.withRoleName (name (config :role-name))))]
    (try
      (-> (.getRole (config ::client) req) .getRole .getArn)
      (catch NoSuchEntityException _
        nil))))

(defn- assume-role-policy-doc! [config]
  (let [req  (-> (CreateRoleRequest.)
                 (.withRoleName                 (name (config :role-name)))
                 (.withAssumeRolePolicyDocument (config :role)))
        resp (.createRole (config ::client) req)]
    (-> resp .getRole .getArn)))

(defn- put-role-policy! [config]
  (let [req (-> (PutRolePolicyRequest.)
                (.withPolicyDocument (config :policy))
                (.withPolicyName     (name (config :role-name)))
                (.withRoleName       (name (config :role-name))))]
    (.putRolePolicy (config ::client) req)))

(defn install-iam-role! [config]
  (let [client (build-client (AmazonIdentityManagementClientBuilder/standard) config)
        config (assoc config ::client client)]
    (if-let [role-arn (get-role-arn! config)]
      role-arn
      (let [role-arn (assume-role-policy-doc! config)]
        (put-role-policy! config)
        role-arn))))
