;;;;
;;;; # Images
;;;;

(ns louis-jackman.container-image-pipelines.images)



(def ^:private latest-tag "latest")

(defrecord ImageRef
    ;; A reference to a fully-qualified Docker image.
    [registry image-name tag])

(def ImageRef->string
  "Convert an image reference into a string format widely understood by Docker
  tooling."
  (memoize
   (fn [{:keys [registry image-name tag]}]
     (str registry \/ image-name \: tag))))

(def latest-ImageRef
  "Discard an image's tag, yielding a replacement with the `latest` tag
  instead."
  (memoize
   (fn [image-ref]
     (merge image-ref {:tag latest-tag}))))

