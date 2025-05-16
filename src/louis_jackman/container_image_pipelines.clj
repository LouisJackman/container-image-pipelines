;;;;
;;;; # container-image-pipelines
;;;;

(set! *warn-on-reflection* true)


(ns louis-jackman.container-image-pipelines
  (:gen-class)
  (:require [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as string]

            [clojure.tools.cli :refer [parse-opts]]

            [louis-jackman.container-image-pipelines.utils
             :refer [system-newline]]
            [louis-jackman.container-image-pipelines.subcommands
             :refer [build-images
                     pull-from-registry
                     upload-to-local-registry
                     cascade-version-updates]]))



(def ^:private program-docs
  "Build & publish a monorepo of co-dependent, multiarchitecture container images.

Avoid rebuilding already-built images, automatically rebuild dependencies of
descendent images if necessary, and handle multiarchitecture building
woes. Let users easily shift between remote and local
registries. Automatically cascade version increments to all descendent
images when patching a root base image.")


;;;
;;; Default Option Values
;;;

(def ^:private default-local-registry "localhost:5000")
(def ^:private default-project-dir (io/file "."))
(def ^:private default-stop-on-first-error true)

(def ^:private default-target-platforms
  "The default platforms to build for, unless a build spec overrides them or a
  local-platform-exclusive build is requested."
  #{"linux/arm64"
    "linux/amd64"})



;;;
;;; Option Specifications
;;;

(def ^:private help-opt
  ["-h" "--help"
   "Get help about this command."])

(def ^:private project-dir-opt
  [nil "--project-dir PROJECT-DIR"
   "The project directory containing the `contexts` directory."
   :default default-project-dir
   :parse-fn io/file])

(def ^:private local-registry-opt
  [nil "--local-registry LOCAL-REGISTRY"
   "The local registry."
   :default default-local-registry])

(def ^:private remote-registry-opt
  [nil "--remote-registry REMOTE-REGISTRY"
   "The remote registry."
   :required "REMOTE-REGISTRY"
   :missing "--remote-registry is missing"])

(def ^:private default-platforms-opt
  [nil "--default-platforms DEFAULT-PLATFORMS"
   "The default platforms to target, e.g. \"linux/arm64\". Comma-separate multiple platforms. Specific contexts can override this option if their build is not compatible on all default platforms. Furthermore, the `--only-local-platform` flag overrides both this flag and contexts' overrides."
   :default default-target-platforms
   :parse-fn (comp (partial map string/trim)
                   (partial string/split \,))])

(def ^:private only-local-platform-opt
  [nil "--only-local-platform"
   "Whether to build and publish for just the local builder's platform, e.g. ARM64."])

(def ^:private stop-on-first-error-opt
  [nil "--stop-on-first-error"
   "Whether to stop on the first error, or keep on going and report all errors at the end."
   :default default-stop-on-first-error])


;;;
;;; Option Parsing
;;;

(defn- check-opt-errors
  "If errors exist, throw a structured exception that can be introspected for
  more detailed error messages."
  [errors]
  (when errors
    (throw (ex-info "invalid arguments" {:type :invalid-arguments
                                         :errors errors}))))

