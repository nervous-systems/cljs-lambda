(ns leiningen.cljs-lambda.aws
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.set :as set]
            [clojure.string :as str]
            [base64-clj.core :as base64])
  (:import [java.io File]))

(defn abs-path [^File f] (.getAbsolutePath f))

(defn merge-global-opts
  [{:keys [aws-profile region] :as global-opts} command-opts]
  (cond-> command-opts
    region      (assoc :region  (name region))
    aws-profile (assoc :profile (name aws-profile))))

(defn ->cli-args [m global-opts & [positional]]
  (let [m    (merge-global-opts global-opts m)
        args (flatten
              (for [[k v] (set/rename-keys m {:name :function-name})]
                [(str "--" (name k))
                 (if (keyword? v) (name v) (str v))]))]
    (cond->> args positional (into positional))))

(defn aws-cli! [service cmd args & [{:keys [fatal?] :or {fatal? true}}]]
  (apply println "aws" service (name cmd) args)
  (let [{:keys [exit err] :as r}
        (apply shell/sh "aws" service (name cmd) args)]
    (if (and fatal? (not (zero? exit)))
      (leiningen.core.main/abort err)
      r)))

(def lambda-cli! (partial aws-cli! "lambda"))

(defn create-function! [fn-spec zip-path global-opts]
  (lambda-cli!
   :create-function
   (-> fn-spec
       (select-keys #{:name :role :handler})
       (assoc :runtime "nodejs" :zip-file zip-path)
       (->cli-args global-opts))))

(defn function-exists? [fn-name global-opts]
  (-> (lambda-cli!
       :get-function
       (->cli-args {:name fn-name} global-opts)
       {:fatal? false})
      :exit
      zero?))

(defn update-function-config! [fn-spec global-opts]
  (lambda-cli!
   :update-function-configuration
   (-> fn-spec
       (select-keys #{:name :role :handler :description :timeout :memory-size})
       (->cli-args global-opts))))

(defn update-function-code! [{:keys [name]} zip-path global-opts]
  (lambda-cli!
   :update-function-code
   (->cli-args {:name name :zip-file zip-path} global-opts)))

(defn deploy-function!
  [zip-path {fn-name :name create :create :as fn-spec} global-opts]
  (cond (function-exists? fn-name global-opts)
        (update-function-code! fn-spec zip-path global-opts)
        create (create-function! fn-spec zip-path global-opts)
        :else (leiningen.core.main/abort
               "Function" fn-name "doesn't exist & :create not set"))
  (update-function-config! fn-spec global-opts))

(defn fn-global-opts [fn-region {:keys [global-aws-opts keyword-args]}]
  ;; This is awkward - we want to override the global region with the
  ;; function's :region, unless the region came from the command line
  (cond-> global-aws-opts
    (and fn-region (not (:region keyword-args))) (assoc :region fn-region)))

(defn deploy!
  [zip-path {:keys [functions keyword-args global-aws-opts] :as cljs-lambda}]
  (doseq [{:keys [name handler region] :as fn-spec} functions]
    (let [global-aws-opts (fn-global-opts region cljs-lambda)]
      (println "Registering handler" handler "for function" name)
      (deploy-function!
       (str "fileb://" zip-path)
       fn-spec
       global-aws-opts))))

(defn update-configs!
  [{:keys [functions aws-profile global-aws-opts] :as cljs-lambda}]
  (doseq [{fn-name :name region :region :as fn-spec} functions]
    (let [global-aws-opts (fn-global-opts region cljs-lambda)]
     (when-not (function-exists? fn-name global-aws-opts)
       (leiningen.core.main/abort fn-name "doesn't exist & can't create"))
     (update-function-config! fn-spec global-aws-opts))))

(let [invoke-kwargs {:log-type "Tail" :query "LogResult" :output "text"}]
 (defn invoke! [fn-name payload global-opts]
   (let [out-file    (File/createTempFile "lambda-output" ".json")
         out-path    (abs-path out-file)
         {logs :out} (lambda-cli!
                      :invoke
                      (->cli-args
                       (assoc invoke-kwargs :name fn-name :payload payload)
                       global-opts
                       [out-path]))]
     (println (base64/decode (str/trim logs)))
     (let [output (slurp out-path)]
       (clojure.pprint/pprint
        (try
          (json/parse-string output true)
          (catch Exception e
            [:not-json output])))))))

(defn get-role-arn! [role-name global-opts]
  (let [{:keys [exit out]}
        (aws-cli!
         "iam" "get-role"
         (->cli-args
          {:role-name role-name :output "text" :query "Role.Arn"}
          global-opts)
         {:fatal? false})]
    (when (zero? exit)
      (str/trim out))))

(defn assume-role-policy-doc! [role-name file-path global-opts]
  (-> (aws-cli!
       "iam" "create-role"
       (->cli-args
        {:role-name role-name
         :assume-role-policy-document (str "file://" file-path)
         :output "text"
         :query "Role.Arn"}
        global-opts))
      :out
      str/trim))

(defn put-role-policy! [role-name file-path global-opts]
  (aws-cli!
   "iam" "put-role-policy"
   (->cli-args
    {:role-name role-name
     :policy-name role-name
     :policy-document (str "file://" file-path)}
    global-opts)))

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
