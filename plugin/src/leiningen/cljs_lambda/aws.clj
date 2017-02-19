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
            [clojure.string :as string])
  (:import [java.io File]
           [java.util.concurrent Executors]))

(defn abs-path [^File f] (.getAbsolutePath f))

(defmacro with-meta-config [config & body]
  `(let [{aws-profile# :aws-profile region# :region} ~config]
     (binding [args/*aws-profile* (or aws-profile# args/*aws-profile*)
               args/*region*      (or region#      args/*region*)]
       ~@body)))

(defn meta-config []
  (cond-> {}
    args/*region*      (assoc :region  (name args/*region*))
    args/*aws-profile* (assoc :profile (name args/*aws-profile*))))

(defmulti ->cli-arg-value
  (fn [k v] k))

(defmethod ->cli-arg-value :vpc-config [k v]
  (let [subnets (:subnets v)
        security-groups (:security-groups v)]
    (string/join
      ["SubnetIds=["
       (string/join "," subnets)
       "],SecurityGroupIds=["
       (string/join "," security-groups)
       "]"])))

(defmethod ->cli-arg-value :environment [k v]
  (str
    "Variables={"
    (string/join
      ","
      (for [[k v] v]
        (str (name k) "=" v)))
    "}"))

(defmethod ->cli-arg-value :dead-letter-config [k v]
  (str "TargetArn=" v))

(defmethod ->cli-arg-value :default [k v]
  (if (keyword? v) (name v) (str v)))

(defn ->cli-arg [k v]
  [(str "--" (name k))
   (->cli-arg-value k v)])

(defn ->cli-args [m & [positional {:keys [preserve-names?]}]]
  (let [m    (cond-> (merge (meta-config) m)
               (not preserve-names?)
               (set/rename-keys {:name :function-name :vpc :vpc-config :dead-letter :dead-letter-config :env :environment}))
        args (flatten
               (for [[k v] m]
                 (->cli-arg k v)))]
    (cond->> args positional (into positional))))

(defn aws-cli! [service cmd args & [{:keys [fatal?] :or {fatal? true}}]]
  (apply log :verbose "aws" service (name cmd) args)
  (let [{:keys [exit err] :as r}
        (apply shell/sh "aws" service (name cmd) args)]
    (if (and fatal? (not (zero? exit)))
      (leiningen.core.main/abort err)
      r)))

(def lambda-cli! (partial aws-cli! "lambda"))

(def fn-config-args
  #{:name :role :handler :description :timeout :memory-size :runtime :vpc :dead-letter :env})

(def fn-spec-defaults
  {:vpc {:subnets [] :security-groups []} :dead-letter "" :env {}})

(def create-function-args
  (into fn-config-args
    #{:zip-file :output :query}))

(def update-function-code-args
  (remove #{:vpc :dead-letter :env} create-function-args))

(defn fn-spec->cli-args [fn-args {:keys [publish] :as fn-spec}]
  (let [args (merge {:output "text" :query "Version"} fn-spec)]
    (-> args
        (select-keys fn-args)
        (->cli-args (cond-> [] publish (conj "--publish"))))))

(defn update-alias! [alias-name fn-name version]
  (lambda-cli!
    :update-alias
    (->cli-args
      {:function-name fn-name
       :name alias-name
       :function-version version}
      nil
      {:preserve-names? true})))

(defn create-alias! [alias-name fn-name version]
  (lambda-cli!
   :create-alias
   (->cli-args
    {:function-name fn-name
     :name alias-name
     :function-version version}
    nil
    {:preserve-names? true})
   {:fatal? false}))

(def default-runtime "nodejs4.3")

(defn- create-function! [fn-spec zip-path]
  (let [args (fn-spec->cli-args
               create-function-args
               (merge {:runtime default-runtime} fn-spec {:zip-file zip-path}))]
    (-> (lambda-cli! :create-function args) :out str/trim)))

(defn- update-function-config! [fn-spec]
  (lambda-cli!
   :update-function-configuration
   (-> (merge fn-spec-defaults fn-spec)
       (select-keys fn-config-args)
       ->cli-args)))

(defn- update-function-code!
  [{:keys [name publish] :as fn-spec} zip-path]
  (let [args (merge (fn-spec->cli-args
                      update-function-code-args
                      {:name name :zip-file zip-path :publish publish}))]
    (-> (lambda-cli! :update-function-code args)
        :out
        str/trim)))

(defn- get-function-configuration! [{:keys [name]}]
  (let [{:keys [exit out]} (lambda-cli!
                            :get-function-configuration
                            (->cli-args {:name name})
                            {:fatal? false})]
    (case exit
      255 nil
      0   (json/parse-string out))))

(defn normalize-config [config]
  (-> config
      (update-in [:vpc :subnets] sort)
      (update-in [:vpc :security-groups] sort)
      (update-in [:env] clojure.walk/stringify-keys)))

(defn remote-config->local-config [remote]
  (let [remote (set/rename-keys remote {"FunctionName" :name
                                        "VpcConfig" :vpc
                                        "DeadLetterConfig" :dead-letter
                                        "Environment" :env
                                        "Description" :description
                                        "Timeout" :timeout
                                        "Handler" :handler
                                        "Runtime" :runtime
                                        "MemorySize" :memory-size
                                        "Version" :version
                                        "Role" :role})]
    (merge
      remote
      (if-let [vpc (:vpc remote)]
        (assoc remote :vpc
          (-> vpc
              (select-keys #{"SubnetIds" "SecurityGroupIds"})
              (set/rename-keys {"SubnetIds" :subnets "SecurityGroupIds" :security-groups}))))
      {:dead-letter (get-in remote [:dead-letter "TargetArn"] "")}
      {:env (-> (get-in remote [:env "Variables"] {}))})))

(defn same-config? [remote local]
  (let [remote (-> remote remote-config->local-config normalize-config)
        local  (-> (merge fn-spec-defaults local) (select-keys fn-config-args) normalize-config)]
    (= (select-keys remote (keys local)) local)))

(defn- deploy-function!
  [zip-path {fn-name :name create? :create :as fn-spec}]
  (if-let [remote-config (get-function-configuration! fn-spec)]
    (do
      (when-not (same-config? remote-config fn-spec)
        (update-function-config! fn-spec))
      (update-function-code! fn-spec zip-path))
    (if create?
      (create-function! fn-spec zip-path)
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
   (fn [{fn-alias :alias fn-name :name :as fn-spec}]
     (with-meta-config fn-spec
       (let [version (deploy-function! (str "fileb://" zip-path) fn-spec)]
         (if fn-alias
           (let [{:keys [exit err] :as r} (create-alias! fn-alias fn-name version)]
             (when-not (zero? exit)
               (if (.contains err "ResourceConflictException")
                 (update-alias! fn-alias fn-name version)
                 (leiningen.core.main/abort err))))
           (when version
             (println version))))))
   cljs-lambda))

(defn update-config! [{fn-name :name :as fn-spec}]
  (with-meta-config fn-spec
    (if-let [remote-config (get-function-configuration! fn-spec)]
      (when-not (same-config? remote-config fn-spec)
        (update-function-config! fn-spec))
      (leiningen.core.main/abort fn-name "doesn't exist & can't create"))))

(defn update-configs! [cljs-lambda]
  (do-functions! update-config! cljs-lambda))

(defn invoke! [fn-name payload {:keys [keyword-args] :as cljs-lambda}]
  (let [out-file    (File/createTempFile "lambda-output" ".json")
        out-path    (abs-path out-file)
        qualifier   (some keyword-args [:qualifier :version])
        optional    (cond-> {} qualifier (assoc :qualifier qualifier))
        args        (-> {:function-name fn-name
                         :payload payload
                         :log-type "Tail"
                         :query "LogResult"
                         :output "text"}
                        (merge optional)
                        (->cli-args [out-path]))
        {logs :out} (lambda-cli! :invoke args)]
    (log :verbose (base64/decode (str/trim logs)))
    (let [output (slurp out-path)]
      (pprint/pprint
       (try
         (json/parse-string output true)
         (catch Exception e
           [:not-json output]))))))

(defn- get-role-arn! [role-name]
  (let [args (->cli-args {:role-name role-name :output "text" :query "Role.Arn"})
        {:keys [exit out]}
        (aws-cli! "iam" "get-role" args {:fatal? false})]
    (when (zero? exit)
      (str/trim out))))

(defn- assume-role-policy-doc! [role-name file-path]
  (-> (aws-cli!
       "iam" "create-role"
       (->cli-args
        {:role-name role-name
         :assume-role-policy-document (str "file://" file-path)
         :output "text"
         :query "Role.Arn"}))
      :out
      str/trim))

(defn- put-role-policy! [role-name file-path]
  (let [args (->cli-args
              {:role-name role-name
               :policy-name role-name
               :policy-document (str "file://" file-path)})]
    (aws-cli! "iam" "put-role-policy" args)))

(defn install-iam-role! [role-name role policy]
  (if-let [role-arn (get-role-arn! role-name)]
    role-arn
    (let [role-tmp-file   (File/createTempFile "iam-role" nil)
          policy-tmp-file (File/createTempFile "iam-policy" nil)]
      (spit role-tmp-file role)
      (spit policy-tmp-file policy)
      (let [role-arn (assume-role-policy-doc! role-name (abs-path role-tmp-file))]
        (put-role-policy! role-name (abs-path policy-tmp-file))
        (.delete role-tmp-file)
        (.delete policy-tmp-file)
        role-arn))))
