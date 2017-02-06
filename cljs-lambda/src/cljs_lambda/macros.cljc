(ns cljs-lambda.macros
  (:require [promesa.core :as p]
            #? (:clj  [clojure.tools.macro :as macro]
                :cljs [cljs-lambda.util])
            #? (:cljs [cljs-lambda.aws.event]))
  #? (:cljs (:require-macros [cljs-lambda.macros])))

#? (:clj
    (defmacro deflambda
      "Define exported var `name` with given `bindings`, interpreting `body` as
  per [[async-lambda-fn]].

  If [[handle-errors]]-type behaviour is required, use `async-lambda-fn`
  directly.

```clojure
(deflambda wait [{n :msecs} ctx]
  (p/delay n :waited))

;; Expands into

(def ^:export wait
  (async-lambda-fn
   (fn [{n :msecs} ctx]
     ...)))
```"
      [name & body]
      (let [[name [bindings & body]] (macro/name-with-attributes name body)]
       `(def ~(vary-meta name assoc :export true)
          (cljs-lambda.util/async-lambda-fn
           (fn ~bindings
             ~@body))))))

#? (:clj
    (defmacro defgateway
      "Variant of [[deflambda]] which uses [[cljs-lambda.aws.event]] to translate the
  first input and eventual output as maps describing API Gateway requests and
  responses.  Does nothing to errors.

```clojure
(defgateway echo [event ctx]
  {:status  200
   :headers {:content-type (-> event :headers :content-type)}
   :body    (event :body)})
```"
      [name & body]
      (let [[name [bindings & body]] (macro/name-with-attributes name body)]
        `(def ~(vary-meta name assoc :export true)
           (cljs-lambda.util/async-lambda-fn
            (fn [event# & args#]
              (let [event# (-> (assoc event# :aws.event/type :api-gateway)
                               cljs-lambda.aws.event/from-aws)]
                (p/then (apply cljs-lambda.util/invoke-async
                               (fn ~bindings ~@body)
                               (conj args# event#))
                  (comp cljs-lambda.aws.event/to-aws
                        #(assoc % :aws.event/type :api-gateway))))))))))
