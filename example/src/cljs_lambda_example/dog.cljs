(ns cljs-lambda-example.dog)

(defn ^:export bark [event context]
  (.succeed context (str "I'm barking at" (aget event "name"))))
