;;;;
;;;; # Docker Contexts: Enumeration & Ordering
;;;;

(ns louis-jackman.container-image-pipelines.contexts
  (:require [clojure.java.io :as io]
            [clojure.string :as string]

            [louis-jackman.container-image-pipelines.context-metadata
             :refer [info-file-name]])

  (:import (java.io File)
           (java.nio.file Files Path)))

(set! *warn-on-reflection* true)



(defn- strip-ordering-prefix
  "Strip the prefix used to denote context build orders, e.g. `01` in
  `01-image-to-build`."
  [s]
  (string/replace-first s #"(?x) ^ ( \d+ - )" ""))

(defn name-from-context
  "Derive a context's from its directory name, discarding other information it
  embeds e.g. build ordering."
  [^File context]
  (-> context .getName strip-ordering-prefix))


(def ^:private dockerfile-name "Dockerfile")


(defn check-context-validity
  "Throw an exception if the provided context directory does not contain the
  necessary files to be inspected and potentially built."
  [context]
  (when-not (.exists (io/file context dockerfile-name))
    (throw (ex-info (str "all context directories must contain `"
                         dockerfile-name
                         "`, but it is missing here")
                    {:dir context})))
  (when-not (.exists (io/file context info-file-name))
    (throw (ex-info (str "all context directories must contain `"
                         info-file-name
                         "`, but it is missing here")
                    {:dir context}))))

(defn sort-contexts
  "For the provided project, lookup a sorted list of directories. Each is a
  context. As build ordering is based on directory name prefixes, this also
  provides their build order."
  [project-dir]
  (let [subdirs (->> (io/file project-dir "contexts")
                     .toPath
                     Files/list
                     stream-seq!
                     (map Path/.toFile)
                     (filter File/.isDirectory)
                     sort)]
    (run! check-context-validity subdirs)
    subdirs))

