(ns cljs-lambda.util
  (:require [cljs.nodejs :as nodejs]
            [cljs.core.async :refer [<!]]
            [clojure.set :as set])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(nodejs/enable-util-print!)

(defn msecs-remaining [{:keys [handle]}]
  (.getRemainingTimeInMillis handle))

(defn succeed! [{:keys [handle]} & [arg]]
  (.succeed handle (clj->js arg)))

(defn fail! [{:keys [handle]} & [arg]]
  (.fail handle (clj->js arg)))

(defn done! [{:keys [handle]} & [bad good]]
  (.done handle (clj->js bad) (clj->js good)))

(defn context->map [js-context]
  {:handle          js-context
   :aws-request-id  (aget js-context "awsRequestId")
   :client-context  (aget js-context "clientContext")
   :log-group-name  (aget js-context "logGroupName")
   :log-stream-name (aget js-context "logStreamName")
   :function-name   (aget js-context "functionName")})

(defn wrap-lambda-fn [f]
  (fn [event context]
    (f (js->clj event :keywordize-keys true)
       (context->map context))))

(defn async-lambda-fn [f]
  (wrap-lambda-fn
   (fn [event context]
     (go
       (let [result (<! (f event context))]
         (if (instance? js/Error result)
           (fail!    context result)
           (succeed! context result)))))))
