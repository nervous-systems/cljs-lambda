(ns leiningen.new.cljs-lambda
  (:require [leiningen.new.templates :refer [renderer name-to-path ->files]]
            [leiningen.core.main :as main]))

(def render (renderer "cljs-lambda"))

(defn cljs-lambda
  [name]
  (let [data {:name name
              :sanitized (name-to-path name)}]
    (main/info "Generating fresh 'lein new' cljs-lambda project.")
    (->files data
             ["project.clj"                 (render "project.clj" data)]
             ["src/{{sanitized}}/core.cljs" (render "core.cljs"   data)]
             [".gitignore"                  (render "gitignore"   data)])))
