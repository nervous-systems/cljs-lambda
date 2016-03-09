# cljs-lambda

[AWS Lambda](http://aws.amazon.com/documentation/lambda/) is a service which
allows named functions to be directly invoked (via a client API), have their
execution triggered by a variety of AWS events (S3 upload, DynamoDB activity,
etc.) or to serve as HTTP endpoints (via [API
Gateway](https://aws.amazon.com/api-gateway/)).

This README serves to document a Leiningen plugin (`lein-cljs-lambda`), template
(`cljs-lambda`) and small library (`cljs-lambda`) to facilitate the writing,
deployment & invocation of Clojurescript Lambda functions

### Benefits

 - Low instance warmup penalty
 - Ability to specify execution roles and resource limits in project definition
 - Use [core.async](https://github.com/clojure/core.async) for deferred completion
 - `:optimizations` `:advanced` support, for smaller zip files
 - Utilities for testing Lambda entrypoints off of EC2 ([see below](#testing))
 - Parallel deployments
 - Function publishing/versioning
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
...
$ lein cljs-lambda update-config work-magic :memory-size 256 :timeout 66
```

# Plugin Overview

The `cljs-lambda` plugin enables the declaration of Lambda-deployable functions
from Leiningen project files.

[![Clojars
Project](http://clojars.org/io.nervous/lein-cljs-lambda/latest-version.svg)](http://clojars.org/io.nervous/lein-cljs-lambda)

## project.clj Excerpt

```clojure
{...
 :cljs-lambda
 {:cljs-build-id "cljs-lambda-example"
  :defaults {:role "arn:aws:iam::151963828411:role/..."}
  :resource-dirs ["config"]
  :region ... ;; This'll default to your AWS CLI profile's region
  :functions
  [{:name   "dog-bark"
    :invoke cljs-lambda-example.dog/bark}
   {:name   "cat-meow"
    :invoke cljs-lambda-example.cat/meow}]}}
```

The wiki's [plugin
reference](https://github.com/nervous-systems/cljs-lambda/wiki/Plugin-Reference)
has more details.

# Brief Function Examples

The most convenient way to write functions is with `async-lambda-fn`, a wrapper
for two-argument functions, each taking an EDN representation of the Lambda
invocation's input JSON, and a map describing the invocation's context.

An `async-lambda-fn` is expected to return a `core.async` channel, and populate
it with a single value. `js/Error` instances result in failure, and all other
JSON-serializable values success.

It's not necessary that you use `core.async` or `async-lambda-fn` - regular two
argument functions can be exposed to Lambda, with more explicit and
uncomfortable ways of signalling completion - see [section below](#the-library).

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

## AWS Integration with [eulalie](https://github.com/nervous-systems/eulalie)

This function retrieves the name it was invoked under, then attempts to invoke
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
which discusses it.

# Function Configuration w/ Plugin

The `:functions` vector within a `:cljs-lambda` map is comprised of maps, each
describing a function which'll be deployed when `lein cljs-lambda deploy` is
invoked.  An example:

```clojure
{:name "the-lambda-function-name"
 :invoke my-cljs-namespace.module/fn
 :role "arn:..."
;; Optional w/ defaults:
 :region ... ;; Defers to [:cljs-lambda :region] or AWS CLI
 :description nil
 :create true
 :timeout 3 ;; seconds
 :memory-size 128} ;; MB
```

The wiki's [plugin
reference](https://github.com/nervous-systems/cljs-lambda/wiki/Plugin-Reference)
has more details.

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

The second argument to each Lambda-exposed function is a "context" map, having
keys `:aws-request-id` `:client-context` `:log-group-name` `:log-stream-name` &
`:function-name`.  Regardless of whether `async-lambda-fn` is being used, the
following context-handling functions will immediately/manually terminate
execution:

```clojure
(cljs-lambda.util/fail! context & [error])
(cljs-lambda.util/succeed! context & [success-value])
(cljs-lambda.util/done! context & [error success-value])

;; If you start getting anxious

(cljs-lambda.util/msecs-remaining context)
```

## Testing

While it's strongly suggested that Lambda functions are minimal abstractions of
the execution environment (i.e. input/output processing & delegation to generic
Clojurescript functions), there are times when it's going to make sense to test
the entry points off of EC2.

`cljs-lambda.util/mock-context` returns a map containing superficially plausible
values for the context's keys (and accepts an optional, single map argument
containing any overrides).  Passing the resulting map to an `async-lambda-fn`
will cause the returned channel to resolve to the wrapped function's delivered
value (whether delivered via the wrapped function's channel, or manually via
`fail!`, etc.).  Results will be placed in a vector beginning with either
`:succeed` or `:fail`, because it's not always possible to determine this from
the value itself.

### Example

```clojure
(def ^:export testable
  (async-lambda-fn
   (fn [x context]
     (go
       ;; TODO Think hard
       (inc 1)))))

(testable 5 (mock-context)) ;; => Channel containing [:succeed 6]
```

# Invoking

## CLI

```sh
$ lein cljs-lambda invoke my-lambda-fn '{"arg1": "value" ...}' [:region ...]
```

## Programmatically

If you're interested in programmatically invoking Lambda functions from Clojure/Clojurescript, it's pretty easy with [eulalie](https://github.com/nervous-systems/eulalie):

```clojure
(eulalie.lambda.util/request!
 {:access-key ... :secret-key ... [:token :region etc.]}
 "my-lambda-fn"
 {:arg1 "value" :arg2 ["value"]})
```

## License

cljs-lambda is free and unencumbered public domain software. For more
information, see http://unlicense.org/ or the accompanying UNLICENSE
file.
