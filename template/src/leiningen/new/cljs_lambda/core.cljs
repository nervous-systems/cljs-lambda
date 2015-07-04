(ns {{name}}.core
  (:require [cljs-lambda.util :refer [async-lambda-fn]])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(def ^:expose work-magic
  (async-lambda-fn
   (fn [{:keys [variety]} context]
     (go
       (str "Sorry, I don't yet know how to work: " variety)))))
