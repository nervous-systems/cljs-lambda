(ns cljs-lambda.local
  "Utilities for the local (e.g. automated tests, REPL interactions) invocation
  of Lambda handlers.  Local invocation is accomplished by passing handlers a
  stub context object which records completion signals."
  (:require [promesa.core :as p]
            [cljs-lambda.context :as ctx]
            [cljs.core.async :as async])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(defrecord ^:no-doc LocalContext [result-channel]
  ctx/ContextHandle
  (-done! [this err result]
    (async/put! result-channel [(js->clj err) (js->clj result)]))
  (msecs-remaining [this]
    -1))

(defn ->context
  "Create a `context` object for use w/ [[invoke]], [[channel]].  This is
  helpful if your want to take advantage of `key-overrides` to supply different
  context values for an invocation -- otherwise, no need to use directly.

```clojure
(invoke wait {:msecs 17} (->context {:function-name \"wait\"}))
```

  By default, the values for the context keys will match the key names, more or
  less, e.g. `{:function-name \"functionName\"}."
  [& [key-overrides]]
  (map->LocalContext
   (merge ctx/context-keys
          {:result-channel (async/promise-chan)}
          key-overrides)))

(defn- channel->promise [ch]
  (p/promise
   (fn [resolve reject]
     (go
       (let [[err result] (<! ch)]
         (if err
           (reject  err)
           (resolve result)))))))

(defn invoke
  "Utility for local (test, REPL) invocation of Lambda handlers.  Returns a
  promise resolved or rejected with the result of invoking `f` on the given
  event (nil in arity 1) and `context` object (defaulted in lower arities).

```clojure
(deflambda add [[x y] ctx]
  (go (+ x y)))

(invoke add [-1 2])
;; => <Promise: 1>
```"
  ([f]       (invoke f nil))
  ([f event] (invoke f event (->context)))
  ([f event {:keys [result-channel] :as ctx}]
   (let [promise (channel->promise result-channel)]
     (f event ctx)
     promise)))

(defn channel
  "Identical semantics to [[invoke]], though the return value is a `core.async`
  channel containing either `[:succeed <result>]` or `[:fail <result>]`.

```clojure
(deflambda please-repeat [[n x] ctx]
  (promesa/resolved (repeat n x)))

(invoke please-repeat [3 :x])
;; => <Channel [:succeed [\"x\" \"x\" \"x\"]]>
```"
  ([f]       (channel f nil))
  ([f event] (channel f event (->context)))
  ([f event {input-channel :result-channel :as ctx}]
   (f event ctx)
   (go
     (let [[err result] (<! input-channel)]
       (if err
         [:fail    err]
         [:succeed result])))))
