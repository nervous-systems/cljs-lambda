(ns cljs-lambda.test.help
  #? (:cljs (:require-macros [cljs-lambda.test.help])))

#? (:clj
    (defmacro deftest-async [test-name & body]
      `(cljs.test/deftest ~test-name
         (let [result# (do ~@body)]
           (cljs.test/async
            done#
            (.then  result# done#)
            (.catch result#
                    (fn [e#]
                      (cljs.test/is (not e#))
                      (done#))))))))



