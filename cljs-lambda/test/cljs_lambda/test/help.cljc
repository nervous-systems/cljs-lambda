(ns cljs-lambda.test.help
  #? (:cljs (:require-macros [cljs-lambda.test.help])))

#? (:clj
    (defmacro deftest-async [test-name & body]
      `(cljs.test/deftest ~test-name
         (let [result# (do ~@body)]
           (cljs.test/async
            done#
            (promesa.core/branch result#
              done#
              (fn [e#]
                (println (.. e# -stack))
                (cljs.test/is (not e#))
                (done#))))))))
