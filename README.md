# cljs-lambda

[![Build Status](https://travis-ci.org/nervous-systems/cljs-lambda.svg?branch=master)](https://travis-ci.org/nervous-systems/cljs-lambda)

[AWS Lambda](http://aws.amazon.com/documentation/lambda/) is a service which
allows named functions to be directly invoked (via a client API), have their
execution triggered by a variety of AWS events (S3 upload, DynamoDB activity,
etc.) or to serve as HTTP endpoints (via [API
Gateway](https://aws.amazon.com/api-gateway/)).

This README serves to document a [Leiningen
plugin](https://github.com/nervous-systems/cljs-lambda/tree/master/plugin)
(`lein-cljs-lambda`), template (`cljs-lambda`) and [small
library](https://nervous.io/doc/cljs-lambda/) (`cljs-lambda`) to facilitate the
writing, deployment & invocation of Clojurescript Lambda functions.

The plugin can deploy functions itself, or the
excellent [Serverless](http://serverless.com) framework can be used,
via
[serverless-cljs-plugin](https://www.npmjs.com/package/serverless-cljs-plugin).

## Benefits

 - Low instance warmup penalty
 - Use promises, or asynchronous channels for deferred completion
 - `:optimizations` `:advanced` support, for smaller zip files*
 - Utilities for [testing Lambda entrypoints](https://nervous.io/doc/cljs-lambda/testing.html) off of EC2
 - Function publishing/versioning
 - [Serverless](http://serverless.com) integration

_N.B. If using advanced compilation alongside Node's standard library,
something like
[cljs-nodejs-externs](https://github.com/nervous-systems/cljs-nodejs-externs)
will be required_

## Status

This collection of projects is used extensively in production, for important
pieces of infrastructure.  While efforts are made to ensure backward
compatibility in the Leiningen plugin, the `cljs-lambda` API is subject to
breaking changes.

### Recent Changes

- `io.nervous/lein-cljs-lambda` 0.6.0 defaults the runtime of deployed functions
to `nodejs4.3`, unless this is overriden with `:runtime` in the fn-spec or on
the command-line.  While your functions will be backwards compatible, your AWS
CLI installation may require updating to support this change.

# Coordinates

## [Plugin](https://github.com/nervous-systems/cljs-lambda/tree/master/plugin)

[![Clojars
Project](http://clojars.org/io.nervous/lein-cljs-lambda/latest-version.svg)](http://clojars.org/io.nervous/lein-cljs-lambda)

## [Library](https://github.com/nervous-systems/cljs-lambda/tree/master/cljs-lambda)

[![Clojars Project](http://clojars.org/io.nervous/cljs-lambda/latest-version.svg)](http://clojars.org/io.nervous/cljs-lambda)

# Get Started

```sh
$ lein new cljs-lambda my-lambda-project
$ cd my-lambda-project
$ lein cljs-lambda default-iam-role
$ lein cljs-lambda deploy
### 500ms delay via a promise (try also "delay-channel" and "delay-fail")
$ lein cljs-lambda invoke work-magic \
  '{"spell": "delay-promise", "msecs": 500, "magic-word": "my-lambda-project-token"}'
... {:waited 500}
### Get environment varibles
$ lein cljs-lambda invoke work-magic \
  '{"spell": "echo-env", "magic-word": "my-lambda-project-token"}'
...
$ lein cljs-lambda update-config work-magic :memory-size 256 :timeout 66
```

## Serverless

To generate a minimal project:

```sh
$ lein new serverless-cljs my-lambda-project
```

# Documentation
 - [lein-cljs-lambda plugin README](https://github.com/nervous-systems/cljs-lambda/tree/master/plugin) / [reference](https://github.com/nervous-systems/cljs-lambda/wiki/Plugin-Reference)
 - [cljs-lambda library API docs](https://nervous.io/doc/cljs-lambda/)

## Older
 - [Clojurescript/Node on AWS Lambda](https://nervous.io/clojure/clojurescript/aws/lambda/node/lein/2015/07/05/lambda/) (blog post)
 - [Chasing Chemtrails w/ Clojurescript](https://nervous.io/clojure/clojurescript/node/aws/2015/08/09/chemtrails/) (blog post)

## Other
- [Example project](https://github.com/nervous-systems/cljs-lambda/tree/master/example/) (generated from template)

# Function Examples

(Using promises)

```clojure
(deflambda slowly-attack [{target :name} ctx]
  (p/delay 1000 {:to target :data "This is an attack"}))
```

## AWS Integration With [eulalie](https://github.com/nervous-systems/eulalie)

(Using core.async)

This function retrieves the name it was invoked under, then attempts to invoke
itself in order to recursively compute the factorial of its input:

```clojure
(deflambda fac [n {:keys [function-name] :as ctx}]
  (go
    (if (<= n 1)
      n
      (let [[tag result] (<! (lambda/request! (creds/env) function-name (dec n)))]
        (* n result)))))
```

See the
[eulalie.lambda.util](https://github.com/nervous-systems/eulalie/wiki/eulalie.lambda.util)
documentation for further details.

_N.B. Functions interacting with AWS will require execution under roles with the
appropriate permissions, and will not execute under the placeholder IAM role
created by the plugin's `default-iam-role` task._

## Further Examples

Using SNS & SQS from Clojurescript Lambda functions is covered in [this working
example](https://github.com/nervous-systems/chemtrack-example/blob/master/lambda/chemtrack/lambda.cljs),
and the [blog
post](https://nervous.io/clojure/clojurescript/node/aws/2015/08/09/chemtrails/)
which discusses it.

# Invoking

## CLI

```sh
$ lein cljs-lambda invoke my-lambda-fn '{"arg1": "value" ...}' [:region ...]
```

## Programmatically

If you're interested in programmatically invoking Lambda functions from
Clojure/Clojurescript, it's pretty easy with
[eulalie](https://github.com/nervous-systems/eulalie):

```clojure
(eulalie.lambda.util/request!
 {:access-key ... :secret-key ... [:token :region etc.]}
 "my-lambda-fn"
 {:arg1 "value" :arg2 ["value"]})
```

# License

cljs-lambda is free and unencumbered public domain software. For more
information, see http://unlicense.org/ or the accompanying UNLICENSE
file.
