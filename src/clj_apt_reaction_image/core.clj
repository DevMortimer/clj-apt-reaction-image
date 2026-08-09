(ns clj-apt-reaction-image.core
  (:gen-class)
  (:require
   [clj-apt-reaction-image.describe :as describe]
   [clj-apt-reaction-image.log :as log]
   [clj-apt-reaction-image.tools :as tools]
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;; ─── helpers ────────────────────────────────────────────────────────────

(defn- usage []
  (str/join
   \newline
   ["clj-apt-reaction-image  —  on-device reaction image organiser"
    ""
    "Commands:"
    "  organize --images-dir PATH [--dry-run] [--all] [--frames N] [--output text|json]"
    ""
    "Files that already carry Finder tags are treated as done and skipped."
    "Pass -a / --all to reprocess them anyway."
    ""
    "Examples:"
    "  clojure -M:run organize --images-dir \"~/iCloud/Pictures/maymays\" --dry-run"
    "  clojure -M:run organize --images-dir \"~/iCloud/Pictures/maymays\""
    "  clojure -M:run organize --images-dir \"~/iCloud/Pictures/maymays\" --all"
    ""
    "Dependencies (installed via homebrew):"
    "  auge       — Apple Vision CLI (OCR, classification, face detection)"
    "  apfel      — Apple Foundation Model CLI (text reasoning)"
    "  apfel-tag  — Apple on-device content tagger"
    "  ffmpeg     — frame extraction for clips (GIFs and videos)"
    ""
    "All processing is 100% on-device. No cloud, no API keys, no Ollama."]))

(def ^:private boolean-flags
  #{"--dry-run" "--dryrun" "--json" "--quiet" "--help" "--version" "--all"})

(def ^:private flag-aliases
  {"-a" "--all"})

(defn- parse-args [argv]
  (loop [args argv parsed {:positionals []}]
    (if-let [arg (get flag-aliases (first args) (first args))]
      (if (str/starts-with? arg "--")
        (if (contains? boolean-flags arg)
          (recur (next args) (assoc parsed (keyword (subs arg 2)) true))
          (let [key-name (keyword (subs arg 2))
                value (second args)]
            (when (or (nil? value) (str/starts-with? value "--"))
              (throw (ex-info (str "Missing value for " arg) {:arg arg})))
            (recur (nnext args) (assoc parsed key-name value))))
        (recur (next args) (update parsed :positionals conj arg)))
      parsed)))

(defn- canonical-path [path]
  (.getAbsolutePath (.getCanonicalFile (io/file path))))

;; ─── prerequisite checks ────────────────────────────────────────────────

(defn- ensure-prerequisites! [config]
  (doseq [tool ["auge" "apfel" "apfel-tag" "ffmpeg" "ffprobe"]]
    (when-not (tools/tool-present? config tool)
      (throw (ex-info (str "Missing required tool: " tool
                           ". Install with: brew install "
                           (case tool
                             "auge" "Arthur-Ficial/tap/auge"
                             "ffmpeg" "ffmpeg"
                             "ffprobe" "ffmpeg"
                             tool))
                      {:tool tool}))))
  (tools/ensure-binary! config)
  true)

;; ─── apfel-tag generation ───────────────────────────────────────────────

(defn- generate-tags! [config ocr-text classes]
  (let [input (cond
                (not (str/blank? ocr-text)) ocr-text
                (not (str/blank? classes)) classes
                :else "image")]
    (try
      (let [result (tools/apfel-tag! config input)
            all-tags (concat (:tags result)
                             (:topics result)
                             (:emotions result))
            seen (atom #{})
            uniq (vec (take 6 (filter (fn [t]
                                        (let [low (str/lower-case (name t))]
                                          (when-not (@seen low)
                                            (swap! seen conj low)
                                            true)))
                                      all-tags)))]
        (str/join "," (map name uniq)))
      (catch Exception _ ""))))

;; ─── apfel filename generation ──────────────────────────────────────────

(defn- fallback-name
  "Name built from abbreviated OCR text, then face count, then first
  classification label — the model-gone-missing name per CONTEXT.md."
  [ocr-text classes faces]
  (let [name-parts (filter seq [(when (not (str/blank? ocr-text))
                                  (str/lower-case
                                   (str/replace (subs ocr-text 0 (min 20 (count ocr-text)))
                                                #"[^a-z0-9]" "-")))
                                 (when (pos? faces) (str faces "face"))
                                 (when (not (str/blank? classes))
                                   (-> (str/split classes #",") first str/trim (str/replace #" " "-")))])]
    (when (seq name-parts)
      (str/join "-" name-parts))))

(defn- generate-filename! [config ocr-text classes faces tags]
  (let [prompt-parts []
        prompt-parts (if (not (str/blank? ocr-text))
                       (conj prompt-parts (str "OCR text on image: \"" (subs ocr-text 0 (min 120 (count ocr-text))) "\""))
                       prompt-parts)
        prompt-parts (conj prompt-parts (str "Objects detected: " classes))
        prompt-parts (if (pos? faces)
                       (conj prompt-parts (str faces " face(s)"))
                       prompt-parts)
        prompt-parts (if (not (str/blank? tags))
                       (conj prompt-parts (str "Keywords: " tags))
                       prompt-parts)
        prompt (str/join ". " prompt-parts)
        system-prompt
        "You name images. Output a short kebab-case name (max 30 chars, lowercase a-z and hyphens only, no spaces, no dots, no extension). Be specific about what you see."]
    (try
      (let [result (tools/apfel-name! config prompt system-prompt)]
        (or (some-> result :name name str/trim)
            (fallback-name ocr-text classes faces)))
      (catch Exception _
        (fallback-name ocr-text classes faces)))))

;; ─── filename sanitisation ──────────────────────────────────────────────

(defn- sanitize-filename [s max-len]
  (let [cleaned (-> (or s "")
                    str/lower-case
                    (str/replace #"[^a-z0-9-]" "-")
                    (str/replace #"-{2,}" "-")
                    (str/replace #"^-|-$" ""))]
    (subs cleaned 0 (min max-len (count cleaned)))))

(defn- unique-target
  "Target file for renaming source to base.ext in its directory. When a
  different file already has that name, appends _1, _2, ... until free."
  [^java.io.File source base ext]
  (let [dir (.getParentFile source)
        candidate (io/file dir (str base "." ext))]
    (if (or (= (.getAbsolutePath candidate) (.getAbsolutePath source))
            (not (.exists candidate)))
      candidate
      (loop [n 1]
        (let [c (io/file dir (str base "_" n "." ext))]
          (if (.exists c)
            (recur (inc n))
            c))))))

;; ─── finder tags ────────────────────────────────────────────────────────

(defn- already-tagged? [config ^java.io.File file]
  (boolean (seq (tools/read-tags! config (.getAbsolutePath file)))))

;; ─── per-image pipeline ─────────────────────────────────────────────────

(defn- process-image! [config ^java.io.File image-file timestamp]
  (let [path (.getAbsolutePath image-file)
        name (.getName image-file)
        ext (describe/extension-of image-file)
        dry-run (:dry-run config)]
    (log/log! config (str "[" timestamp "] " name))
    (let [{:keys [ocr-text classes faces]}
          (describe/describe config image-file)
          _ (log/log! config (str "  ocr: " (when (seq ocr-text) (str (subs ocr-text 0 (min 60 (count ocr-text))) "..."))))
          _ (log/log! config (str "  classes: " classes))
          _ (log/log! config (str "  faces: " faces))
          tags (generate-tags! config ocr-text classes)
          _ (log/log! config (str "  tags: " tags))
          raw-name (generate-filename! config ocr-text classes faces tags)
          clean-name (sanitize-filename raw-name 30)
          final-name (if (str/blank? clean-name)
                       (str "img-" (System/currentTimeMillis))
                       clean-name)
          target (unique-target image-file final-name ext)
          new-name (.getName target)
          new-path (.getAbsolutePath target)]
      (log/log! config (str "  → " new-name))
      (when dry-run
        (log/log! config "  (dry-run, no changes made)"))
      (when-not dry-run
        (when (not= path new-path)
          (let [renamed (.renameTo (io/file image-file) (io/file new-path))]
            (when-not renamed
              (throw (ex-info "Failed to rename file"
                              {:from path :to new-path})))))
        (tools/write-tags! config new-path tags))
      {:original-name name
       :new-name new-name
       :path new-path
       :tags tags
       :ocr-text ocr-text
       :classes classes
       :faces faces
       :dry-run dry-run})))

;; ─── organise command ──────────────────────────────────────────────────

(defn- default-config [opts]
  (let [parse-frames
        (fn [s]
          (try
            (let [n (Integer/parseInt s)]
              (if (pos? n) n
                  (throw (ex-info "Invalid --frames: must be a positive integer"
                                  {:type :usage :exit-code 1}))))
            (catch NumberFormatException _
              (throw (ex-info "Invalid --frames: must be a positive integer"
                              {:type :usage :exit-code 1})))))
        frames-str (:frames opts)]
    {:images-dir (some-> (:images-dir opts) canonical-path)
     :dry-run (boolean (:dry-run opts))
     :all (boolean (:all opts))
     :frames (if frames-str (parse-frames frames-str) 6)
     :output-format (or (:output opts) "text")}))

(defn- collect-images [images-dir]
  (->> (file-seq (io/file images-dir))
       (filter describe/media-file?)
       (sort-by #(.getAbsolutePath ^java.io.File %))
       vec))

(defn organize-images! [config]
  (ensure-prerequisites! config)
  (let [images-dir (:images-dir config)]
    (when-not images-dir
      (throw (ex-info "Provide --images-dir"
                      {:type :usage :exit-code 1})))
    (let [all-files (collect-images images-dir)]
      (when (zero? (count all-files))
        (throw (ex-info "No supported image files found" {:images-dir images-dir})))
      (let [images (if (:all config)
                     all-files
                     (vec (remove #(already-tagged? config %) all-files)))
            skipped (- (count all-files) (count images))
            total (count images)]
        (when (pos? skipped)
          (log/log! config (str "Skipping " skipped " already-tagged file(s); pass --all to reprocess.")))
        (if (zero? total)
          (do
            (log/log! config "Nothing to do.")
            {:images-dir images-dir
             :dry-run (:dry-run config)
             :total 0
             :skipped skipped
             :results []})
          (do
            (log/log! config (str "Processing " total " images in " images-dir))
            (let [results (mapv (fn [^java.io.File img]
                                  (let [idx (inc (.indexOf images img))
                                        ts (str idx "/" total)]
                                    (process-image! config img ts)))
                                images)]
              (log/log! config (str "Done. " total " images processed."))
              {:images-dir images-dir
               :dry-run (:dry-run config)
               :total total
               :skipped skipped
               :results results})))))))

;; ─── output ─────────────────────────────────────────────────────────────

(defn- json-output? [config]
  (= "json" (str/lower-case (or (:output-format config) "text"))))

(defn- print-json! [value]
  (println (json/write-str value)))

(defn- entry->response [entry]
  {:original_name (:original-name entry)
   :new_name (:new-name entry)
   :path (:path entry)
   :tags (:tags entry)
   :ocr_text (:ocr-text entry)
   :classes (:classes entry)
   :faces (:faces entry)})

(defn- result->response [result]
  {:images_dir (:images-dir result)
   :dry_run (:dry-run result)
   :total (:total result)
   :skipped (:skipped result 0)
   :results (mapv entry->response (:results result))})

;; ─── CLI entry points ───────────────────────────────────────────────────

(defn- run-organize! [args]
  (let [opts (parse-args args)
        config (assoc (default-config opts)
                      :log-fn (when-not (= "json" (str/lower-case (or (:output opts) "text")))
                                println))]
    (try
      (let [result (organize-images! config)]
        (when (json-output? config)
          (print-json! (result->response result)))
        result)
      (catch clojure.lang.ExceptionInfo ex
        (let [data (ex-data ex)]
          (if (= :usage (:type data))
            (do (println (.getMessage ex))
                (println)
                (println (usage)))
            (throw ex)))))))

(defn- argv-requests-json? [argv]
  (loop [args argv]
    (when-let [arg (first args)]
      (if (= "--output" arg)
        (= "json" (str/lower-case (or (second args) "")))
        (recur (next args))))))

(defn -main [& argv]
  (try
    (let [[command & args] argv]
      (case command
        "organize" (run-organize! args)
        (do (println (usage))
            (System/exit 1))))
    (catch Exception ex
      (let [data (ex-data ex)
            exit-code (or (:exit-code data) 1)
            json? (argv-requests-json? argv)]
        (if json?
          (print-json! {:error {:type (name (or (:type data) :error))
                                :message (.getMessage ex)}})
          (binding [*out* *err*]
            (println (.getMessage ex))))
        (System/exit exit-code)))))
