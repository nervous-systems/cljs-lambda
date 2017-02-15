(ns cljs-lambda.context
  "Representation & manipulation of Lambda-handler execution context.

  Contexts are represented as records with keys:

* `:aws-request-id`
* `:client-context`
* `:log-group-name`
* `:log-stream-name`
* `:function-name`
* `:function-arn`
* `:identity` (optional)
* `:handler-callback` (optional)
")

(defn- json->edn [json]
  (js->clj (js/JSON.parse (js/JSON.stringify json))))

(defprotocol ContextHandle
  (-done!
    [this err result]
    "See [[done!]]")
  (msecs-remaining
    [this]
    "The number of milliseconds remaining until the timeout of the invocation
   associated with this context.")
  (environment
    [this]
    "Retrieve a map of environment variables."))

(defrecord ^:no-doc LambdaContext [js-handle]
  ContextHandle
  (-done! [this err result]
    (.done js-handle err result))
  (msecs-remaining [this]
    (.getRemainingTimeInMillis js-handle))
  (environment [this]
    (json->edn js/process.env)))

(defn env
  "Retrieve an environment variable by name, defaulting to `nil` if not found.

```clojure
(env ctx \"USER\")
(env ctx :USER)
(env ctx 'USER)
```"
  [ctx k]
  (get (environment ctx) (name k)))

(defn done!
  "Terminate execution of the handler associated w/ the given context, conveying
  the given error (if non-nil), or the given success result (if non-nil).  No
  arguments communicates generic success.

```clojure
(deflambda quick [_ ctx]
  (ctx/done! ctx))
```"
  [ctx & [err result]]
  (-done! ctx (clj->js err) (clj->js result)))

(defn fail!
  "Trivial wrapper around [[done!]]

  Terminate execution of the handler associated w/ the given context, conveying
  the given error, if non-nil - otherwise mark the execution as failed w/ no
  specific error.

```clojure
(deflambda purchase [item-name ctx]
  (ctx/fail! ctx (js/Error. (str \"Sorry, no more \" item-name))))
```"
  [ctx & [err]]
  (done! ctx err nil))

(defn succeed!
  "Trivial wrapper around [[done!]]

  Terminate execution of the handler associated w/ the given context, conveying
  the given JSON-serializable success value, if non-nil - otherwise mark the
  execution as successful w/ no specific result.

```clojure
(deflambda purchase [item-name ctx]
  (ctx/succeed! ctx \"You bought something\"))
```"
  [ctx & [result]]
  (done! ctx nil result))

(def ^:no-doc context-keys
  {:aws-request-id  "awsRequestId"
   :client-context  "clientContext"
   :log-group-name  "logGroupName"
   :log-stream-name "logStreamName"
   :function-name   "functionName"
   :function-arn    "invokedFunctionArn"})

(defn- identity-map [js-context]
  (when-let [id (.. js-context -identity)]
    {:cognito-id   (.. id -cognitoIdentityId)
     :cognito-pool (.. id -cognitoIdentityPoolId)}))

(defn ^:no-doc ->context [js-context]
  (let [id (identity-map js-context)
        cb (.. js-context -handler-callback)]
    (cond-> (into (->LambdaContext js-context)
              (for [[us them] context-keys]
                [us (aget js-context them)]))
      id (assoc :identity id)
      cb (assoc :handler-callback cb))))
