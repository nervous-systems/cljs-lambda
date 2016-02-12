(defproject example "0.1.0-SNAPSHOT"
  :description "FIXME"
  :url "http://please.FIXME"
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/clojurescript "1.7.228"]
                 [org.clojure/core.async "0.2.374"]
                 [io.nervous/cljs-lambda "0.2.0"]
                 [io.nervous/cljs-nodejs-externs "0.2.0"]]
  :plugins [[lein-cljsbuild "1.1.2"]
            [lein-npm "0.6.0"]
            [lein-doo "0.1.7-SNAPSHOT"]
            [io.nervous/lein-cljs-lambda "0.3.1-SNAPSHOT"]]
  :npm {:dependencies [[source-map-support "0.2.8"]]}
  :source-paths ["src"]
  :cljs-lambda
  {:defaults {:role "FIXME"}
   :resource-dirs ["static"]
   :functions
   [{:name   "work-magic"
     :invoke example.core/work-magic}]}
  :cljsbuild
  {:builds [{:id "example"
             :source-paths ["src"]
             :compiler {:output-to "target/example/example.js"
                        :output-dir "target/example"
                        :target :nodejs
                        :optimizations :advanced}}
            {:id "example-test"
             :source-paths ["src" "test"]
             :compiler {:output-to "target/example-test/example.js"
                        :output-dir "target/example-test"
                        :target :nodejs
                        :optimizations :none
                        :main example.test-runner}}]})
