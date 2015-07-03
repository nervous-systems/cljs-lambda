(ns cljs-lambda-example.cat)

(defn ^:export meow [event context]
  (.succeed context (str "I'm meowing at" (aget event "name"))))
