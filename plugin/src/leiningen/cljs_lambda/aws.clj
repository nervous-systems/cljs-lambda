(ns leiningen.cljs-lambda.aws
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.set :as set]
            [cheshire.core :as json])
  (:import [java.io File]))

(defn lambda-cli! [cmd longopts &
                   [{:keys [fatal positional] :or {fatal true}}]]
  (let [args (flatten
              (for [[k v] (set/rename-keys longopts {:name :function-name})]
                [(str "--" (name k))
                 (if (keyword? v) (name v) (str v))]))
        args (cond->> args positional (into positional))]
    (apply println "aws lambda" (name cmd) args)
    (let [{:keys [exit err] :as r}
          (apply shell/sh "aws" "lambda" (name cmd) args)]
      (if (and fatal (not (zero? exit)))
        (leiningen.core.main/abort err)
        r))))

(defn create-function! [fn-spec zip-path]
  (lambda-cli!
   :create-function
   (-> fn-spec
       (select-keys #{:name :role :handler})
       (assoc :runtime "nodejs" :zip-file zip-path))))

(defn function-exists? [fn-name]
  (-> (lambda-cli! :get-function {:function-name fn-name} {:fatal false})
      :exit
      zero?))

(defn update-function-config! [fn-spec]
  (lambda-cli!
   :update-function-configuration
   (select-keys fn-spec
                #{:name :role :handler :description
                  :timeout :memory-size})))

(defn update-function-code! [{:keys [name]} zip-path]
  (lambda-cli!
   :update-function-code
   {:name name :zip-file zip-path}))

(defn deploy-function! [zip-path {fn-name :name create :create :as fn-spec}]
  (cond (function-exists? fn-name) (update-function-code! fn-spec zip-path)
        create (create-function! fn-spec zip-path)
        :else (leiningen.core.main/abort
               "Function" fn-name "doesn't exist & :create not set"))

  (update-function-config! fn-spec))

(defn deploy! [zip-path {:keys [functions] :as cljs-lambda}]
  (doseq [{:keys [name handler] :as fn-spec} functions]
    (println "Registering handler" handler "for function" name)
    (deploy-function! (str "fileb://" zip-path) fn-spec)))

(defn update-configs! [{:keys [functions] :as cljs-lambda}]
  (doseq [{fn-name :name :as fn-spec} functions]
    (when-not (function-exists? fn-name)
      (leiningen.core.main/abort fn-name "doesn't exist & can't create"))
    (update-function-config! fn-spec)))

(defn invoke! [fn-name payload]
  (let [out-file (File/createTempFile "lambda-output" ".json")
        out-path (.getAbsolutePath out-file)]
    (lambda-cli!
     :invoke
     {:function-name fn-name :payload payload}
     {:positional [out-path]})

    (let [output (slurp out-path)]
      (clojure.pprint/pprint
       (try
         (json/parse-string output true)
         (catch Exception e
           [:not-json output]))))))
