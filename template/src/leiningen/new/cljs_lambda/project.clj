(defproject {{name}} "0.1.0-SNAPSHOT"
  :description "FIXME"
  :url "http://please.FIXME"
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/clojurescript "0.0-3308"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [io.nervous/cljs-lambda "0.1.2"]]
  :plugins [[lein-cljsbuild "1.0.6"]
            [lein-npm "0.5.0"]
            [io.nervous/lein-cljs-lambda "0.2.4"]]
  :node-dependencies [[source-map-support "0.2.8"]]
  :source-paths ["src"]
  :cljs-lambda
  {:defaults {:role "FIXME"}
   :functions
   [{:name   "work-magic"
     :invoke {{name}}.core/work-magic}]}
  :cljsbuild
  {:builds [{:id "{{name}}"
             :source-paths ["src"]
             :compiler {:output-to "out/{{sanitized}}.js"
                        :output-dir "out"
                        :target :nodejs
                        :optimizations :none
                        :source-map true}}]})
