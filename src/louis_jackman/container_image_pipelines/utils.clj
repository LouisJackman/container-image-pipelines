;;;;
;;;; # Utilities
;;;;

(ns louis-jackman.container-image-pipelines.utils
  (:require [clojure.java.process :as process])
  (:import [java.net.http HttpClient HttpResponse]))

(set! *warn-on-reflection* true)



(def system-newline
  (System/getProperty "line.separator"))


(def inherit-io-flags
  {:in :inherit
   :out :inherit
   :err :inherit})

(defn wait-for-proc [proc]
  "Wait for a process to finish."
  @(process/exit-ref proc))

(defn check-proc [^Process proc]
  "Wait for a process to finish. Throw an exception if its status code
  indicates failure, i.e. not 0."
  (let [status (wait-for-proc proc)
        command (-> proc .info .commandLine (.orElse nil))]
    (if-not (zero? status)
      (let [ex-data (merge {:status status}
                           (if command
                               {:command command}
                               {}))]
      (throw (ex-info "process failed" ex-data))))))

(defn docker
  "Run the `docker` process."
  [& [maybe-opts & rest :as maybe-args]]
  (let [[opts args] (if (map? maybe-opts)
                      [(merge inherit-io-flags maybe-opts) rest]
                      [inherit-io-flags maybe-args])
        cmd (concat [opts "docker"]
                    args)]
    (apply process/start cmd)))


(def ^:dynamic ^HttpClient *http-client*
  "The HTTP client to use for requests by default. Bind to a non-nil client
  for each new platform thread.")

(defn http-resp-ok?
  "Is a HTTP response in the 200 range?"
  [^HttpResponse resp]
  (<= 200 (.statusCode resp) 299))

