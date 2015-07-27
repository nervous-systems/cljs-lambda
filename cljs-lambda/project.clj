(defproject io.nervous/cljs-lambda "0.1.2"
  :description "Clojurescript AWS Lambda utilities"
  :url "https://github.com/nervous-systems/cljs-lambda"
  :license {:name "Unlicense" :url "http://unlicense.org/UNLICENSE"}
  :scm {:name "git" :url "https://github.com/nervous-systems/cljs-lambda"}
  :deploy-repositories [["clojars" {:creds :gpg}]]
  :signing {:gpg-key "moe@nervous.io"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/clojurescript "0.0-3308"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]]
  :profiles
  {:dev {:dependencies
         [[com.cemerick/piggieback "0.2.1"]
          [org.clojure/tools.nrepl "0.2.10"]]
         :repl-options {:nrepl-middleware
                        [cemerick.piggieback/wrap-cljs-repl]}}})
