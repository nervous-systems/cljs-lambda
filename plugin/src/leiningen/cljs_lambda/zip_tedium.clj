(ns leiningen.cljs-lambda.zip-tedium
  (:require [leiningen.cljs-lambda.logging :refer [log]]
            [clojure.java.io :as io])
  (:import [java.io File]
           [java.nio.file Files LinkOption]
           [java.nio.file.attribute PosixFilePermission]
           [org.apache.commons.compress.archivers.zip
            ZipArchiveEntry
            ZipArchiveOutputStream]))

;; I don't have time to explain
(def ^:dynamic *print-files* false)

(defn- get-posix-mode [file]
  (let [no-follow (into-array [LinkOption/NOFOLLOW_LINKS])
        perms (Files/getPosixFilePermissions (.toPath file) no-follow)]
    (cond-> 0
      (contains? perms PosixFilePermission/OWNER_READ)     (bit-set 8)
      (contains? perms PosixFilePermission/OWNER_WRITE)    (bit-set 7)
      (contains? perms PosixFilePermission/OWNER_EXECUTE)  (bit-set 6)
      (contains? perms PosixFilePermission/GROUP_READ)     (bit-set 5)
      (contains? perms PosixFilePermission/GROUP_WRITE)    (bit-set 4)
      (contains? perms PosixFilePermission/GROUP_EXECUTE)  (bit-set 3)
      (contains? perms PosixFilePermission/OTHERS_READ)    (bit-set 2)
      (contains? perms PosixFilePermission/OTHERS_WRITE)   (bit-set 1)
      (contains? perms PosixFilePermission/OTHERS_EXECUTE) (bit-set 0))))

(defn- zip-entry [zip-stream file & [path]]
  (let [path  (or path (.getPath file))
        entry (ZipArchiveEntry. file path)]
    (when *print-files*
      (println (.getAbsolutePath file))
      (println path))
    (.setUnixMode entry (get-posix-mode file))
    (.putArchiveEntry zip-stream entry)
    (io/copy file zip-stream)
    (.closeArchiveEntry zip-stream)))

(defn extension [file]
  (let [path (.getPath file)]
    (when-let [i (.lastIndexOf path ".")]
      (subs path (inc i)))))

(defn- zip-below [zip-stream dir]
  (log :verbose "Adding files from" (.getAbsolutePath dir))
  (doseq [file (file-seq dir)]
    (when (and (.isFile file)
               (not= (extension file) "zip"))
      (zip-entry zip-stream file))))

(defn- zip-resources [zip-stream dir]
  (let [prefix (.. (io/file (.getAbsolutePath dir)) getParentFile getAbsolutePath)]
    (doseq [file (rest (file-seq dir))]
      (let [path (subs (.getAbsolutePath file) (inc (count prefix)))]
        (when-not (.isDirectory file)
          (zip-entry zip-stream file path))))))

(defmulti  stuff-zip
  (fn [_ {:keys [optimizations]} _]
    (if (#{:simple :advanced} optimizations)
      :single-file
      :default)))

(defmethod stuff-zip :default [zip-stream {:keys [output-dir]} {:keys [index-path]}]
  (zip-entry zip-stream (io/file index-path) "index.js")
  (zip-below zip-stream (io/file output-dir))
  (zip-below zip-stream (io/file "node_modules")))

(defmethod stuff-zip :single-file [zip-stream {:keys [output-to source-map]} {:keys [index-path]}]
  (zip-entry zip-stream (io/file index-path) "index.js")
  (zip-entry zip-stream (io/file output-to))
  (when (string? source-map)
    (zip-entry zip-stream (io/file source-map)))
  (zip-below zip-stream (io/file "node_modules")))

(defn write-zip [{:keys [output-dir] :as compiler-opts}
                 {:keys [project-name zip-name resource-dirs] :as spec}]
  (let [zip-file (if (spec :force-path)
                   (io/file (spec :force-path))
                   (io/file output-dir zip-name))
        path     (.getAbsolutePath zip-file)]
    (log :verbose "Writing zip to" path)
    (.delete zip-file)
    (let [zip-stream (ZipArchiveOutputStream. zip-file)]
      (binding [*print-files* (spec :print-files)]
        (stuff-zip zip-stream compiler-opts spec)
        (doseq [d resource-dirs]
          (zip-resources zip-stream (io/file d))))
      (.close zip-stream)
      path)))
