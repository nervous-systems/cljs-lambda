# cljs-lambda

[AWS Lambda](http://aws.amazon.com/documentation/lambda/) is a service which
allows named functions to be directly invoked (via a client API), have their
execution triggered by a variety of AWS events (S3 upload, DynamoDB activity,
etc.) or to serve as HTTP endpoints (via [API
Gateway](https://aws.amazon.com/api-gateway/)).

This README serves to document a Leiningen plugin (`lein-cljs-lambda`), template
(`cljs-lambda`) and small library (`cljs-lambda`) to facilitate the writing of
Lambda functions in Clojurescript and their deployment/invocation.

### Features

 - Low instance warmup penalty
 - Specify execution roles and resource limits in project definition
 - Use [core.async](https://github.com/clojure/core.async) for deferred completion
 - Smaller zip files with `:optimizations` `:advanced` support
 - Utilities for testing Lambda entrypoints off of EC2 ([see below](#testing))
 - [Blog post/tutorial](https://nervous.io/clojure/clojurescript/aws/lambda/node/lein/2015/07/05/lambda/) (and [another](https://nervous.io/clojure/clojurescript/node/aws/2015/08/09/chemtrails/))

N.B. If using advanced compilation alongside Node's standard library,
something like
[cljs-nodejs-externs](https://github.com/nervous-systems/cljs-nodejs-externs)
will be required

# Get Started

Using this project will require a recent [Node](https://nodejs.org/) runtime,
and a properly-configured (`aws configure`) [AWS
CLI](https://github.com/aws/aws-cli) installation **>= 1.7.31**.  Please run
`pip install --upgrade awscli` if you're using an older version (`aws
--version`).

```sh
$ lein new cljs-lambda my-lambda-project
$ cd my-lambda-project
$ lein cljs-lambda default-iam-role
$ lein cljs-lambda deploy
$ lein cljs-lambda invoke work-magic '{"variety": "black"}'
```
Or, put:

[![Clojars
Project](http://clojars.org/io.nervous/lein-cljs-lambda/latest-version.svg)](http://clojars.org/io.nervous/lein-cljs-lambda)

In your Clojurescript project's `:plugins` vector.

# Plugin Overview

## project.clj Excerpt

The `cljs-lambda` plugin enables the declaration of Lambda-deployable functions
from Leiningen project files:

```clojure
{...
 :cljs-lambda
 {:cljs-build-id "cljs-lambda-example"
  :aws-profile "XYZ"
  :defaults
  {:role "arn:aws:iam::151963828411:role/lambda_basic_execution"}
  :resource-dirs ["config"]
  :functions
  [{:name   "dog-bark"
    :invoke cljs-lambda-example.dog/bark}
   {:name   "cat-meow"
    :invoke cljs-lambda-example.cat/meow}]}}
```

 - If `:aws-profile` is present, the value will be passed as `--profile` to all invocations of the AWS CLI, otherwise the CLI's default profile will be used
 - `:resource-dirs` is a sequence of directories, with each appear at the top-level in the resulting zip-file

# Brief Function Examples

The most convenient way to write functions is with `async-lambda-fn`, a wrapper
for two-argument functions, each taking an EDN structure constructed from an
invocation's input JSON, and a map describing the invocation's context.

An `async-lambda-fn` is expected to return a `core.async` channel, with closure
signalling the termination of processing.  The wrapper accepts a single value:
`js/Error` instances result in failure, and all other JSON-serializable values
success.

It's not necessary that you use `core.async` - regular two argument functions
can be supplied, with more explicit and uncomfortable ways of signalling
completion - see [section below](#the-library).

## A Simple Function

```clojure
(ns cljs-lambda-example.cat
  (:require [cljs-lambda.util :refer [async-lambda-fn]]))

(def ^:export meow
  (async-lambda-fn
   (fn [{meow-target :name} context]
     (go
       (<! (async/timeout 1000)) ;; Act like we're doing something
       {:from "the-cat"
        :to meow-target
        :message "I'm  meowing at you"}))))
```

[And its associated project.clj](https://github.com/nervous-systems/cljs-lambda/blob/master/example/project.clj)

## AWS Integration with [eulalie](https://github.com/nervous-systems/eulalie)

This function retrieves the name it was invoked under then attempts to invoke
itself in order to recursively compute the factorial of its input:

```clojure
(require '[eulalie.lambda.util :as lambda])
(require '[eulalie.creds :as creds])

(def ^:export fac
  (async-lambda-fn
   (fn [n {fac :function-name}]
     (go
       (if (<= n 1)
         n
         (let [[tag result] (<! (lambda/request! (creds/env) fac (dec n)))]
           (* n result)))))))
```

See the
[eulalie.lambda.util](https://github.com/nervous-systems/eulalie/wiki/eulalie.lambda.util)
documentation for further details.

N.B. Functions interacting with AWS will require execution under roles with the
appropriate permissions, and will not execute under the placeholder IAM role
created by the plugin's `default-iam-role` task.

## Further Examples

Using SNS & SQS from Clojurescript Lambda functions is covered in [this working
example](https://github.com/nervous-systems/chemtrack-example/blob/master/lambda/chemtrack/lambda.cljs),
and the [blog
post](https://nervous.io/clojure/clojurescript/node/aws/2015/08/09/chemtrails/)
which discusses it.  The [project file](
https://github.com/nervous-systems/chemtrack-example/blob/master/project.clj)
should serve as a reasonable guide for deployment.

# Function Configuration w/ Plugin

The `:functions` vector within a `:cljs-lambda` map is comprised of maps, each
describing a function which'll be deployed when `lein cljs-lambda deploy` is
invoked.  An exhaustive example, with optional keys are listed at the end,
alongside defaults:

```clojure
{:name "the-lambda-function-name"
 :invoke my-cljs-namespace.module/fn
 :role "arn:..."
;; Optional:
 :description nil
 :create true
 :timeout 3 ;; seconds
 :memory-size 128} ;; MB
```

While `:role` is required, it (and any of the other keys) can be supplied as
defaults applying to all functions which don't otherwise specify values, via
placing e.g. `:defaults {:role ...}` in the `:cljs-lambda` map.

To synchronize config changes without deploying:

```sh
$ lein cljs-lambda update-config
```

`update-config` updates the remote configuration of all of the functions listed
in the project file.  A function's `:create` key, defaulting to `true`,
determines whether a `create-function` Lambda command will be issued if an
attempt is made to `deploy` a not-yet-existing Lambda function.

## cljsbuild

The plugin depends on `lein-cljsbuild`, and assumes a `:cljsbuild` section in
your `project.clj`.  A deployment or build via `cljs-lambda` invokes `cljsbuild` -
it'll run either the first build in the `:builds` vector, or the one
identified by `[:cljs-lambda :cljs-build-id]`.

 - Source map support will be enabled if the `:source-map` key of the active build
is `true`.
 - If `:optimizations` is set to `:advanced` on the active build, the zip output
 will be structured accordingly (i.e. it'll only contain `index.js` and the single
 compiler output file).
 - With `:advanced`, `*main-cli-fn*` is required to be set (i.e. `(set! *main-cli-fn* identity)`)

# The Library

[![Clojars Project](http://clojars.org/io.nervous/cljs-lambda/latest-version.svg)](http://clojars.org/io.nervous/cljs-lambda)

## Context Object

The second argument to a Lambda function is a map, having keys `:aws-request-id`
`:client-context` `:log-group-name` `:log-stream-name` & `:function-name`.
Regardless of whether `async-lambda-fn` is being used the following
context-handling functions can be used to immediately/manually terminate
execution:

 - `(cljs-lambda.util/fail! context & [error])`
 - `(cljs-lambda.util/succeed! context & [success-value])`
 - `(cljs-lambda.util/done! context & [error success-value])`

And a utility to return the number of milliseconds until the function's maximum
time allowance is exhausted:

 - `(cljs-lambda.util/msecs-remaining context)`

## Testing

While it's strongly suggested that Lambda functions are minimal abstractions of
the execution environment (i.e. input/output processing & delegation to generic
Clojurescript functions), there are times when it's going to make sense to test
the entry points off of EC2.

`cljs-lambda.util/mock-context` returns a map containing superficially plausible
values for the context's keys (and accepts an optional, single map argument
containing any overrides).  Passing the resulting map to an `async-lambda-fn`
will cause the returned channel to resolve to wrapped function's delivered value
(whether delivered via the wrapped function's channel, or manually via `fail!`,
etc.).  Results will be wrapped in a vector beginning with either `:succeed` or
`:fail`.

### Example

```clojure
(def ^:export testable
  (async-lambda-fn
   (fn [x context]
     ;; ...
     (+ x 1))))

(testable 5 (mock-context)) ;; => Channel containing [:succeed 6]
```

If the function being tested doesn't use `async-lambda-fn`, the value it
eventually returns (via `done!`, etc.) is readable from the async channel under
the mock context's `:cljs-lambda/resp-chan` key.

### Advanced Example

```clojure
(defn ^:export testable [_ {:keys [function-name] :as context}]
  (succeed!
   {:function-name function-name
    :remaining (msecs-remaining context)}))

;; succeed!, fail! and done! will return the mock context's response channel,
;; so we could read from either the result of 'testable', or from
;; the :cljs-lambda/resp-chan value in the context.
(testable nil (mock-context
               {:function-name "testable"
                :cljs-lambda/msecs-remaining (constantly 17)}))
;; => Channel containing
;;   [:succeed {:function-name "testable" remaining 17}]
```

# Invoking

## CLI

```sh
$ lein cljs-lambda invoke my-lambda-fn '{"arg1": "value" ...}'
```

## Programmatically

If you're interested in programmatically invoking Lambda functions from Clojure/Clojurescript, it's pretty easy with [eulalie](https://github.com/nervous-systems/eulalie):

```clojure
(eulalie.lambda.util/invoke!
 {:access-key ... :secret-key ... [:token :region etc.]}
 "my-lambda-fn"
 :request-response
 {:arg1 "value" :arg2 ["value"]})
```

## License

cljs-lambda is free and unencumbered public domain software. For more
information, see http://unlicense.org/ or the accompanying UNLICENSE
file.
