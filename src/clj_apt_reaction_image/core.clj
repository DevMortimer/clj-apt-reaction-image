(ns clj-apt-reaction-image.core
  (:gen-class)
  (:require
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.java.shell :as sh]
   [clojure.string :as str]))

;; ─── constants ──────────────────────────────────────────────────────────────

(def ^:private still-extensions
  #{"png" "jpg" "jpeg" "webp" "heic" "heif" "bmp" "tif" "tiff"})

(def ^:private clip-extensions
  #{"gif" "mp4" "mov" "m4v" "webm" "mkv"})

(def ^:private supported-extensions
  (into still-extensions clip-extensions))

(def ^:private project-dir
  (.getCanonicalFile (io/file ".")))

(def ^:private tags-source
  (io/file project-dir "mac" "tags" "main.swift"))

(def ^:private tags-binary
  (io/file project-dir ".clj-apt-reaction-image" "tags"))

(def ^:private cache-dir
  (io/file project-dir ".clj-apt-reaction-image"))

;; ─── helpers ────────────────────────────────────────────────────────────────

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

(defn- log! [config & xs]
  (when-let [log-fn (:log-fn config)]
    (apply log-fn xs)))

(defn- normalize-text [s]
  (-> (or s "") str (str/replace #"\s+" " ") str/trim))

(defn- extension-of [^java.io.File file]
  (let [name (.getName file)
        dot (.lastIndexOf name ".")]
    (when (pos? dot)
      (str/lower-case (subs name (inc dot))))))

(defn- media-file? [^java.io.File file]
  (and (.isFile file) (contains? supported-extensions (extension-of file))))

(defn- clip-file? [^java.io.File file]
  (contains? clip-extensions (extension-of file)))

(defn- collect-images [images-dir]
  (->> (file-seq (io/file images-dir))
       (filter media-file?)
       (sort-by #(.getAbsolutePath ^java.io.File %))
       vec))

(defn- command! [& args]
  (let [{:keys [exit out err]} (apply sh/sh args)]
    (when-not (zero? exit)
      (throw (ex-info (str "Command failed: " (str/join " " args))
                      {:args args :exit exit :out out :err err})))
    out))

(defn- command-exists? [name]
  (zero? (:exit (sh/sh "which" name))))

(defn- ensure-dir! [^java.io.File dir]
  (.mkdirs dir) dir)

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

(defn- require-option [opts key-name]
  (or (get opts key-name)
      (throw (ex-info (str "Missing required option --" (name key-name))
                      {:type :usage :exit-code 1 :option key-name}))))

(defn- shell-json [& args]
  "Run a command that returns JSON and parse it."
  (let [{:keys [exit out err]} (apply sh/sh args)]
    (when-not (zero? exit)
      (throw (ex-info (str "JSON command failed: " (str/join " " args))
                      {:args args :exit exit :out out :err err})))
    (when (seq out)
      (try
        (json/read-str out :key-fn keyword)
        (catch Exception e
          (throw (ex-info "Failed to parse JSON output"
                          {:args args :out out :error (.getMessage e)})))))))

;; ─── prerequisite checks ────────────────────────────────────────────────────

(defn- ensure-tags-binary! []
  (when (or (not (.exists tags-binary))
            (> (.lastModified tags-source) (.lastModified tags-binary)))
    (log! nil "Compiling tags binary...")
    (ensure-dir! (.getParentFile tags-binary))
    (let [{:keys [exit out err]}
          (sh/sh "swiftc" "-o" (.getAbsolutePath tags-binary)
                 (.getAbsolutePath tags-source))]
      (when-not (zero? exit)
        (throw (ex-info "Failed to compile tags binary"
                        {:source (.getAbsolutePath tags-source)
                         :target (.getAbsolutePath tags-binary)
                         :exit exit :out out :err err})))
      (log! nil "Tags binary compiled."))))

(defn- ensure-prerequisites! []
  (doseq [tool ["auge" "apfel" "apfel-tag" "ffmpeg" "ffprobe"]]
    (when-not (command-exists? tool)
      (throw (ex-info (str "Missing required tool: " tool
                           ". Install with: brew install "
                           (case tool
                             "auge" "Arthur-Ficial/tap/auge"
                             "ffmpeg" "ffmpeg"
                             "ffprobe" "ffmpeg"
                             tool))
                      {:tool tool}))))
  (ensure-tags-binary!)
  true)

;; ─── frame sampling ────────────────────────────────────────────────────────

(defn- sample-fractions [n]
  ;; n evenly spaced, centered: n=6 → 1/12, 3/12, 5/12, 7/12, 9/12, 11/12
  (mapv #(/ (+ 1.0 (* 2 %)) (* 2 n)) (range n)))

(defn- clip-duration! [path]
  (try
    (let [stdout (:out (sh/sh "ffprobe" "-v" "error"
                              "-show_entries" "format=duration"
                              "-of" "default=noprint_wrappers=1:nokey=1" path))
          parsed (Double/parseDouble (str/trim stdout))]
      (when (pos? parsed) parsed))
    (catch Exception _ nil)))

(defn- extract-frames! [path n]
  (let [temp-dir (.toFile (java.nio.file.Files/createTempDirectory
                           "clj-apt-frames"
                           (make-array java.nio.file.attribute.FileAttribute 0)))
        duration (clip-duration! path)
        timestamps (if duration
                     (mapv #(* duration %) (sample-fractions n))
                     [0.0])]
    (try
      (doseq [[i t] (map-indexed vector timestamps)]
        (sh/sh "ffmpeg" "-v" "error" "-ss" (format "%.3f" t)
               "-i" path "-frames:v" "1"
               "-y" (str (.getAbsolutePath temp-dir) "/frame-" (format "%03d" i) ".png")))
      (let [frame-files (->> (.listFiles temp-dir)
                             (filter #(and (.isFile %) (pos? (.length %))))
                             sort
                             vec)
            digest (java.security.MessageDigest/getInstance "MD5")
            seen (atom #{})
            deduped (filterv (fn [^java.io.File f]
                               (let [bytes (java.nio.file.Files/readAllBytes (.toPath f))
                                     hash (format "%032x" (BigInteger. 1 (.digest digest bytes)))]
                                 (.reset digest)
                                 (when-not (@seen hash)
                                   (swap! seen conj hash)
                                   true)))
                             frame-files)]
        {:dir temp-dir :frames deduped})
      (catch Exception e
        {:dir temp-dir :frames []}))))

;; ─── auge batch ─────────────────────────────────────────────────────────────

(defn- auge-batch! [mode paths]
  (try
    (let [result (sh/sh "auge" mode "--ndjson" "-q" :in (str/join "\n" (map str paths)))]
      (if (zero? (:exit result))
        (let [lines (str/split-lines (str/trim (:out result)))]
          (into {} (for [line lines
                         :when (not (str/blank? line))]
                     (let [parsed (json/read-str line :key-fn keyword)]
                       [(:file parsed) parsed]))))
        {}))
    (catch Exception _ {})))

;; ─── frame merge ────────────────────────────────────────────────────────────

(defn- merge-ocr-texts [texts]
  (let [seen (atom #{})]
    (->> texts
         (map normalize-text)
         (remove str/blank?)
         (filter (fn [t]
                   (let [low (str/lower-case t)]
                     (when-not (@seen low)
                       (swap! seen conj low)
                       true))))
         (str/join " "))))

(defn- merge-classifications [per-frame-classifications]
  (let [best (->> (flatten (seq per-frame-classifications))
                  (remove nil?)
                  (group-by #(str/lower-case (name (:label %))))
                  (mapv (fn [[_ items]]
                          (apply max-key :confidence items)))
                  (sort-by #(- (:confidence %)))
                  (take 5)
                  (map #(name (:label %))))]
    (str/join ", " best)))

(defn- merge-face-counts [counts]
  (reduce max 0 counts))

;; ─── auge extraction ────────────────────────────────────────────────────────

(defn- extract-ocr! [image-path]
  (try
    (let [result (shell-json "auge" "--ocr" image-path "--json" "-q")]
      (or (some-> result :results :text normalize-text) ""))
    (catch Exception _ "")))

(defn- extract-classes! [image-path]
  (try
    (let [result (shell-json "auge" "--classify" image-path "--json" "-q")
          classes (->> (get-in result [:results :classifications])
                       (take 5)
                       (map :label)
                       (map name)
                       (remove str/blank?))]
      (str/join ", " classes))
    (catch Exception _ "")))

(defn- extract-faces! [image-path]
  (try
    (let [result (shell-json "auge" "--faces" image-path "--json" "-q")]
      (get-in result [:results :count] 0))
    (catch Exception _ 0)))

;; ─── apfel-tag generation ───────────────────────────────────────────────────

(defn- generate-tags! [ocr-text classes]
  (let [input (cond
                (not (str/blank? ocr-text)) ocr-text
                (not (str/blank? classes)) classes
                :else "image")]
    (try
      (let [result (shell-json "apfel-tag" "--permissive" "-o" "json" "-q"
                               :in input)
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

;; ─── apfel filename generation ──────────────────────────────────────────────

(def ^:private filename-schema
  (io/file cache-dir "filename-schema.json"))

(defn- ensure-filename-schema! []
  (when-not (.exists filename-schema)
    (ensure-dir! cache-dir)
    (spit filename-schema
          (json/write-str {:type "object"
                           :properties {:name {:type "string"
                                              :description "kebab-case filename, max 30 chars"}}
                           :required ["name"]}))))

(defn- generate-filename! [ocr-text classes faces tags]
  (ensure-filename-schema!)
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
        "You name images. Output a short kebab-case name (max 30 chars, lowercase a-z and hyphens only, no spaces, no dots, no extension). Be specific about what you see."
        schema-path (.getAbsolutePath filename-schema)]
    (try
      (let [result (shell-json "apfel" "-q" "--permissive" "--schema" schema-path
                               "-s" system-prompt prompt)]
        (or (some-> result :name name str/trim)
            (let [name-parts (filter seq [(when (not (str/blank? ocr-text))
                                           (str/lower-case
                                            (str/replace (subs ocr-text 0 (min 20 (count ocr-text)))
                                                         #"[^a-z0-9]" "-")))
                                          (when (pos? faces) (str faces "face"))
                                          (when (not (str/blank? classes))
                                            (-> (str/split classes #",") first str/trim (str/replace #" " "-")))])]
              (when (seq name-parts)
                (str/join "-" name-parts)))))
      (catch Exception _
        (let [name-parts (filter seq [(when (not (str/blank? ocr-text))
                                       (str/lower-case
                                        (str/replace (subs ocr-text 0 (min 20 (count ocr-text)))
                                                     #"[^a-z0-9]" "-")))
                                      (when (pos? faces) (str faces "face"))
                                      (when (not (str/blank? classes))
                                        (-> (str/split classes #",") first str/trim (str/replace #" " "-")))])]
          (when (seq name-parts)
            (str/join "-" name-parts))))
      )))

;; ─── filename sanitisation ──────────────────────────────────────────────────

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

;; ─── finder tags ────────────────────────────────────────────────────────────

(defn- finder-tags! [file-path]
  (let [{:keys [exit out]} (sh/sh (.getAbsolutePath tags-binary) file-path)]
    (if (zero? exit)
      (->> (str/split (str/trim out) #",")
           (remove str/blank?)
           vec)
      [])))

(defn- already-tagged? [^java.io.File file]
  (boolean (seq (finder-tags! (.getAbsolutePath file)))))

(defn- set-finder-tags! [file-path tags-str]
  (when (and (seq tags-str) (.exists (io/file file-path)))
    (let [{:keys [exit err]} (sh/sh (.getAbsolutePath tags-binary)
                                    file-path tags-str)]
      (when-not (zero? exit)
        (throw (ex-info "Failed to set Finder tags"
                        {:file file-path :tags tags-str :error err}))))))

;; ─── describe seam ──────────────────────────────────────────────────────────

(defn- describe-still! [path]
  {:ocr-text (extract-ocr! path)
   :classes  (extract-classes! path)
   :faces    (extract-faces! path)})

(defn- describe-clip! [config path]
  (let [n (:frames config)
        {:keys [dir frames]} (extract-frames! path n)]
    (try
      (if (empty? frames)
        (do
          (log! config (str "  no frames extracted, falling back to still analysis"))
          (describe-still! path))
        (let [frame-paths (mapv #(.getAbsolutePath ^java.io.File %) frames)]
          (log! config (str "  frames: " (count frames) " sampled"))
          (let [ocr-batch (auge-batch! "--ocr" frame-paths)
                classify-batch (auge-batch! "--classify" frame-paths)
                faces-batch (auge-batch! "--faces" frame-paths)
                ocr-texts (mapv (fn [fp]
                                  (-> (get ocr-batch fp)
                                      :results :text normalize-text))
                                frame-paths)
                classifications (mapv (fn [fp]
                                        (-> (get classify-batch fp)
                                            :results :classifications (or [])))
                                      frame-paths)
                face-counts (mapv (fn [fp]
                                    (-> (get faces-batch fp)
                                        :results :count (or 0)))
                                  frame-paths)]
            {:ocr-text (merge-ocr-texts ocr-texts)
             :classes  (merge-classifications classifications)
             :faces    (merge-face-counts face-counts)})))
      (finally
        (doseq [f (.listFiles dir)]
          (io/delete-file f :silently))
        (io/delete-file dir :silently)))))

;; ─── per-image pipeline ─────────────────────────────────────────────────────

(defn- process-image! [config ^java.io.File image-file timestamp]
  (let [path (.getAbsolutePath image-file)
        name (.getName image-file)
        ext (extension-of image-file)
        dry-run (:dry-run config)]
    (log! config (str "[" timestamp "] " name))
    (let [{:keys [ocr-text classes faces]}
          (if (clip-file? image-file)
            (describe-clip! config path)
            (describe-still! path))
          _ (log! config (str "  ocr: " (when (seq ocr-text) (str (subs ocr-text 0 (min 60 (count ocr-text))) "..."))))
          _ (log! config (str "  classes: " classes))
          _ (log! config (str "  faces: " faces))
          tags (generate-tags! ocr-text classes)
          _ (log! config (str "  tags: " tags))
          raw-name (generate-filename! ocr-text classes faces tags)
          clean-name (sanitize-filename raw-name 30)
          final-name (if (str/blank? clean-name)
                       (str "img-" (System/currentTimeMillis))
                       clean-name)
          target (unique-target image-file final-name ext)
          new-name (.getName target)
          new-path (.getAbsolutePath target)]
      (log! config (str "  → " new-name))
      (when dry-run
        (log! config "  (dry-run, no changes made)"))
      (when-not dry-run
        (when (not= path new-path)
          (let [renamed (.renameTo (io/file image-file) (io/file new-path))]
            (when-not renamed
              (throw (ex-info "Failed to rename file"
                              {:from path :to new-path})))))
        (set-finder-tags! new-path tags))
      {:original-name name
       :new-name new-name
       :path new-path
       :tags tags
       :ocr-text ocr-text
       :classes classes
       :faces faces
       :dry-run dry-run})))

;; ─── organise command ──────────────────────────────────────────────────────

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

(defn organize-images! [config]
  (ensure-prerequisites!)
  (let [images-dir (:images-dir config)]
    (when-not images-dir
      (throw (ex-info "Provide --images-dir"
                      {:type :usage :exit-code 1})))
    (let [all-files (collect-images images-dir)]
      (when (zero? (count all-files))
        (throw (ex-info "No supported image files found" {:images-dir images-dir})))
      (let [images (if (:all config)
                     all-files
                     (vec (remove already-tagged? all-files)))
            skipped (- (count all-files) (count images))
            total (count images)]
        (when (pos? skipped)
          (log! config (str "Skipping " skipped " already-tagged file(s); pass --all to reprocess.")))
        (if (zero? total)
          (do
            (log! config "Nothing to do.")
            {:images-dir images-dir
             :dry-run (:dry-run config)
             :total 0
             :skipped skipped
             :results []})
          (do
            (log! config (str "Processing " total " images in " images-dir))
            (let [results (mapv (fn [^java.io.File img]
                                  (let [idx (inc (.indexOf images img))
                                        ts (str idx "/" total)]
                                    (process-image! config img ts)))
                                images)]
              (log! config (str "Done. " total " images processed."))
              {:images-dir images-dir
               :dry-run (:dry-run config)
               :total total
               :skipped skipped
               :results results})))))))

;; ─── output ─────────────────────────────────────────────────────────────────

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

;; ─── CLI entry points ───────────────────────────────────────────────────────

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
