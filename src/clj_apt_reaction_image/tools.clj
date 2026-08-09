(ns clj-apt-reaction-image.tools
  "Deep module: every external tool invocation lives here — auge
  (Apple Vision), apfel / apfel-tag (Apple Foundation Models),
  ffmpeg / ffprobe (frame sampling), and the Swift tags helper.

  One place for CLI flags, output parsing, and failure policy.
  Injection: config may carry :run-fn (args stdin) -> {:exit :out :err}.
  Policy: vision and text tools degrade to nil/empty on failure
  (a file may legitimately have no text, no faces); Finder-tag reads
  degrade to [] (a file may simply be untagged); tag writes and the
  Swift compiler fail hard."
  (:require
   [clj-apt-reaction-image.log :as log]
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.java.shell :as sh]
   [clojure.string :as str]))

;; ─── project paths ──────────────────────────────────────────────────────

(def ^:private project-dir
  (.getCanonicalFile (io/file ".")))

(def ^:private tags-source
  (io/file project-dir "mac" "tags" "main.swift"))

(def ^:private tags-binary
  (io/file project-dir ".clj-apt-reaction-image" "tags"))

(def ^:private cache-dir
  (io/file project-dir ".clj-apt-reaction-image"))

(def ^:private filename-schema
  (io/file cache-dir "filename-schema.json"))

;; ─── the runner ─────────────────────────────────────────────────────────

(defn- real-run!
  "Production runner: spawn the process. Returns {:exit :out :err}."
  [args stdin]
  (apply sh/sh (cond-> (vec args) (some? stdin) (conj :in stdin))))

(defn- spawn!
  "Run args, optionally feeding stdin. Tests inject a fake via
  (:run-fn config)."
  [config args stdin]
  ((or (:run-fn config) real-run!) args stdin))

(defn- run-ok!
  "Run; return stdout, or nil when the process exits non-zero."
  [config args stdin]
  (let [{:keys [exit out]} (spawn! config args stdin)]
    (when (zero? exit) out)))

(defn- parse-json!
  "Parse stdout as JSON; nil when unparseable."
  [out]
  (try
    (json/read-str out :key-fn keyword)
    (catch Exception _ nil)))

;; ─── auge (Apple Vision) — degrades to nil / {} ─────────────────────────

(defn auge-json!
  "auge <op> <path> --json -q → parsed JSON, or nil when auge fails."
  [config op path]
  (try
    (some-> (run-ok! config ["auge" op path "--json" "-q"] nil)
            parse-json!)
    (catch Exception _ nil)))

(defn auge-ndjson!
  "auge <op> --ndjson -q with paths on stdin → {path → parsed JSON},
  or {} when auge fails."
  [config op paths]
  (try
    (let [args ["auge" op "--ndjson" "-q"]
          out (run-ok! config args (str/join "\n" (map str paths)))]
      (if (nil? out)
        {}
        (into {}
              (for [line (str/split-lines (str/trim out))
                    :when (not (str/blank? line))]
                (let [parsed (json/read-str line :key-fn keyword)]
                  [(:file parsed) parsed])))))
    (catch Exception _ {})))

;; ─── apfel (Apple Foundation Models) — degrades to nil ──────────────────

(defn apfel-tag!
  "apfel-tag --permissive -o json -q, text on stdin → parsed JSON,
  or nil when the tagger fails."
  [config text]
  (some-> (run-ok! config ["apfel-tag" "--permissive" "-o" "json" "-q"] text)
          parse-json!))

(defn- ensure-name-schema! []
  (when-not (.exists filename-schema)
    (.mkdirs cache-dir)
    (spit filename-schema
          (json/write-str {:type "object"
                           :properties {:name {:type "string"
                                              :description "kebab-case filename, max 30 chars"}}
                           :required ["name"]}))))

(defn apfel-name!
  "apfel -q --permissive --schema <schema> -s <system-prompt> <prompt>
  → parsed JSON (expects :name), or nil when apfel fails. Ensures the
  schema file exists."
  [config prompt system-prompt]
  (ensure-name-schema!)
  (let [args ["apfel" "-q" "--permissive" "--schema"
              (.getAbsolutePath filename-schema)
              "-s" system-prompt prompt]]
    (some-> (run-ok! config args nil)
            parse-json!)))

;; ─── ffmpeg / ffprobe — degrade to nil / false ──────────────────────────

(defn probe-duration!
  "ffprobe → clip duration in seconds, or nil when unknown."
  [config path]
  (try
    (let [out (run-ok! config ["ffprobe" "-v" "error"
                               "-show_entries" "format=duration"
                               "-of" "default=noprint_wrappers=1:nokey=1"
                               path] nil)
          parsed (some-> out str/trim Double/parseDouble)]
      (when (and parsed (pos? parsed)) parsed))
    (catch Exception _ nil)))

(defn extract-frame!
  "ffmpeg → one still at timestamp; true when ffmpeg exits 0."
  [config path timestamp out-file]
  (some? (run-ok! config ["ffmpeg" "-v" "error" "-ss" (format "%.3f" timestamp)
                          "-i" path "-frames:v" "1" "-y"
                          (.getAbsolutePath out-file)] nil)))

;; ─── tags binary — reads degrade, writes fail hard ──────────────────────

(defn read-tags!
  "tags <path> → current Finder tags as a vector, or [] on failure."
  [config file-path]
  (let [out (run-ok! config [(.getAbsolutePath tags-binary) file-path] nil)]
    (if (nil? out)
      []
      (->> (str/split (str/trim out) #",")
           (remove str/blank?)
           vec))))

(defn write-tags!
  "tags <path> <tags> — set Finder tags; throws on failure."
  [config file-path tags-str]
  (when (and (seq tags-str) (.exists (io/file file-path)))
    (let [{:keys [exit err]} (spawn! config [(.getAbsolutePath tags-binary)
                                             file-path tags-str] nil)]
      (when-not (zero? exit)
        (throw (ex-info "Failed to set Finder tags"
                        {:file file-path :tags tags-str :error err}))))))

(defn ensure-binary!
  "Compile the tags Swift helper when missing or stale. Fail-hard."
  [config]
  (when (or (not (.exists tags-binary))
            (> (.lastModified tags-source) (.lastModified tags-binary)))
    (log/log! config "Compiling tags binary...")
    (.mkdirs (.getParentFile tags-binary))
    (let [{:keys [exit out err]}
          (spawn! config ["swiftc" "-o" (.getAbsolutePath tags-binary)
                          (.getAbsolutePath tags-source)] nil)]
      (when-not (zero? exit)
        (throw (ex-info "Failed to compile tags binary"
                        {:source (.getAbsolutePath tags-source)
                         :target (.getAbsolutePath tags-binary)
                         :exit exit :out out :err err})))
      (log/log! config "Tags binary compiled."))))

;; ─── tool presence ──────────────────────────────────────────────────────

(defn tool-present?
  "Is the tool on PATH?"
  [config name]
  (zero? (:exit (spawn! config ["which" name] nil))))
