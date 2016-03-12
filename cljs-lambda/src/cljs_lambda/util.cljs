(ns cljs-lambda.util
  (:require [cljs.nodejs :as nodejs]
            [cljs.core.async :as async :refer [<! >!]]
            [cljs.core.async.impl.protocols :as async-p]
            [clojure.set :as set])
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

(defmethod context->map :mock
  [{:keys [cljs-lambda/msecs-remaining] :as ctx
    :or {msecs-remaining (constantly -1)}}]
  (let [reject  #(.reject  js/Promise (js->clj %))
        resolve #(.resolve js/Promise (js->clj %))]
    (assoc ctx
      :handle
      #js {:fail    reject
           :succeed resolve
           :done    #(if %1 (reject %1) (resolve %2))
           :getRemainingTimeInMillis msecs-remaining})))

(defn mock-context [& [m]]
  (-> (merge context-keys m)
      (set/rename-keys context-keys)
      (assoc :cljs-lambda/context-type :mock)))

(defn wrap-lambda-fn [f]
  (fn [event context]
    (f (js->clj event :keywordize-keys true)
       (context->map context))))

(defn- promise? [x]
  (or (instance? js/Promise x)
      (and (fn? (.. x -then))
           (fn? (.. x -catch)))))

(defn- chain-promise [in resolve reject]
  (.then  in resolve)
  (.catch in reject))

(defn- chan? [x]
  (satisfies? async-p/ReadPort result))

(defn- invoke [f event context]
  (js/Promise.
   (fn [resolve reject]
     (let [handle #(if (instance? js/Error %) (reject %) (resolve %))]
       (try
         (let [result (f event context)]
           (cond
             (promise? result) (chain-promise result resolve reject)
             (chan?    result) (go (handle (<! result)))
             :else             (handle result)))
         (catch js/Error e
           (reject e)))))))

(defn promise->chan [x]
  (let [chan (async/chan)
        done #(do
                (async/put! chan %)
                (async/close! chan))]
    (-> x
        (.then  done)
        (.catch done))))

(defn async-lambda-fn [f]
  (wrap-lambda-fn
   (fn [event context]
     (-> f
         (invoke event context)
         (.then  (partial succeed! context))
         (.catch (partial fail!    context))))))
