(ns cljs-lambda.test.util
  (:require [cljs-lambda.util :as lambda]
            [cljs.test :refer-macros [deftest is]]
            [cljs-lambda.macros :as macros]
            [promesa.core :as p])
  (:require-macros [cljs.core.async.macros :refer [go]]
                   [cljs-lambda.test.help :refer [deftest-async]]))

(defn will= [x]
  #(is (= % x)))

(deftest mock-context
  (let [ctx (lambda/mock-context)]
    (is (ctx :aws-request-id))
    (is (ctx :function-name))
    (is (ctx :client-context))))

(defn underlying-fn [[tag value] context]
  (case tag
    :fail    (lambda/fail!    context value)
    :succeed (lambda/succeed! context value)
    :chan    (go value)
    :fail-promise    (.reject  js/Promise value)
    :succeed-promise (.resolve js/Promise value)))

(def lambda-fn (lambda/async-lambda-fn underlying-fn))

(defn catches [p catch]
  (p/branch p #(is false (str "Success branch reached w/" %)) catch))

(deftest-async fail-promise
  (let [value (js/Error "This isn't an actual error")]
    (catches
     (lambda-fn [:fail-promise value] (lambda/mock-context))
     (will= value))))

(deftest-async succeed-promise
  (let [value "OK, ok.  OK"]
    (p/then
     (lambda-fn [:succeed-promise value] (lambda/mock-context))
     (will= value))))

(deftest-async fail
  (let [error (js/Error. "ayy lmao")]
    (catches
     (lambda-fn [:fail error] (lambda/mock-context))
     (will= error))))

(deftest-async bail
  (let [error (js/Error. "3 eggs")
        f     (lambda/async-lambda-fn
               (fn [_ ctx]
                 (lambda/fail! ctx error)))]
    (catches
     (f nil (lambda/mock-context))
     (will= error))))

(deftest-async bail-indirect
  (let [error (js/Error. "17 eggs")
        f     (lambda/async-lambda-fn
               (fn [_ ctx]
                 (go
                   (lambda/fail! ctx error)
                   "Some other value")))]
    (catches
     (f nil (lambda/mock-context))
     (will= error))))

(deftest-async bail-eternal
  (let [ctx   (lambda/mock-context)
        error (js/Error. "12 eggs")
        f     (lambda/async-lambda-fn
               (fn [_ ctx]
                 (js/Promise.
                  (fn [resolve reject]
                    (js/setTimeout #(lambda/fail! ctx error) 50)))))]
    (f nil ctx)
    (catches (ctx :cljs-lambda/result) (will= error))))

(deftest-async done
  (let [f (lambda/async-lambda-fn
           (fn [_ ctx]
             (lambda/done! ctx nil "deftest-async done")))]
    (p/then
     (f nil (lambda/mock-context))
     (will= "deftest-async done"))))

(deftest-async done-error
  (let [error (js/Error. "deftest-async done-error")
        f     (lambda/async-lambda-fn
               (fn [_ ctx]
                 (lambda/done! ctx error)))]
    (catches
     (f nil (lambda/mock-context))
     (will= error))))

(deftest-async succeed
  (p/then
   (lambda-fn [:succeed "ayy lmao"] (lambda/mock-context))
   (will= "ayy lmao")))

(deftest-async error-handler
  (let [error (js/Error. "Porcupine Z")
        event {:X 'y}
        ctx   (lambda/mock-context)

        f     (lambda/async-lambda-fn
               #(throw error)
               {:error-handler (fn [error* event* ctx*]
                                 (is (= error error*))
                                 (is (= event event*))
                                 (.resolve js/Promise "Porcupine X"))})]
    (p/then (f event ctx) (will= "Porcupine X"))))

(deftest-async error-handler-error
  (let [error    (js/Error. "Porcupine Z")
        error-in (js/Error. "Scissors")
        f (lambda/async-lambda-fn
           #(throw error)
           {:error-handler #(throw error-in)})]
    (catches (f nil (lambda/mock-context)) (will= error-in))))

(deftest-async error-handler-skipped
  (let [f (lambda/async-lambda-fn
           (constantly "Everything's OK!")
           {:error-handler #(throw (js/Error. "Wilderness"))})]
    (p/then
     (f nil (lambda/mock-context))
     (will= "Everything's OK!"))))

(macros/deflambda def-wrappers-are-evil [event context]
  (lambda/done! context nil event))

(deftest-async deflambda
  (let [event [1 2 "hello"]]
    (p/then
     (def-wrappers-are-evil event (lambda/mock-context))
     (will= event))))

(deftest deflambda-exports
  (is (-> #'def-wrappers-are-evil meta :export)))

(deftest-async msecs-remaining
  (let [f (lambda/async-lambda-fn
           (fn [_ ctx]
             (lambda/msecs-remaining ctx)))]
    (p/then
     (f nil (lambda/mock-context))
     (will= -1))))

(deftest-async msecs-remaining-supplied
  (let [f (lambda/async-lambda-fn
           (fn [_ ctx]
             (lambda/msecs-remaining ctx)))]
    (p/then
     (f nil (assoc (lambda/mock-context)
              :cljs-lambda/msecs-remaining (constantly 77)))
     (will= 77))))
