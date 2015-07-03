(ns cljs-lambda-example.dog
  (:require [cljs-lambda.util :refer [wrap-lambda-fn fail! succeed!]]))

(def ^:export bark
  (wrap-lambda-fn
   [{bark-target :name} context]
   (if (= bark-target "Postman")
     (fail! "I'm sorry, I don't really want to bark at you")
     (succeed! (str "I'm barking at: " bark-target)))))
