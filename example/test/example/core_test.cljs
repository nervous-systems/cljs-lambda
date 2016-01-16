(ns example.core-test
  (:require [example.core :refer [work-magic config]]
            [cljs.test :refer-macros [deftest is]]
            [cljs-lambda.util :refer [mock-context]]
            [cljs.core.async :as async])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(deftest wrong-word
  (cljs.test/async
   done
   (go
     (let [[tag result] (<! (work-magic
                             {:magic-word "not the magic word"}
                             (mock-context)))]
       (is (= tag :fail))
       (done)))))

(deftest delay-spell
  (cljs.test/async
   done
   (go
     (let [[tag result] (<! (work-magic
                             {:magic-word (:magic-word config)
                              :spell :delay
                              :msecs 2}
                             (mock-context)))]
       (is (= tag :succeed))
       (is (= result {:waited 2}))
       (done)))))

(deftest delayed-failure-spell
  (cljs.test/async
   done
   (go
     (let [[tag result] (<! (work-magic
                             {:magic-word (:magic-word config)
                              :spell :delayed-failure
                              :msecs 3}
                             (mock-context)))]
       (is (= tag :fail))
       (is (instance? js/Error result))
       (done)))))
