(ns leiningen.new.serverless-cljs
  (:require [leiningen.new.templates :refer [renderer name-to-path ->files]]
            [leiningen.core.main :as main]))

(def render (renderer "serverless-cljs"))

(defn serverless-cljs
  [name]
  (let [data {:name name
              :sanitized (name-to-path name)}]
    (main/info "Generating fresh 'lein new' serverless-cljs project.")
    (->files data
             ["project.clj"                       (render "project.clj"    data)]
             ["serverless.yml"                    (render "serverless.yml" data)]
             ["README.md"                         (render "README.md"   data)]
             ["src/{{sanitized}}/core.cljs"       (render "core.cljs"   data)]
             [".gitignore"                        (render "gitignore"   data)])))
