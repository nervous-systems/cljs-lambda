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

(defmulti succeed! context-dispatch)
(defmulti fail!    context-dispatch)
(defmulti done!    context-dispatch)
(defmulti msecs-remaining context-dispatch)

(defmethod fail! :default [{:keys [handle]} & [arg]]
  (.fail handle (clj->js arg)))
(defmethod fail! :mock [context & [arg]]
  (channel-result context [:fail arg]))

(defmethod succeed! :default [{:keys [handle]} & [arg]]
  (.succeed handle (clj->js arg)))
(defmethod succeed! :mock [context & [arg]]
  (channel-result context [:succeed arg]))

(defmethod done! :default [{:keys [handle]} & [bad good]]
  (.done handle (clj->js bad) (clj->js good)))
(defmethod done! :mock [context & [bad good]]
  (channel-result context (if bad [:fail bad] [:succeed good])))

(defmethod msecs-remaining :default [{:keys [handle]}]
  (.getRemainingTimeInMillis handle))
(defmethod msecs-remaining :mock [{:keys [cljs-lambda/msecs-remaining]}]
  (or (and msecs-remaining (msecs-remaining)) -1))

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
(defmethod context->map :mock [context-map] context-map)

(defn mock-context [& [m]]
  (-> (merge context-keys m)
      (set/rename-keys context-keys)
      (assoc
        :cljs-lambda/resp-chan (async/chan 1)
        :cljs-lambda/context-type :mock)))

(defn wrap-lambda-fn [f]
  (fn [event context]
    (f (js->clj event :keywordize-keys true)
       (context->map context))))

(defn async-lambda-fn [f]
  (wrap-lambda-fn
   (fn [event context]
     (let [ch (go
                (let [result (try
                               (f event context)
                               (catch js/Error e e))
                      result (cond-> result
                               (satisfies? async-p/ReadPort result) <!)]
                  (if (instance? js/Error result)
                    (fail!    context result)
                    (succeed! context result))))]
       (get context :cljs-lambda/resp-chan ch)))))
