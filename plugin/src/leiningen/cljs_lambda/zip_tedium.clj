(ns leiningen.cljs-lambda.zip-tedium
  (:require [clojure.java.io :as io])
  (:import [java.io File]
           [java.util.zip ZipEntry ZipOutputStream]))

(defn- zip-entry [zip-stream file & [path]]
  (.putNextEntry zip-stream (ZipEntry. (or path (.getPath file))))
  (io/copy file zip-stream)
  (.closeEntry zip-stream))

(defn extension [file]
  (let [path (.getPath file)
        i    (.lastIndexOf path ".")]
    (and (< -1 i) (subs path (inc i)))))

(defn- zip-below [zip-stream dir]
  (println "Adding files from" (.getAbsolutePath dir))
  (dorun
   (for [file (file-seq dir)
         :when (and (.isFile file) (not= "zip" (extension file)))]
     (zip-entry zip-stream file))))

(defn- zip-resources [zip-stream dir]
  (let [prefix (-> dir .getAbsolutePath io/file .getParentFile .getAbsolutePath)]
    (doseq [file (remove #(.isDirectory %) (file-seq dir))]
      (let [path (subs (.getAbsolutePath file) (inc (count prefix)))]
        (zip-entry zip-stream file path)))))

(defmulti  stuff-zip (fn [_ compiler-opts _] (compiler-opts :optimizations)))

(defmethod stuff-zip :default [zip-stream {:keys [output-dir]} {:keys [index-path]}]
  (zip-entry zip-stream (io/file index-path) "index.js")
  (zip-below zip-stream (io/file output-dir))
  (zip-below zip-stream (io/file "node_modules")))

(defmethod stuff-zip :advanced [zip-stream {:keys [output-to]} {:keys [index-path]}]
  (zip-entry zip-stream (io/file index-path) "index.js")
  (zip-entry zip-stream (io/file output-to))
  (zip-below zip-stream (io/file "node_modules")))

(defn write-zip [{:keys [output-dir] :as compiler-opts}
                 {:keys [project-name zip-name resource-dirs] :as spec}]
  (let [zip-file (io/file output-dir zip-name)
        path     (.getAbsolutePath zip-file)]
    (println "Writing zip to" path)
    (.delete zip-file)
    (let [zip-stream (ZipOutputStream. (io/output-stream zip-file))]
      (stuff-zip zip-stream compiler-opts spec)
      (doseq [d resource-dirs]
        (zip-resources zip-stream (io/file d)))
      (.close zip-stream)
      path)))