(defn- parse-and-check-subcommand-opts [raw-args opt-specs subcommand]
  "Parse `raw-args` according to `opt-specs`. If a help-like flag is passed,
  provide help instead. Returns `[options rest helped]`; if `helped` is
  `true`, the caller can skip their usual operation and the other two
  arguments will be `nil`."
  (let [flags-usage (for [[_ arg desc & rest] opt-specs]
                      (let [default (->> rest
                                         (drop-while #(not= % :default))
                                         (drop 1)
                                         first)
                            default-usage (if (nil? default)
                                            [""]
                                            [" [" default "] "])
                            concise-arg (-> arg
                                            (string/split #"\s+")
                                            first)]
                        (concat [\tab
                                 concise-arg]
                                default-usage
                                [" — "
                                 desc
                                 system-newline])))
        usage (->> [system-newline
                    "container-image-pipelines " subcommand
                    system-newline
                    flags-usage
                    [system-newline]]
                   concat
                   (apply str))
        opt-specs-with-help (conj opt-specs help-opt)
        {rest :arguments :keys [options errors]} (parse-opts raw-args
                                                             opt-specs-with-help)]
    (if (options :help)
      (do
        (println usage)
        [nil nil true])
      (do
        (check-opt-errors errors)
        [options rest false]))))


;;;
;;; Subcommands
;;;

(defn- help-command
  "Provide help and quit successfully having done nothing."
  []
  (->> [""
        program-docs
        ""
        "Usage:"
        "\tcontainer-image-pipelines help"
        "\tcontainer-image-pipelines build"
        "\tcontainer-image-pipelines publish-locally"
        "\tcontainer-image-pipelines publish"
        "\tcontainer-image-pipelines upload-all-to-local-registry"
        "\tcontainer-image-pipelines pull-latest-from-local-registry"
        "\tcontainer-image-pipelines pull-latest-from-remote-registry"
        "\tcontainer-image-pipelines cascade-version-updates"
        ""
        "\tPass `-h` or `--help` to one of those subcommands to discover"
        "\ttheir options."
        ""]
       (string/join system-newline)
       println))

(defn- build-command
  "Build images locally for just the current platform."
  [args]

  (let [opt-specs [project-dir-opt
                   local-registry-opt
                   default-platforms-opt
                   stop-on-first-error-opt]
        [opts rest helped] (parse-and-check-subcommand-opts args
                                                            opt-specs
                                                            "build")
        {:keys [project-dir
                local-registry
                default-platforms
                stop-on-first-error]} opts]
    (if-not helped
      (build-images :project-dir project-dir

                    ;; Due to `:push false`, this registry is just the local
                    ;; store tag; it isn't actually published to.
                    :registry local-registry

                    :default-platforms default-platforms
                    :push false
                    :only-local-platform true
                    :secure-manifest-inspections false
                    :stop-on-first-error stop-on-first-error
                    :extra-build-args rest))))

(defn- publish-locally-command
  "Build and publish images to a local registry for just the current
  platform."
  [args]

  (let [opt-specs [project-dir-opt
                   local-registry-opt
                   default-platforms-opt
                   stop-on-first-error-opt]
        [opts rest helped] (parse-and-check-subcommand-opts args
                                                            opt-specs
                                                            "publish-locally")
        {:keys [project-dir
                local-registry
                default-platforms
                stop-on-first-error]} opts]
    (if-not helped
      (build-images :project-dir project-dir
                    :registry local-registry
                    :default-platforms default-platforms
                    :push true
                    :only-local-platform true
                    :secure-manifest-inspections false
                    :stop-on-first-error stop-on-first-error
                    :extra-build-args rest))))

(defn- publish-command
  "Build and publish images to a remote registry for all default, supported
  platforms."
  [args]

  (let [opt-specs [project-dir-opt
                   remote-registry-opt
                   default-platforms-opt
                   stop-on-first-error-opt]
        [opts rest helped] (parse-and-check-subcommand-opts args
                                                            opt-specs
                                                            "publish")
        {:keys [project-dir
                remote-registry
                default-platforms
                stop-on-first-error]} opts]
    (if-not helped
      (build-images :project-dir project-dir
                    :registry remote-registry
                    :default-platforms default-platforms
                    :push true
                    :only-local-platform false
                    :secure-manifest-inspections true
                    :stop-on-first-error stop-on-first-error
                    :extra-buid-args rest))))

(defn- upload-all-to-local-registry-command
  "Upload all remote images to a local registry, using the container store as an intermediary."
  [args]

  (let [opt-specs [project-dir-opt
                   local-registry-opt
                   remote-registry-opt]
        [opts _ helped] (parse-and-check-subcommand-opts args
                                                         opt-specs
                                                         "upload-all-to-local-registry")
        {:keys [project-dir local-registry remote-registry]} opts]
    (if-not helped
      (upload-to-local-registry :project-dir project-dir
                                :local-registry local-registry
                                :remote-registry remote-registry

                                ;; Don't mandate secure inspections for local
                                ;; registries.
                                :secure-manifest-inspections false))))

(defn- pull-latest-from-local-registry-command
  "Pull the latest prebuilt images from a local registry into the local
  store."
  [args]

  (let [opt-specs [project-dir-opt local-registry-opt]
        [opts _ helped] (parse-and-check-subcommand-opts args
                                                         opt-specs
                                                         "pull-latest-from-local-registry")
        {:keys [project-dir local-registry]} opts]
    (if-not helped
      (pull-from-registry :project-dir project-dir
                          :registry local-registry

                          ;; Don't mandate secure inspections for local
                          ;; registries.
                          :secure-manifest-inspections false))))

(defn- pull-latest-from-remote-registry-command
  "Pull the latest prebuilt images from the remote registry into the local
  store."
  [args]

  (let [opt-specs [project-dir-opt remote-registry-opt]
        [opts _ helped] (parse-and-check-subcommand-opts args
                                                         opt-specs
                                                         "pull-latest-from-remote-registry")
        {:keys [project-dir remote-registry]} opts]
    (if-not helped
      (pull-from-registry :project-dir project-dir
                          :registry remote-registry
                          :secure-manifest-inspections true))))

(defn- cascade-version-updates-command
  "Cascade version updates downwards; if a base image has had its version
  bump, cascade that throughout all its derivative images recursively."
  [args]

  (let [opt-specs [project-dir-opt]
        [opts _ helped] (parse-and-check-subcommand-opts args
                                                         opt-specs
                                                         "cascade-version-updates")
        {:keys [project-dir]} opts]
    (if-not helped
      (cascade-version-updates :project-dir project-dir))))



;;;
;;; Subcommand Dispatching
;;;

(def ^:private expected-subcommands #{"help"
                                      "build"
                                      "publish-locally"
                                      "publish"
                                      "upload-all-to-local-registry"
                                      "pull-latest-from-local-registry"
                                      "pull-latest-from-remote-registry"
                                      "cascade-version-updates"})

(def ^:private invalid-subcommand-exception
  (ex-info "invalid subcommand"
           {:type :invalid-subcommand
            :expected-subcommands (sort expected-subcommands)}))

(defn- on-invalid-subcommand []
  (throw invalid-subcommand-exception))

(defn- dispatch-subcommand
  "Dispatch to the provided subcommand if it exists, otherwise display usage."
  [command-line-args]

  (if-let [[sub-command & args] command-line-args]
    (case (string/lower-case sub-command)
      "-h" (help-command)
      "--help" (help-command)
      "help" (help-command)

      "build" (build-command args)
      "publish-locally" (publish-locally-command args)
      "publish" (publish-command args)
      "upload-all-to-local-registry" (upload-all-to-local-registry-command args)
      "pull-latest-from-local-registry" (pull-latest-from-local-registry-command args)
      "pull-latest-from-remote-registry" (pull-latest-from-remote-registry-command args)
      "cascade-version-updates" (cascade-version-updates-command args)
      (on-invalid-subcommand))
    (on-invalid-subcommand)))


;;;
;;; Error Handling
;;;

(def ^:private failure-status-code 1)

(defn- fail-with
  "Fail the program with information from the provided exception. Provide
  additional context if the exception type is recognised."
  [^Exception ex]

  (binding [*out* *err*]
    (let [error-prelude (str system-newline
                             "An error occured: "
                             (ex-message ex)
                             ".")]
      (if-let [data (ex-data ex)]
        (let [info (case (data :type)

                     :invalid-subcommand
                     (do
                       (help-command)
                       (str "valid subcommands: "
                            (string/join ", " (data :expected-subcommands))))

                     :invalid-arguments
                     (do
                       (help-command)
                       (str "a correct subcommand, but it received invalid "
                            "arguments:"
                            system-newline
                            (string/join (map (partial #(str "\t• " % system-newline))
                                              (data :errors)))))
                     (str data))]
          (println error-prelude)
          (println (str "More context: " info system-newline)))
        (println error-prelude))))
  (System/exit failure-status-code))


;;;
;;; Entrypoint
;;;

(defn -main [& args]
  (try
    (dispatch-subcommand args)
    (catch Exception e (fail-with e))))

