(ns leiningen.pom
  "Write a pom.xml file to disk for Maven interoperability."
  (:require [leiningen.core.main :as main]
            [leiningen.core.project :as project]
            [clojure.java.io :as io]
            [clojure.string :as s]
            [clojure.data.xml :as xml]))

(defn- relativize [project]
  (let [root (str (:root project) "/")]
    (reduce #(update-in %1 [%2]
                        (fn [xs]
                          (if (seq? xs)
                            (vec (for [x xs]
                                   (.replace x root "")))
                            (when xs (.replace xs root "")))))
            project
            [:target-path :compile-path :source-paths :test-paths
             :resource-paths :java-source-paths])))

;; git scm

(defn- read-git-ref
  "Reads the commit SHA1 for a git ref path."
  [git-dir ref-path]
  (.trim (slurp (str (io/file git-dir ref-path)))))

(defn- read-git-head
  "Reads the value of HEAD and returns a commit SHA1."
  [git-dir]
  (let [head (.trim (slurp (str (io/file git-dir "HEAD"))))]
    (if-let [ref-path (second (re-find #"ref: (\S+)" head))]
      (read-git-ref git-dir ref-path)
      head)))

(defn- read-git-origin
  "Reads the URL for the remote origin repository."
  [git-dir]
  (with-open [rdr (io/reader (io/file git-dir "config"))]
    (->> (map #(.trim %) (line-seq rdr))
         (drop-while #(not= "[remote \"origin\"]" %))
         (next)
         (take-while #(not (.startsWith % "[")))
         (map #(re-matches #"url\s*=\s*(\S*)\s*" %))
         (filter identity)
         (first)
         (second))))

(defn- parse-github-url
  "Parses a GitHub URL returning a [username repo] pair."
  [url]
  (when url
    (next
     (or
      (re-matches #"(?:git@)?github.com:([^/]+)/([^/]+).git" url)
      (re-matches #"[^:]+://(?:git@)?github.com/([^/]+)/([^/]+).git" url)))))

(defn- github-urls [url]
  (when-let [[user repo] (parse-github-url url)]
    {:public-clone (str "git://github.com/" user "/" repo ".git")
     :dev-clone (str "ssh://git@github.com/" user "/" repo ".git")
     :browse (str "https://github.com/" user "/" repo)}))

(defn- make-git-scm [git-dir]
  (try
    (let [origin (read-git-origin git-dir)
          head (read-git-head git-dir)
          urls (github-urls origin)]
      [:scm
       (when (:public-clone urls)
         [:connection (str "scm:git:" (:public-clone urls))])
       (when (:dev-clone urls)
         [:developerConnection (str "scm:git:" (:dev-clone urls))])
       [:tag head]
       [:url (:browse urls)]])
    (catch java.io.FileNotFoundException e
      nil)))

(def ^{:doc "A notice to place at the bottom of generated files."} disclaimer
     "\n<!-- This file was autogenerated by Leiningen.
  Please do not edit it directly; instead edit project.clj and regenerate it.
  It should not be considered canonical data. For more information see
  https://github.com/technomancy/leiningen -->\n")

(defn- make-test-scope [[dep version opts]]
  [dep version (assoc opts :scope "test")])

(defn- camelize [string]
  (s/replace string #"[-_](\w)" (comp s/upper-case second)))

(defn- pomify [key]
  (->> key name camelize keyword))

(defmulti ^:private xml-tags
  (fn [tag value] (keyword "leiningen.pom" (name tag))))

(defn- guess-scm [project]
  "Returns the name of the SCM used in project.clj or \"auto\" if nonexistant.
  Example: :scm {:name \"git\" :tag \"deadbeef\"}"
  (or (-> project :scm :name) "auto"))

(defn- xmlify [scm]
  "Converts the map identified by :scm"
  (map #(xml-tags (first %) (second %)) scm))

(defn- write-scm-tag [scm project]
  "Write the <scm> tag for pom.xml.
  Retains backwards compatibility without an :scm map."
  (if
    (= "auto" scm)
    (make-git-scm (io/file (:root project) ".git"))
    (xml-tags :scm (xmlify (:scm project)))))
(defmethod xml-tags :default
  ([tag value]
     (when value
       [(pomify tag) value])))

(defmethod xml-tags ::list
  ([tag values]
     [(pomify tag) (map (partial xml-tags
                                 (-> tag name (s/replace #"ies$" "y") keyword))
                        values)]))

(doseq [c [::dependencies ::repositories]]
  (derive c ::list))

(defmethod xml-tags ::exclusions
  [tag values]
  (when (not (empty? values))
    [:exclusions
     (map
      (fn [exclusion-spec]
        (let [[dep & {:keys [classifier extension]}]
              (if (symbol? exclusion-spec)
                [exclusion-spec]
                exclusion-spec)]
          [:exclusion (map (partial apply xml-tags)
                           {:group-id (or (namespace dep)
                                          (name dep))
                            :artifact-id (name dep)
                            :classifier classifier
                            :type extension})]))
      values)]))

(defmethod xml-tags ::dependency
  ([_ [dep version & {:keys [optional classifier
                             exclusions scope
                             extension]}]]
     [:dependency
      (map (partial apply xml-tags)
           {:group-id (or (namespace dep) (name dep))
            :artifact-id (name dep)
            :version version
            :optional optional
            :classifier classifier
            :type extension
            :exclusions exclusions
            :scope scope})]))

(defmethod xml-tags ::repository
  ([_ [id opts]]
     [:repository
      (map (partial apply xml-tags)
           {:id id
            :url (:url opts)
            :snapshots (xml-tags :enabled
                                 (str (if (nil? (:snapshots opts))
                                        true
                                        (boolean
                                         (:snapshots opts)))))
            :releases (xml-tags :enabled
                                (str (if (nil? (:releases opts))
                                       true
                                       (boolean
                                        (:releases opts)))))})]))

(defmethod xml-tags ::license
  ([_ opts]
     (when opts
       (let [tags (for [key [:name :url :distribution :comments]
                        :let [val (opts key)] :when val]
                    [key (name val)])]
         (when (not (empty? tags))
           [:licenses [:license tags]])))))

(defmethod xml-tags ::build
  ([_ [project test-project]]
     (let [[src & extra-src] (concat (:source-paths project)
                                     (:java-source-paths project))
           [test & extra-test] (:test-paths test-project)]
       [:build
        [:sourceDirectory src]
        (xml-tags :testSourceDirectory test)
        (if-let [resources (:resource-paths project)]
          (when (not (empty? resources))
            (vec (concat [:resources]
                         (map (fn [x] [:resource [:directory x]]) resources)))))
        (if-let [resources (:resource-paths test-project)]
          (when (not (empty? resources))
            (vec (concat [:testResources]
                         (map (fn [x] [:testResource [:directory x]]) resources)))))
        (if-let [extensions (:extensions project)]
          (when (not (empty? extensions))
            (vec (concat [:extensions]
                         (map (fn [[dep version]] [:extension
                                                  [:artifactId (name dep)]
                                                  [:groupId (or (namespace dep) (name dep))]
                                                  [:version version]])
                              extensions)))))
        [:directory (:target-path project)]
        [:outputDirectory (:compile-path project)]
        (when (or (not (empty? extra-src))
                  (not (empty? extra-test)))
          [:plugins
           [:plugin
            [:groupId "org.codehaus.mojo"]
            [:artifactId "build-helper-maven-plugin"]
            [:version "1.7"]
            [:executions
             (when (not (empty? extra-src))
               [:execution
                [:id "add-source"]
                [:phase "generate-sources"]
                [:goals [:goal "add-source"]]
                [:configuration
                 (vec (concat [:sources]
                              (map (fn [x] [:source x]) extra-src)))]])
             (when (not (empty? extra-src))
               [:execution
                [:id "add-test-source"]
                [:phase "generate-test-sources"]
                [:goals [:goal "add-test-source"]]
                [:configuration
                 (vec (concat [:sources]
                              (map (fn [x] [:source x]) extra-test)))]])]]])])))

(defmethod xml-tags ::parent
  ([_ [dep version & opts]]
     (let [opts (apply hash-map opts)]
       [:parent
        [:artifactId (name dep)]
        [:groupId (or (namespace dep) (name dep))]
        [:version version]
        [:relativePath (:relative-path opts)]])))

(defmethod xml-tags ::mailing-list
  ([_ opts]
     (when opts
       [:mailingLists
        [:mailingList
         [:name (:name opts)]
         [:subscribe (:subscribe opts)]
         [:unsubscribe (:unsubscribe opts)]
         [:post (:post opts)]
         [:archive (:archive opts)]
         (if-let [other-archives (:other-archives opts)]
           (when other-archives
             (vec (concat [:otherArchives]
                          (map (fn [x] [:otherArchive x])
                               other-archives)))))]])))

(defn- test-scope-excluded [deps [dep version & opts :as depspec]]
  (if (some #{depspec} deps)
    depspec
    (concat [dep version]
            (apply concat (update-in (apply hash-map opts)
                                     [:scope]
                                     #(when (not %)
                                        "test"))))))

(defmethod xml-tags ::project
  ([_ project]
     (let [{:keys [without-profiles included-profiles]} (meta project)
           test-project (-> (or without-profiles project)
                            (project/merge-profiles
                             (concat [:dev :test :default]
                                     included-profiles))
                            relativize)]
       (list
        [:project {:xsi:schemaLocation "http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.0.xsd"
                   :xmlns "http://maven.apache.org/POM/4.0.0"
                   :xmlns:xsi "http://www.w3.org/2001/XMLSchema-instance"}
         [:modelVersion "4.0.0"]
         (when (:parent project) (xml-tags :parent (:parent project)))
         [:groupId (:group project)]
         [:artifactId (:name project)]
         [:packaging (:packaging project "jar")]
         [:version (:version project)]
         (when (:classifier project) [:classifier (:classifier project)])
         [:name (:name project)]
         [:description (:description project)]
         (xml-tags :url (:url project))
         (xml-tags :license (:license project))
         (xml-tags :mailing-list (:mailing-list project))
         (write-scm-tag (guess-scm project) project)
         (xml-tags :build [project test-project])
         (xml-tags :repositories (:repositories project))
         (xml-tags :dependencies
                   (distinct (map (partial test-scope-excluded
                                           (:dependencies project))
                                  (concat (:dependencies project)
                                          (:dependencies test-project)))))]))))

(defn snapshot? [project]
  (re-find #"SNAPSHOT" (:version project)))

(defn check-for-snapshot-deps [project]
  (when (and (not (snapshot? project))
             (not (System/getenv "LEIN_SNAPSHOTS_IN_RELEASE"))
             (some #(re-find #"SNAPSHOT" (second %)) (:dependencies project)))
    (main/abort "Release versions may not depend upon snapshots."
                "\nFreeze snapshots to dated versions or set the"
                "LEIN_SNAPSHOTS_IN_RELEASE environment variable to override.")))

(defn- remove-profiles [project profiles]
  (let [{:keys [included-profiles without-profiles]} (meta project)]
    (project/merge-profiles (or without-profiles project)
                            (remove #(some #{%} profiles)
                                    included-profiles))))

(defn make-pom
  ([project] (make-pom project false))
  ([project disclaimer?]
     (let [project (remove-profiles project [:user :dev :test :default])]
       (check-for-snapshot-deps project)
       (str
        (xml/indent-str
         (xml/sexp-as-element
          (xml-tags :project (relativize project))))
        (when disclaimer?
          disclaimer)))))

(defn make-pom-properties [project]
  (with-open [baos (java.io.ByteArrayOutputStream.)]
    (.store (doto (java.util.Properties.)
              (.setProperty "version" (:version project))
              (.setProperty "groupId" (:group project))
              (.setProperty "artifactId" (:name project)))
              baos "Leiningen")
    (str baos)))

(defn ^{:help-arglists '([])} pom
  "Write a pom.xml file to disk for Maven interoperability."
  ([project pom-location]
     (let [pom (make-pom project true)
           pom-file (io/file (:root project) pom-location)]
       (.mkdirs (.getParentFile pom-file))
       (with-open [pom-writer (io/writer pom-file)]
         (.write pom-writer pom))
       (main/info "Wrote" (str pom-file))
       (.getAbsolutePath pom-file)))
  ([project] (pom project "pom.xml")))
