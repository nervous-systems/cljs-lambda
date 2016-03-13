(ns cljs-lambda.util
  (:require [cljs.nodejs :as nodejs]
            [cljs.core.async :as async :refer [<! >!]]
            [cljs.core.async.impl.protocols :as async-p]
            [cljs-lambda.context :as ctx]
            [promesa.core :as p])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(nodejs/enable-util-print!)

(defn wrap-lambda-fn
  "Prepare a two-arg (event, context) function for exposure as a Lambda handler.
  The returned function will convert the event (Javascript Object) into a
  Clojurescript map with keyword keys, and turn the context into a record having
  keys `:aws-request-id`, `:client-context`, `:log-group-name`,
  `:log-stream-name` & `:function-name` - suitable for manipulation
  by [[context/done!]]  etc."
  [f]
  (fn [event ctx]
    (f (js->clj event :keywordize-keys true)
       (cond-> ctx
         (not (satisfies? ctx/ContextHandle ctx)) ctx/->context))))

(defn- promise? [x]
  (or (instance? js/Promise x)
      (fn? (.. x -then))))

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

(defn handle-errors
  "Returns a Lambda handler delegating to the input handler `f` (i.e. as
  returned by [[async-lambda-fn]]), conveying any errors to `error-handler`, a
  function of `[error event ctx]`, which has the opportunity to modify the
  eventual handler response (rethrow, return promise/channel, etc.)

```clojure
(def ^:export successful-fn
  (-> (fn [event ctx] (p/rejected (js/Error.)))
      async-lambda-fn
      (handle-errors (fn [e event ctx] \"Success\"))))
```"
  [f error-handler]
  (fn [event context]
    (p/catch
      (invoke-async f event context)
      #(invoke-async error-handler % event context))))

(defn async-lambda-fn
  "Repurpose the two-arg (event, context) asynchronous function `f` as a Lambda
  handler.  The function's result determines the invocation's success at the
  Lambda level, without the requirement of using
  Lambda-specific ([[context/fail!]], etc.) functionality within the body.
  Optional error handler behaves as [[handle-errors]].

  **Success conditions**

* Returns successful `js/Promise` (or object w/ `.then()`)
* Returns `core.async` channel containing non-`js/Error`
* Synchronously returns arbitrary object.

  **Failure conditions**

* Returns rejected `js/Promise`
* Returns `core.async` channel containing `js/Error`
* Synchronously throws `js/Error`

```clojure
;; Failure w/ core.async
(def ^:export blow-up
  (async-lambda-fn
   (fn [_ ctx]
     (go
       (<! (async/timeout 10))
       (js/Error. \"I blew up\")))))
```

```clojure
;; Success w/ promises
(def ^:export wait
  (async-lambda-fn
   (fn [{n :msecs} ctx]
     (promesa/promise
      (fn [resolve _]
        (promesa/schedule n #(resolve :waited)))))))
```

  See [[macros/deflambda]] for an alternative approach to defining/export
  handler vars."
  [f & [{:keys [error-handler]}]]
  (let [f (cond-> f error-handler (handle-errors error-handler))]
    (wrap-lambda-fn
     (fn [event ctx]
       (-> (invoke-async f event ctx)
           (p/branch
             (partial ctx/succeed! ctx)
             (partial ctx/fail!    ctx)))))))
