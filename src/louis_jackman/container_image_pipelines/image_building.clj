;;;;
;;;; # Building an Image
;;;;

(ns louis-jackman.container-image-pipelines.image-building
  (:require [clojure.java.io :as io]
            [clojure.java.process :as process]
            [clojure.string :as string]

            [louis-jackman.container-image-pipelines.utils
             :refer [check-proc wait-for-proc docker]]
            [louis-jackman.container-image-pipelines.images
             :refer [ImageRef->string]]
            [louis-jackman.container-image-pipelines.build-specs
             :refer [BuildSpec->build-args]]
            [louis-jackman.container-image-pipelines.image-existence
             :refer [local-image-exists? registry-image-exists?]]))

(set! *warn-on-reflection* true)



(def ^:private docker-build
  "Run the `docker` process, providing initial `buildx` and `build`
  arguments."
  (partial docker "buildx" "build"))

(defrecord ImageBuilder
    ;; Knows how to build Docker images given environmental constraints,
    ;; provided it's given the flags.
    [add-provenance])

(def ^:private missing-provenance-err-msg
  "unknown flag: --provenance")

(def ^:private env-supports-provenance?
  "Does the installed Docker support provenance features?"
  (memoize
   (fn []
     (let [proc (docker {:out :discard
                         :err :pipe}
                        "build"
                        "--provenance=false"
                        "--help")
           supports-provenance (->>
                                proc
                                process/stderr
                                io/reader
                                line-seq
                                (filter #(string/includes? % missing-provenance-err-msg))
                                not)]
       (and (zero? (wait-for-proc proc))
            supports-provenance)))))

(def ImageBuilder-from-env
  "Derive an image builder from the constraints of the current environment."
  (memoize
   (fn []
     (map->ImageBuilder {;; If the environment's Docker does not support
                         ;; provenance, assume such metadata should not be
                         ;; uploaded to the container registry.
                         :add-provenance (env-supports-provenance?)}))))

(defn build-image
  "Build a Docker image based on the build specifications."
  [{:keys [push only-local-platform] :as build-spec}
   & {{:keys [add-provenance]} :with
      :keys [args]}]

  (let [build-msg-suffix (if push
                           (if only-local-platform
                             "building and pushing for just the current platform…"
                             "building and pushing for all supported platforms…")
                           "building for only the local platform and loading locally…")
        {:keys [image-ref] :as spec} (assoc build-spec :add-provenance add-provenance)
        flags (apply BuildSpec->build-args (concat [spec] args))]
    (println (str "Image "
                  (ImageRef->string image-ref)
                  " is missing; "
                  build-msg-suffix))
    (check-proc (apply docker-build flags))))

(defn build-image-if-missing
  "Build a Docker image based on the build specifications, if it is
  missing."
  [{:keys [push image-ref] :as build-spec}
   & {:keys [image-existence-checker image-builder args]}]

  (let [exists (if push
                 (registry-image-exists?
                  image-ref
                  :with image-existence-checker)
                 (local-image-exists? image-ref))]
    (if exists
      (println (str "Image "
                    (ImageRef->string image-ref)
                    " already exists "
                    (if push "on the registry" "locally")
                    "; skipping…"))
      (build-image build-spec
                   :args args
                   :with image-builder))))

