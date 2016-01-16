(ns example.core
  (:require [cljs-lambda.util :as lambda :refer [async-lambda-fn]]
            [cljs.reader :refer [read-string]]
            [cljs.nodejs :as nodejs]
            [cljs.core.async :as async])
  (:require-macros [cljs.core.async.macros :refer [go]]))

;; For optimizations :advanced
(set! *main-cli-fn* identity)

(def config
  (-> (nodejs/require "fs")
      (.readFileSync "static/config.edn" "UTF-8")
      read-string))

(defmulti cast-async-spell (comp keyword :spell))

(defmethod cast-async-spell :delay [{:keys [msecs] :or {msecs 1000}}]
  (go
    (<! (async/timeout msecs))
    {:waited msecs}))

(defmethod cast-async-spell :delayed-failure
  [{:keys [msecs] :or {msecs 1000}}]
  (go
    (<! (async/timeout msecs))
    (js/Error. (str "Failing after " msecs " milliseconds"))))

(def ^:export work-magic
  (async-lambda-fn
   (fn [{:keys [magic-word] :as input} context]
     (if (not= magic-word (config :magic-word))
       ;; We can fail/succeed wherever w/ fail!/succeed! - we can also
       ;; leave an Error instance on the channel we return -
       ;; see :delayed-failure above.
       (lambda/fail! context "Your magic word is garbage")
       (cast-async-spell input)))))
