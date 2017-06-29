# lein-cljs-lambda

The `lein-cljs-lambda` plugin enables the declaration of Lambda-deployable
functions from Leiningen project files.

[![Clojars
Project](http://clojars.org/io.nervous/lein-cljs-lambda/latest-version.svg)](http://clojars.org/io.nervous/lein-cljs-lambda)

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

# project.clj Excerpt

```clojure
{...
 :cljs-lambda
 {:defaults {:role "arn:aws:iam::151963828411:role/..."}
  :resource-dirs ["config"]
  :managed-deps false
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

# Function Configuration

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
 :memory-size 128 ;; MB
 :vpc {:subnets [] :security-groups []}
 :dead-letter "arn:..."
 :env {"VAR_A" "VALUE_A"
       "VAR_B" "VALUE_B"}}
```

**NOTE:** Environment variables are case sensitive. You can provide `VAR_A` and `VAR_a`. Just be careful.

The wiki's [plugin
reference](https://github.com/nervous-systems/cljs-lambda/wiki/Plugin-Reference)
has more details.

## Building Your Project

`lein-cljs-lambda` supports either invoking the Clojurescript compiler directly,
or using `cljsbuild`.  If neither `:compiler` nor `:cljs-build-id` are present in
a project's `:cljs-lambda` map, cljsbuild is assumed, and the default/first
build will be used.

 - Source map support will be enabled if the `:source-map` key of the active build
is `true`.
 - If `:optimizations` is set to `:advanced` on the active build, the zip output
 will be structured accordingly (i.e. it'll only contain `index.js` the single
 compiler output file, and the source map, if any).
 - With `:advanced`, `*main-cli-fn*` is required to be set (i.e. `(set! *main-cli-fn* identity)`)
