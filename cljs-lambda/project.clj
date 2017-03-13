(defproject io.nervous/cljs-lambda "0.3.5"
  :description "Clojurescript AWS Lambda utilities"
  :url         "https://github.com/nervous-systems/cljs-lambda"
  :license     {:name "Unlicense" :url "http://unlicense.org/UNLICENSE"}
  :scm         {:name "git" :url "https://github.com/nervous-systems/cljs-lambda"}
  :dependencies [[org.clojure/clojure            "1.8.0"]
                 [org.clojure/clojurescript      "1.8.51"]
                 [org.clojure/core.async         "0.2.395"]
                 [org.clojure/tools.macro        "0.1.2"]
                 [camel-snake-kebab              "0.4.0"]
                 [funcool/promesa                "1.6.0"]
                 [io.nervous/cljs-nodejs-externs "0.2.0"]]
  :plugins [[lein-doo       "0.1.7"]
            [lein-npm       "0.6.2"]
            [lein-cljsbuild "1.1.4"]
            [lein-codox     "0.10.2"]]
  ;; Codox can't deal w/ deps.cljs, so isolate externs
  :source-paths ["src" "src-deps"]
  :profiles     {:codox {:source-paths ^:replace ["src"]}
                 :dev   {:dependencies [[io.nervous/codox-nervous-theme "0.1.0"]]}}
  :cljsbuild
  {:builds [{:id "test"
             :source-paths ["src" "test"]
             :compiler {:output-to     "target/test/cljs-lambda.js"
                        :output-dir    "target/test"
                        :target        :nodejs
                        :optimizations :none
                        :main          cljs-lambda.test.runner}}]}
  :codox
  {:metadata   {:doc/format :markdown}
   :themes     [:default [:nervous {:nervous/github "https://github.com/nervous-systems/cljs-lambda/"}]]
   :language   :clojurescript
   :source-uri ~(str "https://github.com/nervous-systems/cljs-lambda/"
                     "blob/master/cljs-lambda/{filepath}#L{line}")})
