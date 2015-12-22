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

(defn- generate-index [{:keys [optimizations] :as compiler-opts} fns]
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
    (with-open [w (io/writer file)]
      (.write w s))
    (.getPath file)))

(def default-defaults {:create true})

(defn- augment-project
  [{{:keys [defaults functions cljs-build-id aws-profile]} :cljs-lambda
    :as project}]
  (let [{:keys [builds]} (cljsbuild.config/extract-options project)
        [build] (if-not cljs-build-id
                  builds
                  (filter #(= (:id %) cljs-build-id) builds))]
    (if-not build
      (leiningen.core.main/abort "Can't find cljsbuild build")
      (-> project
          (assoc-in [:cljs-lambda :cljs-build] build)
          (assoc-in [:cljs-lambda :global-aws-opts :aws-profile] aws-profile)
          (assoc-in [:cljs-lambda :functions]
                    (map (fn [m]
                           (assoc
                            (merge default-defaults defaults m)
                            :handler (str "index." (export-name (:invoke m)))))
                         functions))))))

(defn build
  "Write a zip file suitable for Lambda deployment"
  [{{:keys [cljs-build cljs-build-id functions resource-dirs]} :cljs-lambda
    :as project}]
  (npm/npm project "install")
  (cljsbuild/cljsbuild project "once" (:id cljs-build))
  (let [{{:keys [output-dir optimizations] :as compiler} :compiler} cljs-build
        project-name (-> project :name name)
        index-path   (->> functions
                          (generate-index compiler)
                          (write-index output-dir))]
    (write-zip
     compiler
     {:project-name project-name
      :index-path index-path
      :resource-dirs resource-dirs
      :zip-name (str project-name ".zip")})))

(defn deploy
  "Build & deploy a zip file to Lambda, exposing the specified functions"
  [{:keys [cljsbuild cljs-lambda] :as project}]
  (let [zip-path (build project)
        {{:keys [output-dir output-to]} :compiler} cljsbuild]
    (aws/deploy! zip-path cljs-lambda)))

(defn update-config
  "Write function configs from project.clj to Lambda"
  [{:keys [cljs-lambda] :as project}]
  (aws/update-configs! cljs-lambda))

(defn invoke
  "Invoke the named Lambda function"
  [{{:keys [global-aws-opts]} :cljs-lambda} fn-name & [payload]]
  (aws/invoke! fn-name payload global-aws-opts))

(defn default-iam-role
  "Install a Lambda-compatible IAM role, and stick it in project.clj"
  [{{:keys [global-aws-opts]} :cljs-lambda :as project}]
  (let [arn (aws/install-iam-role!
             :cljs-lambda-default
             (slurp (io/resource "default-iam-role.json"))
             (slurp (io/resource "default-iam-policy.json"))
             global-aws-opts)]
    (println "Using role" arn)
    (change/change project [:cljs-lambda :defaults]
                   (fn [m & _]
                     (assoc m :role arn)))))

(defn cljs-lambda
  "Build & deploy AWS Lambda functions"
  {:help-arglists '([build deploy update-config invoke default-iam-role])
   :subtasks [#'build #'deploy #'update-config #'invoke #'default-iam-role]}

  ([project] (println (leiningen.help/help-for cljs-lambda)))

  ([project subtask & args]
   (if-let [subtask-fn ({"build"  build
                         "deploy" deploy
                         "invoke" invoke
                         "default-iam-role" default-iam-role
                         "update-config"    update-config} subtask)]
     (apply subtask-fn (augment-project project) args)
     (println (leiningen.help/help-for cljs-lambda)))))
