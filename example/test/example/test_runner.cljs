(ns example.test-runner
 (:require [doo.runner :refer-macros [doo-tests]]
           [example.core-test]))

(doo-tests
 'example.core-test)
