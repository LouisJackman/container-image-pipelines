;;;;
;;;; # Image Existence Checking
;;;;

(ns louis-jackman.container-image-pipelines.image-existence
  (:require [clojure.java.io :as io]
            [clojure.java.process :as process]
            [clojure.string :as string]

            [clojure.data.json :as json]

            [louis-jackman.container-image-pipelines.utils
             :refer [*http-client* docker http-resp-ok? wait-for-proc]]
            [louis-jackman.container-image-pipelines.images
             :refer [ImageRef->string]])

  (:import (java.net.http HttpResponse$BodyHandlers HttpRequest)))



(defrecord ImageExistenceChecker
    ;; What's necessary to know how to check for the existence of container
    ;; images.
    [force-secure-inspections])

(defn local-image-exists?
  "Does an image exist in the local container store? Registries are not considered."
  [{:keys [registry image-name tag]}]
  (let [name (str registry "/" image-name)
        proc (docker {:out :pipe} "images")
        match (->> proc
                   process/stdout
                   io/reader
                   line-seq
                   (drop 1)
                   (map #(string/split % #"\s+"))
                   (map (partial take 2))
                   (some (fn [[parsed-name parsed-tag]]
                           (and (= parsed-name name) (= parsed-tag tag)))))]
    (and (zero? (wait-for-proc proc))
         match)))

(defn- registry-image-exists-according-to-cmd?
  "Does an image exist in the registry, according to `docker manifest
  inspect`? For reasons not entirely understood, this sometimes yields
  different results to querying the Docker HTTP API of the registry."
  [image-ref
   & {{:keys [force-secure-inspections]} :with}]
  (let [image (ImageRef->string image-ref)
        secure-flag (if force-secure-inspections
                      []
                      ["--insecure"])]
    (-> (apply docker (concat ["manifest" "inspect"] secure-flag [image]))
        wait-for-proc
        zero?)))

(defn- registry-image-exists-according-to-api?
  "Does an image exist in the registry, according to the registry's Docker
  HTTP API? For reasons not entirely understood, this sometimes yields
  different results to querying via `docker manifest inspect`."
  [{:keys [registry image-name tag]}
   & {{:keys [force-secure-inspections]} :with}]

  (let [proto (if force-secure-inspections "https" "http")
        url (str proto "://" registry "/v2/" image-name "/tags/list")
        uri (-> url io/as-url .toURI)
        req (-> uri HttpRequest/newBuilder .build)]

      (let [resp (.send *http-client* req (HttpResponse$BodyHandlers/ofInputStream))]
        (with-open [body (-> resp .body io/reader)]
          (and (http-resp-ok? resp)
               (let [tags (-> body json/read (get "tags"))]
                 (some (partial = tag) tags)))))))

(defn registry-image-exists?
  "Does an image exist in the registry, according to _either_ `docker manifest
  inspect` or the registry's Docker HTTP API?"
  [& args]
  (or (apply registry-image-exists-according-to-api? args)
      (apply registry-image-exists-according-to-cmd? args)))

