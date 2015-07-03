(ns cljs-lambda.util
  (:require [cljs.core.async :refer [<!]])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(defn msecs-remaining [{:keys [handle]}]
  (.getRemainingTimeInMillis handle))

(defn succeed! [{:keys [handle]} & [arg]]
  (.succeed handle arg))

(defn fail! [{:keys [handle]} & [arg]]
  (.fail handle arg))

(defn done! [{:keys [handle]} & [bad good]]
  (.done handle bad good))

(defn context->map [context]
  {:handle context
   :identity        (aget context "identity")
   :aws-request-id  (aget context "awsRequestId")
   :client-context  (aget context "clientContext")
   :log-group-name  (aget context "logGroupName")
   :log-stream-name (aget context "logStreamName")
   :function-name   (aget context "functionName")})

(defn wrap-lambda-fn [f]
  (fn [event context]
    (f event (context->map context))))

(defn async-lambda-fn [f]
  (wrap-lambda-fn
   (fn [event context]
     (go
       (let [result
             (try
               (<! (f event context))
               (catch js/Error e
                 e))]
         (if (instance? js/Error result)
           (fail!    context result)
           (succeed! context result)))))))
