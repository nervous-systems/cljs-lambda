(ns cljs-lambda.context)

(defprotocol ContextHandle
  (-done! [this err result])
  (msecs-remaining [this]))

(defrecord LambdaContext [js-handle]
  ContextHandle
  (-done! [this err result]
    (.done js-handle err result))
  (msecs-remaining [this]
    (.getRemainingTimeInMillis js-handle)))

(defn done! [ctx & [err result]]
  (-done! ctx (clj->js err) (clj->js result)))
(defn fail! [ctx & [err]]
  (done! ctx err nil))
(defn succeed! [ctx & [result]]
  (done! ctx nil result))

(def context-keys
  {:aws-request-id  "awsRequestId"
   :client-context  "clientContext"
   :log-group-name  "logGroupName"
   :log-stream-name "logStreamName"
   :function-name   "functionName"})

(defn ->context [js-context]
  (into (->LambdaContext js-context)
    (for [[us them] context-keys]
      [us (aget js-context them)])))
