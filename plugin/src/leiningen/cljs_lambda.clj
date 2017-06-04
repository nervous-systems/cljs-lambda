(ns leiningen.cljs-lambda
  (:require [clojure.java.io     :as io]
            [clojure.string      :as str]
            [cheshire.core :as json]
            [leiningen.core.main :as main]
            [leiningen.core.eval :as eval]
            [leiningen.cljs-lambda.zip-tedium :refer [write-zip]]
            [leiningen.cljs-lambda.aws     :as aws]
            [leiningen.cljs-lambda.logging :as logging :refer [log]]
            [leiningen.cljs-lambda.args    :as args]
            [leiningen.npm       :as npm]
            [leiningen.cljsbuild :as cljsbuild]
            [leiningen.change    :as change]
            [leiningen.cljsbuild.config :as cljsbuild.config]
            [clostache.parser])
  (:import [java.io File]))

(defn- export-name [sym]
  (str/replace (munge sym) #"\." "_"))

(defn- fns->module-template [fns]
  (for [[ns fns] (group-by namespace (map :invoke fns))]
    {:name (munge ns)
     :function
     (for [f fns]
       ;; This is Clojure's munge, which isn't always going to be right
       {:export  (export-name f)
        :js-name (str (munge ns) "." (munge (name f)))})}))

(defn- generate-index [env {:keys [optimizations source-map] :as compiler-opts} fns]
  (let [template (slurp (io/resource
                         (str "index-" (name optimizations) ".mustache")))]
    (clostache.parser/render
     template
     (assoc compiler-opts
       :source-map (when source-map true)
       :module     (fns->module-template fns)
       :env        (for [[k v] env]
                     {:key k :value v})))))

(defn- write-index [output-dir s]
  (let [file (io/file output-dir "index.js")]
    (log :verbose "Writing index to" (.getAbsolutePath file))
    (with-open [w (io/writer file)]
      (.write w s))
    (.getPath file)))

(def default-defaults {:create true :runtime "nodejs4.3"})

(defn- extract-build [{:keys [cljs-lambda] :as proj}]
  (when-not (:compiler cljs-lambda)
    (let [cljs-build-id    (:cljs-build-id cljs-lambda)
          {:keys [builds]} (cljsbuild.config/extract-options proj)
          [build] (if-not cljs-build-id
                    builds
                    (filter #(= (:id %) cljs-build-id) builds))]
      (cond (not build)   (leiningen.core.main/abort "Can't find cljsbuild build")
            (build :main) (leiningen.core.main/abort "Can't deploy build w/ :main")
            :else         build))))

(defn- extract-compile-opts [proj]
  {:post [(or (nil? %) (and (coll? (:inputs %)) (map? (:options %))))]}
  (some-> proj
    :cljs-lambda
    :compiler
    (update :options (fn [{:keys [optimizations] :as options}]
                       (if (= :advanced optimizations)
                         (merge {:output-wrapper true} options)
                         options)))))

(def fn-keys
  #{:name :create :region :memory-size :role :invoke :description :timeout
    :publish :alias :runtime})

(defn- augment-fn [{:keys [defaults]} cli-kws fn-spec]
  (merge default-defaults
         defaults
         fn-spec
         (select-keys cli-kws fn-keys)
         {:handler (str "index." (export-name (:invoke fn-spec)))}))

(defn- verify-fn-args [{:keys [functions]} {:keys [alias publish]}]
  (when alias
    (when-not publish
      (leiningen.core.main/abort "Can't alias unpublished function"))))

(defn- augment-fns [cljs-lambda keep-fns cli-kws]
  (let [keep-fns (into #{} keep-fns)
        fn-pred  (if (empty? keep-fns)
                   (constantly true)
                   #(keep-fns (:name %)))]
    (verify-fn-args cljs-lambda cli-kws)
    (->> cljs-lambda
         :functions
         (filter fn-pred)
         (map #(augment-fn cljs-lambda cli-kws %)))))

(def meta-defaults {:parallel 5})

(defn- augment-project
  [{:keys [cljs-lambda] :as project} function-names cli-kws]
  (let [build       (extract-build project)
        cljsc       (extract-compile-opts project)
        meta-config (select-keys
                     (merge meta-defaults cljs-lambda cli-kws)
                     #{:region :aws-profile :parallel})
        opts        (assoc cljs-lambda
                      :meta-config     meta-config
                      :positional-args function-names
                      :keyword-args    cli-kws
                      :functions       (augment-fns cljs-lambda function-names cli-kws))]
    (assert (and (or build cljsc) (not (and build cljsc))))
    (assoc project
      :cljs-lambda
      (cond-> opts
        build (assoc :cljs-build build :compiler (build :compiler))
        cljsc (assoc :cljsc cljsc :compiler (cljsc :options))))))

(defn- ->string-matcher [x]
  (cond
    (or (symbol? x) (string? x) (keyword? x)) #(= (name x) %)
    (instance? java.util.regex.Pattern x)     #(re-find x %)))

(defn capture-env [{capture :capture set-vars :set}]
  (let [capture? (if (not-empty capture)
                   (apply some-fn (map ->string-matcher capture))
                   (constantly false))
        env      (filter (comp capture? key) (System/getenv))]
    (merge (into {} env)
           (into {}
             (for [[k v] set-vars]
               [(name k) v])))))

(defn- compile-cljs [proj]
  (if-let [opts (-> proj :cljs-lambda :cljsc)]
    (do
      (log :verbose "Invoking Clojurescript compiler w/ inputs " (opts :inputs))
      (eval/eval-in-project
       proj
       `(cljs.build.api/build (apply cljs.build.api/inputs ~(opts :inputs)) ~(opts :options))
       `(require '[cljs.build.api])))
    (cljsbuild/cljsbuild proj "once" (-> proj :cljs-lambda :cljs-build :id))))

(defn build
  "Write a zip file suitable for Lambda deployment"
  [{{:keys [functions resource-dirs env] :as opts} :cljs-lambda
    :as project}]
  (if (or (opts :managed-deps) (-> opts :keyword-args :managed-deps))
    (log :verbose "Note: You set the :managed-deps options to true, so
    dependencies won't be handled automatically.")
    (if (.exists (io/as-file "package.json"))
      (main/abort "Your project already has a project.json file. Please remove
      it and use :npm {:dependencies [...]} option instead, or set :manage-deps
      to true and manage your dependencies manually.")
      (log :verbose
        (with-out-str
          (npm/npm project "install")))))
  (with-out-str
    (compile-cljs project))
  (let [project-name (-> project :name name)
        index-path   (->> functions
                          (generate-index (capture-env env) (opts :compiler))
                          (write-index (-> opts :compiler :output-dir)))]
    (write-zip
     (opts :compiler)
     {:project-name  project-name
      :index-path    index-path
      :resource-dirs resource-dirs
      :zip-name      (str project-name ".zip")
      :force-path    (-> opts :keyword-args :output)
      :print-files   (-> opts :keyword-args :print-files)})))

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

(defn dump-config
  "Dump effective function configs"
  [{{:keys [keyword-args] :as cljs-lambda} :cljs-lambda}]
  (-> cljs-lambda
      (select-keys [:functions])
      (json/generate-string (select-keys keyword-args [:pretty]))
      println))

(defn invoke
  "Invoke the named Lambda function"
  [{{:keys [positional-args] :as cljs-lambda} :cljs-lambda}]
  (let [[fn-name payload] positional-args]
    (aws/invoke! fn-name payload cljs-lambda)))

(defn default-iam-role
  "Install a Lambda-compatible IAM role, and stick it in project.clj"
  [{:keys [:cljs-lambda] :as project}]
  (let [arn (aws/install-iam-role!
             :cljs-lambda-default
             (slurp (io/resource "default-iam-role.json"))
             (slurp (io/resource "default-iam-policy.json")))]
    (println arn)
    (change/change project [:cljs-lambda :defaults]
                   (fn [m & _] (assoc m :role arn)))))

(defn create-alias
  "Create a remote alias for the given function/version"
  [project]
  (apply aws/create-alias! (-> project :cljs-lambda :positional-args)))

(def task->fn
  {"alias"  create-alias
   "build"  build
   "deploy" deploy
   "invoke" invoke
   "default-iam-role" default-iam-role
   "update-config"    update-config
   "dump-config"      dump-config})

(defn cljs-lambda
  "Build & deploy AWS Lambda functions"
  {:help-arglists '([alias build deploy update-config dump-config invoke default-iam-role])
   :subtasks [#'create-alias #'build #'deploy #'update-config #'dump-config #'invoke #'default-iam-role]}

  ([project] (println (leiningen.help/help-for cljs-lambda)))

  ([project subtask & args]
   (if-let [subtask-fn (task->fn subtask)]
     (let [[pos kw]    (args/split-args args #{:publish :quiet :managed-deps :print-files :pretty})
           [kw quiet]  [(dissoc kw :quiet) (kw :quiet)]
           project     (augment-project project pos kw)
           meta-config (-> project :cljs-lambda :meta-config)]
       (binding [args/*region*       (meta-config :region)
                 args/*aws-profile*  (meta-config :aws-profile)
                 logging/*log-level* (if quiet :error :verbose)]
         (subtask-fn project)))
     (println (leiningen.help/help-for cljs-lambda)))))
