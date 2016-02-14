(ns leiningen.cljs-lambda
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [leiningen.cljs-lambda.zip-tedium :refer [write-zip]]
            [leiningen.cljs-lambda.aws :as aws]
            [leiningen.npm :as npm]
            [leiningen.cljsbuild :as cljsbuild]
            [leiningen.change :as change]
            [leiningen.cljsbuild.config :as cljsbuild.config]
            [clostache.parser])
  (:import [java.io File]))

(defn- export-name [sym]
  (str/replace (munge sym) #"\." "_"))

(defn- generate-index [{optimizations :optimizations :as compiler-opts} fns]
  (let [template (slurp (io/resource
                         (if (= optimizations :advanced)
                           "index-advanced.mustache"
                           "index.mustache")))]
    (clostache.parser/render
     template
     (assoc compiler-opts
       :module
       (for [[ns fns] (group-by namespace (map :invoke fns))]
         {:name (munge ns)
          :function (for [f fns]
                      ;; This is Clojure's munge, which isn't always going to be right
                      {:export  (export-name f)
                       :js-name (str (munge ns) "." (munge (name f)))})})))))

(defn- write-index [output-dir s]
  (let [file (io/file output-dir "index.js")]
    (println "Writing index to" (.getAbsolutePath file))
    (spit f s)
    (.getPath file)))

(defn- extract-build [project]
  (if-let [build (->> project
                      cljsbuild.config/extract-options
                      :builds
                      (filter #(or (not id) (= id (:id %))))
                      first)]
    build
    (leiningen.core.main/abort "Can't find cljsbuild build")))

(let [default-defaults {:create true}]
  (defn- augment-fn [{defaults :defaults} cli-kws fn-spec]
    (assoc
      (merge default-defaults defaults fn-spec cli-kws)
      :handler (str "index." (export-name (:invoke fn-spec))))))

(defn- augment-fns [cljs-lambda keep-fns cli-kws]
  (let [keep-fns (into #{} keep-fns)
        fn-pred  (if (empty? keep-fns)
                   (constantly true)
                   #(keep-fns (:name %)))]
    (->> cljs-lambda
         :functions
         (filter fn-pred)
         (map #(augment-fn cljs-lambda cli-kws %)))))

(defn- augment-project
  [{cljs-lambda :cljs-lambda :as project} function-names cli-kws]
  (let [build (extract-build project)
        global-opts (select-keys
                     (merge cljs-lambda cli-kws)
                     #{:region :aws-profile})]
    (assoc project
      :cljs-lambda
      (assoc cljs-lambda
        :cljs-build build
        :global-aws-opts global-opts
        :positional-args function-names
        :keyword-args cli-kws
        :functions    (augment-fns cljs-lambda function-names cli-kws)))))

(let [coercions
      {:create  #(Boolean/parseBoolean %)
       :timeout #(Integer/parseInt %)
       :memory-size #(Integer/parseInt %)}]
  (defn- split-args [l]
    (loop [positional [] kw {} [k & l] l]
      (cond
        (not k) [positional kw]
        (not= \: (first k)) (recur (conj positional k) kw l)
        :else
        (let [[v & l] l
              k      (keyword (subs k 1))
              coerce (coercions k identity)]
          (recur positional (assoc kw k (coerce v)) l))))))

(defn build
  "Write a zip file suitable for Lambda deployment"
  [{{:keys [cljs-build functions resource-dirs]} :cljs-lambda :as project}]
  (npm/npm project "install")
  (cljsbuild/cljsbuild project "once" (:id cljs-build))

  (let [{compiler :compiler} cljs-build
        project-name (name (:name project))
        index-path   (->> functions
                          (generate-index compiler)
                          (write-index (compiler :output-dir)))]
    (write-zip
     compiler
     {:project-name  project-name
      :index-path    index-path
      :resource-dirs resource-dirs
      :zip-name      (str project-name ".zip")})))

(defn deploy
  "Build & deploy a zip file to Lambda, exposing the specified functions"
  [{cljs-lambda :cljs-lambda :as project}]
  (aws/deploy! (build project) cljs-lambda))

(defn update-config
  "Write function configs from project.clj to Lambda"
  [{cljs-lambda :cljs-lambda}]
  (aws/update-configs! cljs-lambda))

(defn invoke
  "Invoke the named Lambda function"
  [{{:keys [global-aws-opts positional-args]} :cljs-lambda}]
  (let [[fn-name payload] positional-args]
    (aws/invoke! fn-name payload global-aws-opts)))

(defn default-iam-role
  "Install a Lambda-compatible IAM role, and stick it in project.clj"
  [{{global-aws-opts :global-aws-opts} :cljs-lambda :as project}]
  (let [arn (aws/install-iam-role!
             :cljs-lambda-default
             (slurp (io/resource "default-iam-role.json"))
             (slurp (io/resource "default-iam-policy.json"))
             global-aws-opts)]
    (println "Using role" arn)
    (change/change
     project
     [:cljs-lambda :defaults]
     (fn [m & _] (assoc m :role arn)))))

(let [subtask->fn {"build"  build
                   "deploy" deploy
                   "invoke" invoke
                   "default-iam-role" default-iam-role
                   "update-config"    update-config}]
  (defn cljs-lambda
    "Build & deploy AWS Lambda functions"
    {:help-arglists '([build deploy update-config invoke default-iam-role])
     :subtasks [#'build #'deploy #'update-config #'invoke #'default-iam-role]}

    ([project] (println (leiningen.help/help-for cljs-lambda)))

    ([project subtask & args]
     (if-let [subtask-fn (subtask->fn subtask)]
       (let [project (apply augment-project project (split-args args))]
         (subtask-fn project))
       (println (leiningen.help/help-for cljs-lambda))))))
