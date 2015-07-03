(ns cljs-lambda-example.cat
  (:require [cljs-lambda.util :refer [async-lambda-fn]]
            [cljs.core.async :as async :refer [<!]])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(defn asynchronous-meow [meow-target]
  (go
    (<! (async/timeout 1000))
    {:from "the-cat"
     :to meow-target
     :message "I'm  meowing at you"}))

(def ^:export meow
  (async-lambda-fn
   (fn [{meow-target :name} context]
     (asynchronous-meow meow-target))))
