(defproject io.nervous/lein-cljs-lambda "0.5.2"
  :description "Deploying Clojurescript functions to AWS Lambda"
  :url "https://github.com/nervous-systems/cljs-lambda"
  :license {:name "Unlicense" :url "http://unlicense.org/UNLICENSE"}
  :dependencies [[lein-cljsbuild "1.0.6"]
                 [lein-npm       "0.5.0"]
                 [base64-clj     "0.1.1"]
                 [de.ubercode.clostache/clostache "1.4.0"]
                 [org.apache.commons/commons-compress "1.11"]
                 [camel-snake-kebab "0.3.2"]]
  :exclusions [org.clojure/clojure]
  :eval-in-leiningen true)
