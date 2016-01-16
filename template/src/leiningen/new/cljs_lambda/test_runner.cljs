(ns {{name}}.test-runner
 (:require [doo.runner :refer-macros [doo-tests]]
           [{{name}}.core-test]))

(doo-tests
 '{{name}}.core-test)
