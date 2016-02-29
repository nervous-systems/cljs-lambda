(ns leiningen.cljs-lambda.args)

(def ^:dynamic *region* nil)
(def ^:dynamic *aws-profile* nil)

(let [coercions {:create      #(Boolean/parseBoolean %)
                 :timeout     #(Integer/parseInt %)
                 :memory-size #(Integer/parseInt %)
                 :parallel    #(Integer/parseInt %)}
      ->arg #(keyword (subs % 1))]
  (defn split-args [l bool-arg?]
    (loop [pos [] kw {} [k & l] l]
      (cond (not k)             [pos kw]
            (not= \: (first k)) (recur (conj pos k) kw l)
            :else (let [arg (->arg k)]
                    (if (bool-arg? arg)
                      (recur pos (assoc kw arg true) l)
                      (let [[v & l] l
                            coerce  (coercions arg identity)]
                        (recur pos (assoc kw arg (coerce v)) l))))))))
