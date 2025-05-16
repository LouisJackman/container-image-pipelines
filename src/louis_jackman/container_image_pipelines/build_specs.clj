;;;;
;;;; # Build Image Specifications
;;;;

(ns louis-jackman.container-image-pipelines.build-specs
  (:require [clojure.java.io :as io]
            [clojure.string :as string]

            [louis-jackman.container-image-pipelines.context-metadata
             :refer [lookup-context-info]]
            [louis-jackman.container-image-pipelines.contexts
             :refer [name-from-context]]
            [louis-jackman.container-image-pipelines.images
             :refer [ImageRef->string
                     latest-ImageRef]]))



(defrecord BuildSpec
    ;; A specification that tells Docker's BuildKit how to build a context
    ;; directory.
    [dockerfile-path
     context-dir
     image-ref
     target-platforms
     only-local-platform
     add-provenance
     push])

(defn- context-dir->BuildSpec-defaults
  "Use metadata in a context directory to derive defaults for a build
  specification."
  [context-dir]
  (let [{:keys [version platforms]} (lookup-context-info context-dir)
        image-name (name-from-context context-dir)]
    {:context-dir context-dir
     :dockerfile-path (io/file context-dir "Dockerfile")
     :image-ref {:image-name image-name
                 :tag version}
     :target-platforms platforms}))

(defn BuildSpec-from-initial-specs-and-context
  "Convert a map of build specifications into a proper specification,
  defaulting missing values based on the build context directory."
  [{:keys [context-dir] :as spec}
   & {:keys [default-targets]}]
  (merge-with #(cond
                 (nil? %2) %1
                 (map? %2) (merge %1 %2)
                 :else %2)
              {:target-platforms default-targets}
              (context-dir->BuildSpec-defaults context-dir)
              spec))


(defn BuildSpec->build-args
  "To build an image based on provided specifications, generate the arguments
  to pass to `docker buildx build`."

  [{{:keys [registry image-name] :as image-ref} :image-ref
    :keys [context-dir
           target-platforms
           add-provenance
           only-local-platform
           push
           dockerfile-path]
    :as build-spec}
   & args]

  (let [versioned (ImageRef->string image-ref)
        latest (-> image-ref
                   latest-ImageRef
                   ImageRef->string)
        platform-flags (if (and push (not only-local-platform))
                         [(str "--platform=" (string/join \,
                                                          target-platforms))]
                         [])
        provenance-flags (if add-provenance
                           []
                           ["--provenance=false"])
        storage-strategy-flag (if push "--push" "--load")
        dockerfile-flag (str "--file=" dockerfile-path)]

    (concat [(str "--build-arg=REGISTRY=" registry)
             "--tag" versioned
             "--tag" latest]
            platform-flags
            ["--pull"]
            [storage-strategy-flag]
            provenance-flags
            [dockerfile-flag]
            args
            [(str context-dir)])))

