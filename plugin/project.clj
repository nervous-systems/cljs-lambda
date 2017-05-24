(defproject io.nervous/lein-cljs-lambda "0.6.6"
  :description "Deploying Clojurescript functions to AWS Lambda"
  :url "https://github.com/nervous-systems/cljs-lambda"
  :license {:name "Unlicense" :url "http://unlicense.org/UNLICENSE"}
  :dependencies [[lein-cljsbuild "1.1.4"]
                 [lein-npm       "0.6.2"]
                 [base64-clj     "0.1.1"]
                 [de.ubercode.clostache/clostache     "1.4.0"]
                 [org.apache.commons/commons-compress "1.11"]]
  :exclusions    [org.clojure/clojure]
  :eval-in-leiningen true)
