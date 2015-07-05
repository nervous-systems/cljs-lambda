# cljs-lambda

A Lein plugin, template and small Clojurescript library for exposing functions
via [AWS Lambda](http://aws.amazon.com/documentation/lambda/)

```sh
$ lein new cljs-lambda my-lambda-project
$ cd my-lambda-project
$ lein cljs-lambda default-iam-role
# project.clj now has a valid key in [:cljs-lambda :defaults :role]
$ lein cljs-lambda deploy
$ lein cljs-lambda invoke work-magic '{"variety": "black"}'
```

Or, put

[![Clojars
Project](http://clojars.org/io.nervous/lein-cljs-lambda/latest-version.svg)](http://clojars.org/io.nervous/lein-cljs-lambda)

In your Clojurescript project's `:plugins` vector.

## Notes

The plugin depends on `cljsbuild`, and assumes there is a `:cljsbuild` section
in your `project.clj`.  A deployment or build via `cljs-lambda` invokes
`cljsbuild` - it'll run either the first build in the `:builds` vector, or the
one identified by `[:cljs-lambda :cljs-build-id]`.  It could accept build
identifiers via the CLI, also.

Source map support will be enabled if the `:source-map` key of the active build
is `true`.

The following keys are valid for entries in `[:cljs-lambda :functions]`:

```clojure
{:name "the-lambda-function-name"
 :invoke my-cljs-namespace.module/fn
 :role "arn:..."
 [:description "I don't think this field sees much action"
  :timeout 3 ;; seconds
  :memory-size 128]} ;; MB
```

Values in `[:cljs-lambda :defaults]` will be merged into each function map.
