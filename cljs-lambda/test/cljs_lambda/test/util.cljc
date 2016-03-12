(ns cljs-lambda.test.util
  (:require [cljs-lambda.util :as lambda]
            [cljs.test :refer-macros [deftest is]]
            [cljs-lambda.macros :as macros])
  (:require-macros [cljs.core.async.macros :refer [go]]
                   [cljs-lambda.test.help :refer [deftest-async]]))

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
  (.then p #(is false (str "Success branch reached w/" %)) catch))

(deftest-async fail-promise
  (let [value (js/Error "This isn't an actual error")]
    (catches
     (lambda-fn [:fail-promise value] (lambda/mock-context))
     #(is (= % value)))))

(deftest-async succeed-promise
  (let [value "OK, ok.  OK"]
    (.then
     (lambda-fn [:succeed-promise value] (lambda/mock-context))
     #(is (= % value)))))

(deftest-async fail
  (let [error (js/Error. "ayy lmao")]
    (catches
     (lambda-fn [:fail error] (lambda/mock-context))
     #(is (= % error)))))

(deftest-async bail
  (let [error (js/Error. "3 eggs")
        f     (lambda/async-lambda-fn
               (fn [_ ctx]
                 (lambda/fail! ctx error)))]
    (catches
     (f nil (lambda/mock-context))
     (fn [result]
       (is (= result error))))))

(deftest-async bail-indirect
  (let [error (js/Error. "17 eggs")
        f     (lambda/async-lambda-fn
               (fn [_ ctx]
                 (go
                   (lambda/fail! ctx error)
                   "Some other value")))]
    (catches
     (f nil (lambda/mock-context))
     (fn [result]
       (is (= result error))))))

(deftest-async bail-eternal
  (let [ctx   (lambda/mock-context)
        error (js/Error. "12 eggs")
        f     (lambda/async-lambda-fn
               (fn [_ ctx]
                 (js/Promise.
                  (fn [resolve reject]
                    (js/setTimeout #(lambda/fail! ctx error) 50)))))]
    (f nil ctx)
    (catches
     (ctx :cljs-lambda/result)
     (fn [e]
       (is (= e error))))))

(deftest-async done
  (let [f (lambda/async-lambda-fn
           (fn [_ ctx]
             (lambda/done! ctx nil "deftest-async done")))]
    (.then
     (f nil (lambda/mock-context))
     #(is (= % "deftest-async done")))))

(deftest-async done-error
  (let [error (js/Error. "deftest-async done-error")
        f     (lambda/async-lambda-fn
               (fn [_ ctx]
                 (lambda/done! ctx error)))]
    (catches
     (f nil (lambda/mock-context))
     #(is (= % error)))))

(deftest-async succeed
  (.then
   (lambda-fn [:succeed "ayy lmao"] (lambda/mock-context))
   #(is (= % "ayy lmao"))))

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
    (.then
     (f event ctx)
     #(is (= % "Porcupine X")))))

(deftest-async error-handler-error
  (let [error    (js/Error. "Porcupine Z")
        error-in (js/Error. "Scissors")
        f (lambda/async-lambda-fn
           #(throw error)
           {:error-handler #(throw error-in)})]
    (catches
     (f nil (lambda/mock-context))
     #(is (= % error-in)))))

(deftest-async error-handler-skipped
  (let [f (lambda/async-lambda-fn
           (constantly "Everything's OK")
           {:error-handler #(throw (js/Error. "Wilderness"))})]
    (.then
     (f nil (lambda/mock-context))
     #(is (= % "Everything's OK")))))

(macros/deflambda def-wrappers-are-evil [event context]
  (lambda/done! context nil event))

(deftest-async deflambda
  (let [event [1 2 "hello"]]
    (.then
     (def-wrappers-are-evil event (lambda/mock-context))
     #(is (= % event)))))

(deftest deflambda-exports
  (is (-> #'def-wrappers-are-evil meta :export)))

(deftest-async msecs-remaining
  (let [f (lambda/async-lambda-fn
           (fn [_ ctx]
             (lambda/msecs-remaining ctx)))]
    (.then
     (f nil (lambda/mock-context))
     #(is (= % -1)))))

(deftest-async msecs-remaining-supplied
  (let [f (lambda/async-lambda-fn
           (fn [_ ctx]
             (lambda/msecs-remaining ctx)))]
    (.then
     (f nil (assoc (lambda/mock-context)
              :cljs-lambda/msecs-remaining (constantly 77)))
     #(is (= % 77)))))
