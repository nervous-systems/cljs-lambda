(ns cljs-lambda.local
  (:require [promesa.core :as p]
            [cljs-lambda.context :as ctx]
            [cljs.core.async :as async])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(defrecord LocalContext [result-channel]
  ctx/ContextHandle
  (-done! [this err result]
    (async/put! result-channel [(js->clj err) (js->clj result)]))
  (msecs-remaining [this]
    -1))

(defn ->context [& [key-overrides]]
  (into (->LocalContext (async/promise-chan))
    (merge ctx/context-keys key-overrides)))

(defn- context-promise [{:keys [result-channel]}]
  (p/promise
   (fn [resolve reject]
     (go
       (let [[err result] (<! result-channel)]
         (if err
           (reject  err)
           (resolve result)))))))

(defn invoke
  ([f]       (invoke f nil))
  ([f event] (invoke f event (->context)))
  ([f event ctx]
   (let [promise (context-promise ctx)]
     (f event ctx)
     promise)))

(defn channel [& args]
  (let [chan (async/promise-chan)]
    (p/branch (apply invoke args)
      #(async/put! chan %)
      #(async/put! chan %))
    chan))
