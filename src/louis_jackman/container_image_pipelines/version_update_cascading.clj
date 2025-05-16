;;;;
;;;; # Cascading Version Updates
;;;;

(ns louis-jackman.container-image-pipelines.version-update-cascading
  (:require [clojure.java.io :as io]
            [clojure.string :as string]

            [louis-jackman.container-image-pipelines.context-metadata
             :refer [lookup-context-info save-context-info]]
            [louis-jackman.container-image-pipelines.utils
             :refer [system-newline]]
            [louis-jackman.container-image-pipelines.contexts
             :refer [sort-contexts]])

  (:import java.io.File
           java.nio.file.Files
           java.util.regex.Pattern))



(def ^:private ^Pattern from-clause-prefix
  #"(?x)
  ^
  \s*
  (?i:FROM)
  \s+
  \$
  \{ \s* REGISTRY \s* \}
  /")

(defn- replace-dockerfile
  "Propose a replacement of a Dockerfile's context, to repoint its parent
  image references to a new version. May return the same content if it does
  not refer to that parent, or if the referred-to version hasn't changed."
  [& {:keys [^File context parent-image to-version]}]
  (let [from-image (re-pattern (str "(?x)"
                                    "  (?<fromClause>"
                                    (.pattern from-clause-prefix)
                                    (Pattern/quote parent-image)
                                    "  :"
                                    ")"))
        from-image-with-version (re-pattern (str "(?x)"
                                                 (.pattern from-image)
                                                 "\\d+ \\."
                                                 "\\d+ \\."
                                                 "\\d+"
                                                 "(?<rest> .*)"))

        replace-line (fn [line]
                       (if-let [[_ from-clause rest]
                                (re-matches from-image-with-version line)]
                         (str from-clause
                              to-version
                              rest
                              system-newline)
                         (throw (ex-info "standard version tag missing"
                                         {:image-derivation line
                                          :context context}))))

        replace-lines (fn [lines]
                        (for [line lines
                              :let [original-line (str line system-newline)]]
                          (if (re-find from-image line)
                            (replace-line line)
                            original-line)))]
    (with-open [dockerfile (-> context
                               .toPath
                               (.resolve "Dockerfile")
                               .toFile
                               io/reader)]
      (-> dockerfile line-seq replace-lines string/join))))

(def ^:private patch-version-pattern
  #"(?x)
    \.
    (?<patch> \d+ )
    $")

(defn- replace-version
  "Propose a replacement for the version metadata of a context; specifically,
  increment the patch version by one."
  [& {:keys [context]}]
  (let [{:keys [version]} (lookup-context-info context)]
    (if-let [[_ patch] (re-find patch-version-pattern version)]
      (->> patch
           parse-long
           inc
           str
           (string/replace version #"\d+$"))
      (throw (ex-info "invalid version string"
                      {:string version})))))

(defn- backup-and-replace
  "Backup a file, using a `.bk` suffix. Then replace the original with
  `replacement-content`."
  [& {:keys [^File file replacement-content]}]
  (let [original (.toPath file)
        backup (->> (str (.getFileName original) ".bk")
                    (.resolveSibling original)
                    .toFile)]
    (io/copy file backup)
    (io/copy replacement-content file)))

(defn patch-derivative-images-of
  "For all contexts in `project-dir`, patch all references to `parent` by
  bumping the reference's versions to `to-version`. In turn, bump the versions
  of the modified contexts to reflect that."
  [& {:keys [project-dir to-version parent]}]

  (doseq [^File context (sort-contexts project-dir)]
    (let [dockerfile (-> context
                         .toPath
                         (.resolve "Dockerfile")
                         .toFile)
          {:keys [version] :as context-meta} (lookup-context-info context)
          dockerfile-replacement (replace-dockerfile :context context
                                                     :parent-image parent
                                                     :to-version to-version)
          dockerfile-content (-> dockerfile .toPath Files/readString)]
      (when (not= dockerfile-content dockerfile-replacement)
        (println (str "Patching "
                      dockerfile
                      "'s reference to "
                      parent
                      " up to "
                      to-version))
        (let [version-replacement (replace-version :context context)]
          (backup-and-replace :file dockerfile
                              :replacement-content dockerfile-replacement)
          (save-context-info context
                             (assoc context-meta
                                    :version version-replacement)))))))

