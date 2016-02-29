(ns leiningen.cljs-lambda.logging)

(def ^:private log-levels {:error 1 :verbose 0})

(def ^:dynamic *log-level* :verbose)

(defn log [level & args]
  (when (<= (log-levels *log-level*) (log-levels level))
    (binding [*out* *err*]
      (apply println args))))
