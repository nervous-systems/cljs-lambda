# Introduction

## Scope

With the Node JS Lambda runtime, handler functions are passed [two
arguments](http://docs.aws.amazon.com/lambda/latest/dg/nodejs-prog-model-handler.html):
`event`, a Javascript object constructed from the function's input JSON (if
any), and `context`, a map describing the circumstances of the function's
invocation.

With use of Clojurescript's js<->clj data conversion utilities, an exported
Clojurescript function can easily serve as a Lambda handler.  Arranging compiled
Clojurescript into a deployable zip file is outside the scope of this library,
and is handled by the excellent [cljs-lambda Leiningen
plugin](https://github.com/nervous-systems/cljs-lambda#plugin-overview).

The cljs-lambda _library_ is focused on simplifying the definition of
Clojurescript Lambda handlers -- EDN representations of event/context objects,
eliminating the need for knowledge of the deployment target within generic data
processing code, etc.

# Simple Example

```clojure
(ns project.ns
  (:require [cljs-lambda.macros :refer-macros [deflambda]]
            [promesa.core :as p]))

(deflambda wait [n ctx]
  (p/delay n {:waited n}))
```

Let's imagine we're invoking this from the command line:

```shell
$ aws lambda invoke --function-name wait --payload 66 output.json
```

_N.B. there's not necessarily any correspondence between a handler name and the
Lambda function's name (`--function-name`, above), but we're assuming they're
the same here._

## Input

In this case, our `deflambda wait`, if correctly deployed, is going to receive
the Clojurescript number `66` as its first (`event`) argument, and as the second
(`ctx`) arg, a record containing something like:

```clojure
{:function-name   "wait"
 :log-group-name  "/aws/lambda/wait"
 :log-stream-name "2016/03..."
 :aws-request-id  "4cb18..."
 ...}
```

_N.B. The `event` argument is constructed by calling `(js->clj event
:keywordize-keys true)` on the Javascript object received from the Lambda
instructure._

[[deflambda]] is an extremely simple wrapper around [[async-lambda-fn]] - the above
example being exactly equivalent to:

```clojure
(def ^:export wait
  (async-lambda-fn
   (fn [n ctx]
     (p/delay n {:waited n}))))
```

Aside from some trivial input coercion, `async-lambda-fn` is mostly concerned
with interpreting the wrapped function's body - in this case, a
[promesa](https://github.com/funcool/promesa)/[Bluebird](http://bluebirdjs.com/docs/api-reference.html)
Promise.

### A Note on Promises

Promises are an effective representation of Lambda handler results, insofar
as they're capable of unambiguously representing a single deferred success or
failure value.  `core.async` channels may also be returned by `cljs-lambda`
functions, though they're a less natural fit.

The Node version available on AWS Lambda is `v0.10.36`, at the time of writing
-- which predates the inclusion of an ES6 Promise object as a Node global.  The
[promesa](https://github.com/funcool/promesa) Clojure/script library is a
dependency of `cljs-lambda`, and packages the widely used
[Bluebird](http://bluebirdjs.com/docs/api-reference.html) Promise
implementation, providing a natural Clojurescript wrapper around its
functionality.

# Output

The promise returned by our example'll fire in `n` seconds, with the value
`{:waited n}` - which'll be serialized to JSON, and returned to the caller as
`{"waited" 66}`.

In Javascript, an explicit `context.done` method is called to signify
completion -- we're turning that inside-out, and completing the invocation when
the handler's Promise is resolved/rejected.

_N.B. `cljs-lambda` also provides a [[context/done!]] function -- which can be
called at any time to halt execution -- but passing the context object deep into
what'd otherwise be generic Clojurescript code may not be the most comfortable
approach._

# Errors

We can signal an error to the caller by returning a rejected promise from our
handler:

```clojure
(deflambda error [message ctx]
  (p/rejected (js/Error. message)))
```

Or by synchronously throwing a `js/Error`:

```clojure
(deflambda wait [n ctx]
  (when (zero? n)
    (throw (js/Error. "Sorry, but I can't help you")))
  (p/delay n {:waited n}))
```

The same two examples with `core.async`:

```clojure
(deflambda error [message ctx]
  (go (js/Error. message)))

(deflambda wait [n ctx]
  (when (zero? n)
    (throw (js/Error. "Sorry, but I can't help you")))
  (go
    (<! (async/timeout n))
    {:waited n}))
```
