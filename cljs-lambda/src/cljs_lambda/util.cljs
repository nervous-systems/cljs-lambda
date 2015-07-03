(ns cljs-lambda.util
  (:require [cljs.nodejs :as nodejs]
            [cljs.core.async :refer [<!]]
            [clojure.set :as set])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(nodejs/enable-util-print!)

(defn msecs-remaining [{:keys [handle]}]
  (.getRemainingTimeInMillis handle))

(defn succeed! [{:keys [handle]} & [arg]]
  (.succeed handle arg))

(defn fail! [{:keys [handle]} & [arg]]
  (.fail handle arg))

(defn done! [{:keys [handle]} & [bad good]]
  (.done handle bad good))

(defn context->map [js-context]
  (-> js-context
      (js->clj :keywordize-keys true)
      (assoc :handle js-context)
      (set/rename-keys
       {:awsRequestId  :aws-request-id
        :clientContext :client-context
        :logGroupName  :log-group-name
        :logStreamName :log-stream-name
        :functionName  :function-name})))

(defn wrap-lambda-fn [f]
  (fn [event context]
    (f (js->clj event :keywordize-keys true)
       (context->map context))))

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
