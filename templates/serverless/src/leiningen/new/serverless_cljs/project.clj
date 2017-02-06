(defproject {{name}} "0.1.0-SNAPSHOT"
  :dependencies [[org.clojure/clojure       "1.8.0"]
                 [org.clojure/clojurescript "1.8.51"]
                 [io.nervous/cljs-lambda    "0.3.4"]]
  :plugins [[lein-cljsbuild "1.1.5"]
            [lein-npm       "0.6.2"]
            [io.nervous/lein-cljs-lambda "0.6.4"]]
  :cljsbuild
  {:builds [{:id "{{name}}"
             :source-paths ["src"]
             :compiler {:output-to     "target/{{name}}/{{sanitized}}.js"
                        :output-dir    "target/{{name}}"
                        :target        :nodejs
                        :language-in   :ecmascript5
                        :optimizations :none}}]})
