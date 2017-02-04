(ns {{name}}.core
  (:require [cljs-lambda.macros :refer-macros [defgateway]]))

(defgateway echo [event ctx]
  {:status  200
   :headers {:content-type (-> event :headers :content-type)}
   :body    (event :body)})
