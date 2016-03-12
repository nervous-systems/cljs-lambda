(ns cljs-lambda.util
  (:require [cljs.nodejs :as nodejs]
            [cljs.core.async :as async :refer [<! >!]]
            [cljs.core.async.impl.protocols :as async-p])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(nodejs/enable-util-print!)

(defn- context-dispatch [{:keys [cljs-lambda/context-type]} & _]
  context-type)

(defn- channel-result [{:keys [cljs-lambda/resp-chan]} & [arg]]
  (go
    (when arg
      (>! resp-chan arg))
    (async/close! resp-chan))
  arg)

(defn fail! [{:keys [handle]} & [arg]]
  (.fail handle (clj->js arg)))

(defn succeed! [{:keys [handle]} & [arg]]
  (.succeed handle (clj->js arg)))

(defn done! [{:keys [handle]} & [bad good]]
  (.done handle (clj->js bad) (clj->js good)))

(defn msecs-remaining [{:keys [handle]}]
  (.getRemainingTimeInMillis handle))

(def context-keys
  {:aws-request-id  "awsRequestId"
   :client-context  "clientContext"
   :log-group-name  "logGroupName"
   :log-stream-name "logStreamName"
   :function-name   "functionName"})

(defmulti context->map context-dispatch)

(defmethod context->map :default [js-context]
  (into {:handle js-context}
    (for [[us them] context-keys]
      [us (aget js-context them)])))

(defn- chan->promise [ch]
  (js/Promise.
   (fn [resolve reject]
     (go
       (let [[tag value] (<! ch)]
         (case tag
           :resolve (resolve value)
           :reject  (reject  value)))))))

(defmethod context->map :mock
  [{:keys [cljs-lambda/msecs-remaining
           cljs-lambda/result-chan
           cljs-lambda/result] :as ctx
    :or {msecs-remaining (constantly -1)}}]
  (let [resolve   #(do (async/put! result-chan [:resolve (js->clj %)]) result)
        reject    #(do (async/put! result-chan [:reject  (js->clj %)]) result)]
    (assoc ctx
      :cljs-lambda/result result
      :handle
      #js {:fail    reject
           :succeed resolve
           :done    #(if %1 (reject %1) (resolve (js->clj %2)))
           :getRemainingTimeInMillis msecs-remaining})))

(defn mock-context [& [m]]
  (let [result-ch (async/promise-chan)
        result    (chan->promise result-ch)]
    (-> (merge context-keys m)
        (assoc
          :cljs-lambda/context-type :mock
          :cljs-lambda/result-chan result-ch
          :cljs-lambda/result result))))

(defn wrap-lambda-fn [f]
  (fn [event context]
    (f (js->clj event :keywordize-keys true)
       (context->map context))))

(defn- promise? [x]
  (or (instance? js/Promise x)
      (and (fn? (.. x -then))
           (fn? (.. x -catch)))))

(defn- chan? [x]
  (satisfies? async-p/ReadPort x))

(defn- invoke-async [f & args]
  (js/Promise.
   (fn [resolve reject]
     (let [handle #(if (instance? js/Error %) (reject %) (resolve %))]
       (try
         (let [result (apply f args)]
           (cond
             (promise? result) (.then result resolve reject)
             (chan?    result) (go (handle (<! result)))
             :else             (handle result)))
         (catch js/Error e
           (reject e)))))))

(defn handle-errors [f error-handler]
  (fn [event context]
    (.catch
     (invoke-async f event context)
     #(invoke-async error-handler % event context))))

(defn async-lambda-fn [f & [{:keys [error-handler]}]]
  (let [f (cond-> f error-handler (handle-errors error-handler))]
    (wrap-lambda-fn
     (fn [event context]
       (-> (invoke-async f event context)
           (.then (partial succeed! context)
                  (partial fail!    context)))))))
