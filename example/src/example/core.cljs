(ns example.core
  (:require [cljs-lambda.util :as lambda ]
            [cljs-lambda.macros :refer-macros [deflambda]]
            [cljs.reader :refer [read-string]]
            [cljs.nodejs :as nodejs]
            [cljs.core.async :as async]
            [promesa.core :as p])
  (:require-macros [cljs.core.async.macros :refer [go]]))

;; For optimizations :advanced
(set! *main-cli-fn* identity)

(def config
  (-> (nodejs/require "fs")
      (.readFileSync "static/config.edn" "UTF-8")
      read-string))

(defmulti cast-async-spell (fn [{spell :spell} ctx] (keyword spell)))

(defmethod cast-async-spell :delay-channel
  [{:keys [msecs] :or {msecs 1000}} ctx]
  (go
    (<! (async/timeout msecs))
    {:waited msecs}))

(defmethod cast-async-spell :delay-promise
  [{:keys [msecs] :or {msecs 1000}} ctx]
  (p/promise
   (fn [resolve]
     (p/schedule msecs #(resolve {:waited msecs})))))

(defmethod cast-async-spell :delay-fail
  [{:keys [msecs] :or {msecs 1000}} ctx]
  (go
    (<! (async/timeout msecs))
    ;; We can fail/succeed wherever w/ fail!/succeed! - we can also
    ;; leave an Error instance on the channel we return, or return a reject
    ;; promised - see :delayed-failure above.
    (lambda/fail! ctx (js/Error. (str "Failing after " msecs " milliseconds")))))

(deflambda work-magic [{:keys [magic-word] :as input} context]
  (when (not= magic-word (config :magic-word))
    (throw (js/Error. "Your magic word is garbage")))
  (cast-async-spell input context))
