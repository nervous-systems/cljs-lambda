(ns cljs-lambda.test.util
  (:require [cljs-lambda.util :as lambda]
            [cljs-lambda.local :as local :refer [invoke]]
            [cljs-lambda.context :as ctx]
            [cljs.test :refer-macros [deftest is]]
            [promesa.core :as p])
  (:require-macros [cljs.core.async.macros :refer [go]]
                   [cljs-lambda.test.help :refer [deftest-async]]))

(defn will= [x]
  #(is (= % x)))

(defn underlying-fn [[tag value] context]
  (case tag
    :fail    (ctx/fail!    context value)
    :succeed (ctx/succeed! context value)
    :chan    (go value)
    :fail-promise    (p/rejected value)
    :succeed-promise (p/resolved value)))

(def lambda-fn (lambda/async-lambda-fn underlying-fn))

(defn catches [p catch]
  (p/branch p #(is false (str "Success branch reached w/" %)) catch))

(deftest-async fail-promise
  (let [value (js/Error "This isn't an actual error")]
    (catches
     (invoke lambda-fn [:fail-promise value])
     (will= value))))

(deftest-async succeed-promise
  (let [value "OK, ok.  OK"]
    (p/then
      (invoke lambda-fn [:succeed-promise value])
      (will= value))))

(deftest-async fail
  (let [error (js/Error. "ayy lmao")]
    (catches
     (invoke lambda-fn [:fail error])
     (will= error))))

(deftest-async bail
  (let [error (js/Error. "3 eggs")
        f     (lambda/async-lambda-fn
               (fn [_ ctx]
                 (ctx/fail! ctx error)))]
    (catches
     (invoke f)
     (will= error))))

(deftest-async bail-indirect
  (let [error (js/Error. "17 eggs")
        f     (lambda/async-lambda-fn
               (fn [_ ctx]
                 (go
                   (ctx/fail! ctx error)
                   "Some other value")))]
    (catches
     (invoke f)
     (will= error))))

(deftest-async bail-eternal
  (let [error (js/Error. "12 eggs")
        f     (lambda/async-lambda-fn
               (fn [_ ctx]
                 (p/promise
                  (fn [_ _]
                    (js/setTimeout #(ctx/fail! ctx error) 50)))))]
    (catches
     (invoke f)
     (will= error))))

(deftest-async done
  (let [f (lambda/async-lambda-fn
           (fn [_ ctx]
             (ctx/done! ctx nil "deftest-async done")))]
    (p/then
      (invoke f)
      (will= "deftest-async done"))))

(deftest-async done-error
  (let [error (js/Error. "deftest-async done-error")
        f     (lambda/async-lambda-fn
               (fn [_ ctx]
                 (ctx/done! ctx error)))]
    (catches
     (invoke f)
     (will= error))))

(deftest-async succeed
  (p/then
    (invoke lambda-fn [:succeed "ayy lmao"])
    (will= "ayy lmao")))

(deftest-async handle-errors
  (let [f (-> #(throw (js/Error. "ERROR HANDLER IN PLACE"))
              (lambda/handle-errors (constantly "Success"))
              lambda/async-lambda-fn)]
    (p/then (invoke f)
      (will= "Success"))))

(deftest-async error-handler
  (let [error (js/Error. "Porcupine Z")
        event {:X 'y}
        f     (lambda/async-lambda-fn
               #(throw error)
               {:error-handler
                (fn [error* event* ctx*]
                  (is (= error error*))
                  (is (= event event*))
                  (p/resolved "Porcupine X"))})]
    (p/then (invoke f event)
      (will= "Porcupine X"))))

(deftest-async error-handler-error
  (let [error    (js/Error. "Porcupine Z")
        error-in (js/Error. "Scissors")
        f (lambda/async-lambda-fn
           #(throw error)
           {:error-handler #(throw error-in)})]
    (catches (invoke f) (will= error-in))))

(deftest-async error-handler-skipped
  (let [f (lambda/async-lambda-fn
           (constantly "Everything's OK!")
           {:error-handler #(throw (js/Error. "Wilderness"))})]
    (p/then (invoke f)
      (will= "Everything's OK!"))))

(deftest-async msecs-remaining
  (let [f (lambda/async-lambda-fn
           (fn [_ ctx]
             (ctx/msecs-remaining ctx)))]
    (p/then (invoke f)
      (will= -1))))

(deftest-async env
  (let [f   (lambda/async-lambda-fn
             (fn [_ ctx]
               (ctx/env ctx :ENV_VAR)))
        ctx (local/->context {:env {'ENV_VAR "deftest-async env"}})]
    (p/then (invoke f nil ctx)
      (will= "deftest-async env"))))

(deftest-async
  waits
  (let [f (lambda/async-lambda-fn
            (fn [_ ctx]
              (ctx/waits? ctx)))]
    (p/then
      (invoke f)
      (will= true))))

(deftest-async
  set-waits
  (let [f (lambda/async-lambda-fn
            (fn [_ ctx]
              (ctx/set-waits ctx false)))]
    (p/then
      (invoke f)
      (will= false))))
