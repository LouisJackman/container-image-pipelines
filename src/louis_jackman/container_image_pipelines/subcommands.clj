;;;;
;;;; # Subcommands
;;;;

(ns louis-jackman.container-image-pipelines.subcommands
  (:require [clojure.string :as string]

            [louis-jackman.container-image-pipelines.utils
             :refer [*http-client*
                     check-proc
                     docker
                     system-newline]]
            [louis-jackman.container-image-pipelines.images
             :refer [ImageRef->string
                     map->ImageRef
                     latest-ImageRef]]
            [louis-jackman.container-image-pipelines.context-metadata
             :refer [lookup-context-info]]
            [louis-jackman.container-image-pipelines.contexts
             :refer [name-from-context
                     sort-contexts]]
            [louis-jackman.container-image-pipelines.image-existence
             :refer [map->ImageExistenceChecker
                     local-image-exists?
                     registry-image-exists?]]
            [louis-jackman.container-image-pipelines.build-specs
             :refer [BuildSpec-from-initial-specs-and-context]]
            [louis-jackman.container-image-pipelines.image-building
             :refer [ImageBuilder-from-env
                     build-image-if-missing]]
            [louis-jackman.container-image-pipelines.version-update-cascading
             :refer [patch-derivative-images-of]])

  (:import (java.io File InputStream PushbackReader)
           (java.nio.file Files Path Paths)
           (java.net.http HttpClient
                          HttpRequest
                          HttpResponse
                          HttpResponse$BodyHandlers)
           (java.util.regex Pattern)))



;;;
;;; Building All Images
;;;

(def ^:private build-error-prefix
  (str "Building failed. As `stop-on-first-error` is disabled, the remaining "
       "images were attempted to be built afterwards. Now they're all "
       "finished, the errors were:"
       system-newline))

(defn build-images
  "Build a whole project directory of container image definitions. Each image
  has its context directory, with its usual Dockerfile and surrounding
  context. In addition, it contains an `info.edn` file that documents the
  current version and, if required, overrides of the default target
  platforms. If images with the same version already exists on the registry,
  they are skipped. Images are built in their declared build order,
  sequentially."
  [& {:keys [project-dir
             registry
             default-platforms
             push
             only-local-platform
             secure-manifest-inspections
             stop-on-first-error
             extra-build-args]}]

  (letfn [(attempt-build [context]
            (let [spec (BuildSpec-from-initial-specs-and-context
                        {:image-ref {:registry registry}
                         :push push
                         :context-dir context
                         :only-local-platform only-local-platform}
                        :default-targets default-platforms)
                  checker (map->ImageExistenceChecker
                           {:force-secure-inspections secure-manifest-inspections})
                  image-builder (ImageBuilder-from-env)]
              (build-image-if-missing
               spec
               :image-existence-checker checker
               :image-builder image-builder
               :args extra-build-args)))]

    (with-open [http-client (HttpClient/newHttpClient)]
      (binding [*http-client* http-client]

        (if stop-on-first-error
          (run! attempt-build (sort-contexts project-dir))

          ;; Otherwise, accumulate errors throughout. Throw a combined
          ;; exception at the end.
          (loop [errors []
                 contexts (sort-contexts project-dir)]

            (if (empty? contexts)
              ;; Finish, throwing a combined exception if any errors occured.
              (if-not (empty? errors)
                (let [error-msg (string/join (str system-newline "\t")
                                             (map ex-message errors))]
                  (throw (ex-info (str build-error-prefix error-msg)
                                  {:causes errors}))))

              ;; Keep going, accumulating an addition error if one occurs.
              (let [[context & rest-of-contexts] contexts
                    error (try
                            (attempt-build context)
                            nil
                            (catch Exception e e))
                    accumulated-errors (if (nil? error)
                                         errors
                                         (conj errors error))]
                (recur accumulated-errors rest-of-contexts)))))))))


;;;
;;; Pulling from a Registry
;;;

(defn pull-from-registry
  "Pull from the specified registry into the local store. Use a project
  directory of container image definitions to understand which images (and
  versions) are expected."
  [& {:keys [project-dir registry secure-manifest-inspections]}]

  (let [checker (map->ImageExistenceChecker
                 {:force-secure-inspections secure-manifest-inspections})
        http-client (HttpClient/newHttpClient)]
    (binding [*http-client* http-client]
      (doseq [context (sort-contexts project-dir)]
        (let [{:keys [version]} (lookup-context-info context)
              image-name (name-from-context context)
              image (map->ImageRef {:registry registry
                                    :image-name image-name
                                    :tag version})
              image-string (ImageRef->string image)
              latest (latest-ImageRef image)
              latest-string (ImageRef->string latest)]
          (if (registry-image-exists? image)
            (do
              (println (str image-string
                            " exists on registry "
                            registry
                            "; pulling to the local store…"))
              (check-proc (docker "pull" image-string))
              (check-proc (docker "tag" image-string latest-string)))
            (println (str image-string
                          " is missing on the registry "
                          registry
                          "; won't attempt to pull it into the local "
                          "store"))))))))


;;;
;;; Uploading to a Local Registry
;;;

(defn upload-to-local-registry
  "Pull images from the specified remote registry and upload them to the
  provided local registry."
  [& {:keys [project-dir
             local-registry
             remote-registry
             secure-manifest-inspections]}]

  (let [checker (map->ImageExistenceChecker
                 {:force-secure-inspections secure-manifest-inspections})
        http-client (HttpClient/newHttpClient)]
    (binding [*http-client* http-client]
      (doseq [context (sort-contexts project-dir)]
        (let [{:keys [version]} (lookup-context-info context)
              image-name (name-from-context context)
              remote-image (-> (map->ImageRef {:registry remote-registry
                                               :image-name image-name
                                               :tag version})
                               ImageRef->string)
              local-image (map->ImageRef {:registry local-registry
                                          :image-name image-name
                                          :tag version})
              local-image-string (ImageRef->string local-image)
              latest-local-image (-> local-image
                                     latest-ImageRef
                                     ImageRef->string)]
          (if (local-image-exists? local-image)
            (println (str local-image-string
                          " already exists on the local registry "
                          local-registry
                          "; skipping…"))
            (do
              (println (str local-image-string
                            " is missing on the local registry "
                            local-registry
                            "; pushing…"))
              (check-proc (docker "pull" remote-image))
              (check-proc (docker "tag" remote-image local-image-string))
              (check-proc (docker "tag" remote-image latest-local-image))
              (check-proc (docker "rmi" remote-image))
              (check-proc (docker "push" local-image-string))
              (check-proc (docker "push" latest-local-image)))))))))


;;;
;;; Cascading Version Updates
;;;

(defn cascade-version-updates
  "Cascade version updates of images down throughout all their dependents. If
  those dependents are updated to refer to the updated parent, update their
  versions too. This effect recursively occurs to the leaf images."
  [& {:keys [project-dir]}]
  (println "Auto-cascading versions of derived images…")
  (doseq [context (sort-contexts project-dir)]
    (let [image-name (name-from-context context)
          {:keys [version]} (lookup-context-info context)]
      (patch-derivative-images-of :project-dir project-dir
                                  :parent image-name
                                  :to-version version)))
  (println "Auto-cascade complete."))

