(ns leiningen.cljs-lambda.aws
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.set :as set]
            [clojure.string :as str]
            [base64-clj.core :as base64])
  (:import [java.io File]))

(defn abs-path [^File f] (.getAbsolutePath f))

(defn merge-global-opts [{:keys [aws-profile] :as global-opts} command-opts]
  (cond-> command-opts aws-profile (assoc :profile (name aws-profile))))

(defn aws-cli! [service cmd longopts &
                [{:keys [fatal positional] :or {fatal true}
                  :as global-opts}]]
  (let [longopts (merge-global-opts global-opts longopts)
        args (flatten
              (for [[k v] (set/rename-keys longopts {:name :function-name})]
                [(str "--" (name k))
                 (if (keyword? v) (name v) (str v))]))
        args (cond->> args positional (into positional))]
    (apply println "aws" service (name cmd) args)
    (let [{:keys [exit err] :as r}
          (apply shell/sh "aws" service (name cmd) args)]
      (if (and fatal (not (zero? exit)))
        (leiningen.core.main/abort err)
        r))))

(def lambda-cli! (partial aws-cli! "lambda"))

(defn create-function! [fn-spec zip-path global-opts]
  (lambda-cli!
   :create-function
   (-> fn-spec
       (select-keys #{:name :role :handler})
       (assoc :runtime "nodejs" :zip-file zip-path))
   global-opts))

(defn function-exists? [fn-name global-opts]
  (-> (lambda-cli! :get-function
                   {:function-name fn-name}
                   (assoc global-opts :fatal false))
      :exit
      zero?))

(defn update-function-config! [fn-spec global-opts]
  (lambda-cli!
   :update-function-configuration
   (select-keys fn-spec
                #{:name :role :handler :description
                  :timeout :memory-size})
   global-opts))

(defn update-function-code! [{:keys [name]} zip-path global-opts]
  (lambda-cli!
   :update-function-code
   {:name name :zip-file zip-path}
   global-opts))

(defn deploy-function!
  [zip-path {fn-name :name create :create :as fn-spec} global-opts]
  (cond (function-exists? fn-name global-opts)
        (update-function-code! fn-spec zip-path global-opts)
        create (create-function! fn-spec zip-path global-opts)
        :else (leiningen.core.main/abort
               "Function" fn-name "doesn't exist & :create not set"))

  (update-function-config! fn-spec global-opts))

(defn deploy!
  [zip-path {:keys [functions aws-profile global-aws-opts] :as cljs-lambda} & [fns]]
  (let [functions (filter (fn [{:keys [name]}]
                            (or (empty? fns) (fns name))) functions)]
    (doseq [{:keys [name handler] :as fn-spec} functions]
      (println "Registering handler" handler "for function" name)
      (deploy-function!
       (str "fileb://" zip-path)
       fn-spec
       global-aws-opts))))

(defn update-configs!
  [{:keys [functions aws-profile global-aws-opts] :as cljs-lambda}]
  (doseq [{fn-name :name :as fn-spec} functions]
    (when-not (function-exists? fn-name global-aws-opts)
      (leiningen.core.main/abort fn-name "doesn't exist & can't create"))
    (update-function-config! fn-spec global-aws-opts)))

(defn invoke! [fn-name payload global-opts]
  (let [out-file (File/createTempFile "lambda-output" ".json")
        out-path (abs-path out-file)
        {logs :out} (lambda-cli!
                     :invoke
                     {:function-name fn-name
                      :payload payload
                      :log-type "Tail"
                      :query "LogResult"
                      :output "text"}
                     (assoc global-opts :positional [out-path]))]
    (println (base64/decode (str/trim logs)))
    (let [output (slurp out-path)]
      (clojure.pprint/pprint
       (try
         (json/parse-string output true)
         (catch Exception e
           [:not-json output]))))))

(defn get-role-arn! [role-name global-opts]
  (let [{:keys [exit out]}
        (aws-cli!
         "iam" "get-role"
         {:role-name role-name
          :output "text"
          :query "Role.Arn"}
         (assoc global-opts :fatal false))]
    (when (zero? exit)
      (str/trim out))))

(defn assume-role-policy-doc! [role-name file-path global-opts]
  (-> (aws-cli!
       "iam" "create-role"
       {:role-name role-name
        :assume-role-policy-document
        (str "file://" file-path)
        :output "text"
        :query "Role.Arn"}
       global-opts)
      :out
      str/trim))

(defn put-role-policy! [role-name file-path global-opts]
  (aws-cli!
   "iam" "put-role-policy"
   {:role-name role-name
    :policy-name role-name
    :policy-document (str "file://" file-path)}
   global-opts))

(defn install-iam-role! [role-name role policy global-opts]
  (if-let [role-arn (get-role-arn! role-name global-opts)]
    role-arn
    (let [role-tmp-file   (File/createTempFile "iam-role" nil)
          policy-tmp-file (File/createTempFile "iam-policy" nil)]
      (spit role-tmp-file role)
      (spit policy-tmp-file policy)
      (let [role-arn (assume-role-policy-doc!
                      role-name (abs-path role-tmp-file) global-opts)]
        (put-role-policy! role-name (abs-path policy-tmp-file) global-opts)
        (.delete role-tmp-file)
        (.delete policy-tmp-file)
        role-arn))))
