(ns example.core-test
  (:require [example.core :refer [work-magic config]]
            [cljs.test :refer-macros [deftest is]]
            [cljs-lambda.util :refer [mock-context]]
            [promesa.core :as p]))

(defn with-promised-completion [f]
  (cljs.test/async
   done
   (p/branch (f) done done)))

(deftest wrong-word
  (with-promised-completion
    (fn []
      (-> (work-magic {:magic-word "not the magic word"} (mock-context))
          (p/then #(is false "Expected error"))))))

(deftest delay-channel-spell
  (with-promised-completion
    (fn []
      (-> (work-magic
           {:magic-word (:magic-word config)
            :spell :delay-channel
            :msecs 2}
           (mock-context))
          (p/branch #(is (= % {"waited" 2})) #(is false %))))))

(deftest delay-fail-spell
  (with-promised-completion
    (fn []
      (-> (work-magic
           {:magic-word (:magic-word config)
            :spell :delay-fail
            :msecs 3}
           (mock-context))
          (p/then #(is false "Expected error"))))))
