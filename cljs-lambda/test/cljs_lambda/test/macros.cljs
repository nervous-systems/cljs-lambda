(ns cljs-lambda.test.macros
  (:require [cljs-lambda.context :as ctx]
            [cljs-lambda.macros :as macros]
            [cljs-lambda.local :refer [invoke]]
            [cljs.test :refer-macros [deftest is]]
            [promesa.core :as p])
  (:require-macros [cljs-lambda.test.help :refer [deftest-async]]))

(macros/deflambda def-wrappers-are-evil [event ctx]
  (ctx/done! ctx nil event))

(deftest-async deflambda
  (let [event [1 2 "hello"]]
    (p/then (invoke def-wrappers-are-evil event)
      #(is (= % event)))))

(deftest deflambda-exports
  (is (-> #'def-wrappers-are-evil meta :export)))


