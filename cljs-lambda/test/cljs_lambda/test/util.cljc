(ns cljs-lambda.test.util
  (:require [cljs-lambda.util :as lambda]
            [cljs.test :refer-macros [deftest is]]
            [cljs-lambda.macros :as macros])
  (:require-macros [cljs.core.async.macros :refer [go]]
                   [cljs-lambda.test.help :refer [deftest-async]]))

(def lambda-fn
  (lambda/async-lambda-fn
   (fn [[tag value] context]
     (case tag
       :fail    (lambda/fail!    context value)
       :succeed (lambda/succeed! context value)
       :chan    (go value)
       :fail-promise    (.reject  js/Promise value)
       :succeed-promise (.resolve js/Promise value)))))

(defn bind [p then & [catch]]
  (-> p
      (.then then)
      (cond-> catch (.catch catch))))

(defn catches [p catch]
  (bind p #(is false (str "Success branch reached w/" %)) catch))

(deftest-async fail
  (let [error (js/Error. "ayy lmao")]
    (catches
     (lambda-fn [:fail error] (lambda/mock-context))
     (fn [result]
       (is (= result error))))))

(deftest-async succeed
  (bind
   (lambda-fn [:succeed "ayy lmao"] (lambda/mock-context))
   #(is (= % "ayy lmao"))))
