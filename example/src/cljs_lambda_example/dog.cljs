(ns cljs-lambda-example.dog
  (:require [cljs-lambda.util :refer [wrap-lambda-fn fail! succeed!]]))

(def ^:export bark
  (wrap-lambda-fn
   (fn [{bark-target :name} context]
     (if (= bark-target "Postman")
       (fail!    context "I'm sorry, I don't really want to bark at you")
       (succeed! context (str "I'm barking at: " bark-target))))))
