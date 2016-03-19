(ns cljs-lambda.macros
  (:require #? (:clj  [clojure.tools.macro :as macro]
                :cljs [cljs-lambda.util]))
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
