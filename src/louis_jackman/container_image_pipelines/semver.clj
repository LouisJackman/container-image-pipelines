;;;;
;;;; # Semantic Versioning
;;;;

(ns louis-jackman.container-image-pipelines.semver)



(defrecord SemanticVersion [major minor patch])


(defn SemanticVersion->string [{:keys [major minor patch]}]
  (str major \. minor \. patch))


(def ^:private semantic-version-pattern #"(?x) ^ (\d+) \. (\d+) \. (\d+) $")

(defn string->SemanticVersion [s]
  (let [matches (re-matches semantic-version-pattern s)]
    (if-let [[_ major minor patch] matches]
      (map->SemanticVersion {:major major, :minor minor, :patch patch})
      (throw (ex-info "invalid semantic version string" {:string s})))))

