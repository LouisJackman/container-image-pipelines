;;;;
;;;; # Docker Context Metadata Manipulation
;;;;

(ns louis-jackman.container-image-pipelines.context-metadata
  (:require [clojure.java.io :as io]

            [clojure.edn :as edn])

  (:import java.io.PushbackReader))

(set! *warn-on-reflection* true)



(def info-file-name "info.edn")


(defn lookup-context-info
  "Lookup metadata from a context's directory."
  [context-dir]
  (with-open [reader (-> (io/file context-dir info-file-name)
                         io/reader
                         PushbackReader.)]
    (edn/read reader)))


(defn save-context-info
  "Save metadata into a context's directory."
  [context-dir metadata]
  (with-open [writer (-> (io/file context-dir info-file-name)
                         io/writer)]
    (binding [;; Rationale:
              ;; https://nitor.com/en/articles/pitfalls-and-bumps-clojures-extensible-data-notation-edn
              *print-namespace-maps* false

              *out* writer]
      (pr metadata))))

