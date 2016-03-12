(ns cljs-lambda.macros
  #? (:cljs (:require [cljs-lambda.util]))
  #? (:cljs (:require-macros [cljs-lambda.macros])))

#? (:clj
    (defmacro deflambda [name bindings & body]
      `(def ~(vary-meta name assoc :export true)
         (cljs-lambda.util/async-lambda-fn
          (fn ~bindings
            ~@body)))))
