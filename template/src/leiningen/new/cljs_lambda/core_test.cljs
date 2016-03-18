(ns {{name}}.core-test
  (:require [{{name}}.core :refer [work-magic config]]
            [cljs.test :refer-macros [deftest is]]
            [cljs-lambda.local :refer [invoke channel]]
            [promesa.core :as p :refer [await] :refer-macros [alet]]
            [cljs.core.async :refer [<!]])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(defn with-promised-completion [p]
  (cljs.test/async
   done
   (-> p
       (p/catch #(is (not %)))
       (p/then done))))

(defn with-some-error [p]
  (p/branch p
    #(is false "Expected error")
    (constantly nil)))

(deftest echo
  (-> (invoke work-magic {:magic-word "not the magic word"})
      with-some-error
      with-promised-completion))

(def delay-channel-req
  {:magic-word (:magic-word config)
   :spell :delay-channel
   :msecs 2})

(deftest delay-channel-spell
  (with-promised-completion
    (alet [{:keys [waited]} (await (invoke work-magic delay-channel-req))]
      (is (= waited 2)))))

(deftest delay-channel-spell-go
  (cljs.test/async
   done
   (go
     (let [[tag response] (<! (channel work-magic delay-channel-req))]
       (is (= tag :succeed))
       (is (= response {:waited 2})))
     (done))))

(deftest delay-fail-spell
  (-> (invoke work-magic
              {:magic-word (:magic-word config)
               :spell :delay-fail
               :msecs 3})
      with-some-error
      with-promised-completion))
