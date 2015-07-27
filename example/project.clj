(defproject io.nervous/cljs-lambda-example "0.1.0-SNAPSHOT"
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/clojurescript "0.0-3308"]
                 [io.nervous/cljs-lambda "0.1.2"]]
  :plugins [[lein-cljsbuild "1.0.6"]
            [lein-npm "0.5.0"]
            [io.nervous/lein-cljs-lambda "0.2.1"]]
  :node-dependencies [[source-map-support "0.2.8"]]
  :source-paths ["src"]
  :cljs-lambda
  {:cljs-build-id "cljs-lambda-example"
   :defaults
   {:role   "arn:aws:iam::151963828411:role/lambda_basic_execution"
    :create true}
   :functions
   [{:name   "dog-bark"
     :invoke cljs-lambda-example.dog/bark}
    {:name   "cat-meow"
     :invoke cljs-lambda-example.cat/meow}]}
  :cljsbuild
  {:builds [{:id "cljs-lambda-example"
             :source-paths ["src"]
             :compiler {:output-to "target/none/cljs_lambda_example.js"
                        :output-dir "target/none"
                        :target :nodejs
                        :optimizations :none
                        :source-map true}}
            {:id "cljs-lambda-example-adv"
             :source-paths ["src"]
             :compiler {:output-to "target/adv/cljs_lambda_example.js"
                        :output-dir "target/adv"
                        :target :nodejs
                        :optimizations :advanced}}]}
  :profiles
  {:dev {:dependencies
         [[com.cemerick/piggieback "0.2.1"]
          [org.clojure/tools.nrepl "0.2.10"]]
         :repl-options {:nrepl-middleware
                        [cemerick.piggieback/wrap-cljs-repl]}}})
