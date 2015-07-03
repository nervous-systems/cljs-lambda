(ns leiningen.cljs-lambda.zip-tedium
  (:require [clojure.java.io :as io])
  (:import [java.io File]
           [java.util.zip ZipEntry ZipOutputStream]))

(defn- zip-entry [zip-stream file & [path]]
  (.putNextEntry zip-stream (ZipEntry. (or path (.getPath file))))
  (io/copy file zip-stream)
  (.closeEntry zip-stream))

(defn extension [file]
  (let [path (.getPath file)]
    (when-let [i (.lastIndexOf path ".")]
      (subs path (inc i)))))

(defn- zip-below [zip-stream dir]
  (println "Adding files from" (.getAbsolutePath dir))
  (doseq [file (file-seq dir)]
    (when (and (.isFile file)
               (not= (extension file) "zip"))
      (zip-entry zip-stream file))))

(defn write-zip [{:keys [project-name index-path out-path zip-name]}]
  (let [zip-file   (File/createTempFile project-name nil)
        zip-stream (ZipOutputStream. (io/output-stream (.getPath zip-file)))]
    (zip-entry zip-stream (io/file index-path) "index.js")
    (zip-below zip-stream (io/file out-path))
    (zip-below zip-stream (io/file "node_modules"))
    (.close zip-stream)
    (let [destination-file (io/file out-path zip-name)
          path (.getAbsolutePath destination-file)]
      (println "Writing zip to" path)
      (.delete destination-file)
      (.renameTo zip-file destination-file)
      path)))
