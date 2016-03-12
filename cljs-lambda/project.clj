(defproject io.nervous/cljs-lambda "0.3.0-SNAPSHOT"
  :description "Clojurescript AWS Lambda utilities"
  :url "https://github.com/nervous-systems/cljs-lambda"
  :license {:name "Unlicense" :url "http://unlicense.org/UNLICENSE"}
  :scm {:name "git" :url "https://github.com/nervous-systems/cljs-lambda"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/clojurescript "1.7.228"]
                 [org.clojure/core.async "0.2.374"]]
  :plugins [[lein-doo       "0.1.7-SNAPSHOT"]
            [lein-npm       "0.6.0"]
            [lein-cljsbuild "1.1.2"]]
  :cljsbuild
  {:builds [{:id "test"
             :source-paths ["src" "test"]
             :compiler {:output-to  "target/test/cljs-lambda.js"
                        :output-dir "target/test"
                        :target :nodejs
                        :optimizations :none
                        :main cljs-lambda.test.runner}}]})
