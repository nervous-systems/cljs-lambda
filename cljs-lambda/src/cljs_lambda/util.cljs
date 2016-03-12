(ns cljs-lambda.util
  (:require [cljs.nodejs :as nodejs]
            [cljs.core.async :as async :refer [<! >!]]
            [cljs.core.async.impl.protocols :as async-p]
            [cljs-lambda.context :as ctx]
            [promesa.core :as p])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(nodejs/enable-util-print!)

(defn wrap-lambda-fn [f]
  (fn [event ctx]
    (f (js->clj event :keywordize-keys true)
       (cond-> ctx
         (not (satisfies? ctx/ContextHandle ctx)) ctx/->context))))

(defn- promise? [x]
  (or (instance? js/Promise x)
      (and (fn? (.. x -then))
           (fn? (.. x -catch)))))

(defn- chan? [x]
  (satisfies? async-p/ReadPort x))

(defn- invoke-async [f & args]
  (p/promise
   (fn [resolve reject]
     (let [handle #(if (instance? js/Error %) (reject %) (resolve %))]
       (try
         (let [result (apply f args)]
           (cond
             (promise? result) (p/branch result resolve reject)
             (chan?    result) (go (handle (<! result)))
             :else             (handle result)))
         (catch js/Error e
           (reject e)))))))

(defn handle-errors [f error-handler]
  (fn [event context]
    (p/catch
      (invoke-async f event context)
      #(invoke-async error-handler % event context))))

(defn async-lambda-fn [f & [{:keys [error-handler]}]]
  (let [f (cond-> f error-handler (handle-errors error-handler))]
    (wrap-lambda-fn
     (fn [event ctx]
       (-> (invoke-async f event ctx)
           (p/branch
             (partial ctx/succeed! ctx)
             (partial ctx/fail!    ctx)))))))
