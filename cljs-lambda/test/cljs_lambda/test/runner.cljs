(ns cljs-lambda.test.runner
 (:require [doo.runner :refer-macros [doo-tests]]
           [cljs-lambda.test.util]))

(doo-tests 'cljs-lambda.test.util)
