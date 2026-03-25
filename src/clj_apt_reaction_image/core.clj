(ns clj-apt-reaction-image.core
  (:gen-class)
  (:require
   [clojure.data.json :as json]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.java.shell :as sh]
   [clojure.string :as str]
   [clj-apt-reaction-image.ollama :as ollama]))

(def ^:private index-version 3)
(def ^:private semantic-revision 2)
(def ^:private default-vision-model "qwen2.5vl:3b")
(def ^:private default-candidate-count 24)
(def ^:private default-vision-max-side 512)
(def ^:private default-checkpoint-every 10)
(def ^:private model-fallback-batch-size 40)
(def ^:private project-dir
  (.getCanonicalFile (io/file ".")))
(def ^:private cache-dir
  (io/file project-dir ".clj-apt-reaction-image"))
(def ^:private default-index-file
  (io/file cache-dir "index.edn"))
(def ^:private supported-extensions
  #{"png" "jpg" "jpeg" "gif" "webp" "heic" "heif" "bmp" "tif" "tiff"})
(def ^:private stop-words
  #{"a" "an" "and" "are" "as" "at" "be" "but" "by" "for" "from" "i" "if" "in"
    "is" "it" "its" "me" "my" "of" "on" "or" "our" "so" "that" "the" "their"
    "them" "they" "this" "to" "us" "we" "you" "your"})

(defn- usage []
  (str/join
   \newline
   ["clj-apt-reaction-image"
    ""
    "Commands:"
    "  index --images-dir PATH [--index-file PATH] [--vision-model MODEL] [--rank-model MODEL]"
    "  query --text \"message here\" [--images-dir PATH] [--index-file PATH] [--top N]"
    ""
    "Defaults:"
    (str "  vision model: " default-vision-model)
    "  rank model: defaults to the vision model"
    (str "  vision max side: " default-vision-max-side " px")
    (str "  checkpoint every: " default-checkpoint-every " changed images")
    "  host: OLLAMA_HOST or http://localhost:11434"
    ""
    "Examples:"
    "  clojure -M:run index --images-dir \"/path/to/images\""
    "  clojure -M:run query --text \"bro what\" --images-dir \"/path/to/images\""]))

(defn- ensure-dir! [^java.io.File dir]
  (.mkdirs dir)
  dir)

(defn- normalize-text [s]
  (-> (or s "")
      str
      (str/replace #"\s+" " ")
      str/trim))

(defn- non-blank [s]
  (let [s (normalize-text s)]
    (when-not (str/blank? s) s)))

(defn- extension-of [^java.io.File file]
  (some-> (.getName file)
          (str/split #"\.")
          last
          str/lower-case))

(defn- image-file? [^java.io.File file]
  (and (.isFile file)
       (contains? supported-extensions (extension-of file))))

(defn- collect-images [images-dir]
  (->> (file-seq (io/file images-dir))
       (filter image-file?)
       (sort-by #(.getAbsolutePath ^java.io.File %))
       vec))

(defn- tokenize [s]
  (->> (re-seq #"[A-Za-z0-9]+" (str/lower-case (or s "")))
       (map #(str/replace % #"^'+|'+$" ""))
       (remove #(or (< (count %) 2)
                    (contains? stop-words %)))
       vec))

(defn- token-frequencies [s]
  (reduce (fn [freq token]
            (update freq token (fnil inc 0)))
          {}
          (tokenize s)))

(defn- read-edn-file [path]
  (when (.exists (io/file path))
    (-> path slurp edn/read-string)))

(defn- write-edn-file! [path value]
  (let [target (io/file path)]
    (ensure-dir! (.getParentFile target))
    (spit target (binding [*print-length* nil
                           *print-level* nil]
                   (pr-str value)))))

(defn- command! [& args]
  (let [{:keys [exit out err]} (apply sh/sh args)]
    (when-not (zero? exit)
      (throw (ex-info (str "Command failed: " (str/join " " args))
                      {:args args
                       :exit exit
                       :out out
                       :err err})))
    out))

(defn- command-exists? [name]
  (zero? (:exit (sh/sh "which" name))))

(defn- ocr-image! [image-path]
  (normalize-text
   (command! "tesseract" image-path "stdout" "--psm" "6")))

(defn- ensure-prerequisites! []
  (command! "tesseract" "--version")
  true)

(defn- parse-args [argv]
  (loop [args argv
         parsed {:positionals []}]
    (if-let [arg (first args)]
      (if (str/starts-with? arg "--")
        (let [key-name (keyword (subs arg 2))
              value (second args)]
          (when (or (nil? value) (str/starts-with? value "--"))
            (throw (ex-info (str "Missing value for " arg) {:arg arg})))
          (recur (nnext args) (assoc parsed key-name value)))
        (recur (next args) (update parsed :positionals conj arg)))
      parsed)))

(defn- canonical-path [path]
  (.getAbsolutePath (.getCanonicalFile (io/file path))))

(defn- default-config [opts]
  (let [vision-model (or (:vision-model opts)
                         (System/getenv "MAYMAY_VISION_MODEL")
                         default-vision-model)]
    {:images-dir (some-> (:images-dir opts) canonical-path)
     :index-file (canonical-path (or (:index-file opts) (.getAbsolutePath default-index-file)))
     :host (ollama/default-host)
     :vision-model vision-model
     :rank-model (or (:rank-model opts)
                     (System/getenv "MAYMAY_RANK_MODEL")
                     vision-model)
     :top-n (Long/parseLong (or (:top opts) "5"))
     :candidate-count (Long/parseLong (or (:candidate-count opts) (str default-candidate-count)))
     :vision-max-side (Long/parseLong (or (:vision-max-side opts)
                                          (System/getenv "MAYMAY_VISION_MAX_SIDE")
                                          (str default-vision-max-side)))
     :checkpoint-every (Long/parseLong (or (:checkpoint-every opts)
                                           (str default-checkpoint-every)))}))

(defn- load-index [index-file]
  (or (read-edn-file index-file)
      {:version index-version
       :entries []}))

(defn- previous-entry-by-path [entries]
  (into {} (map (juxt :path identity) entries)))

(defn- as-string-vec [xs]
  (->> (cond
         (sequential? xs) xs
         (string? xs) (str/split xs #",")
         :else [])
       (map #(-> %
                 str
                 normalize-text))
       (remove str/blank?)
       vec))

(defn- lower-string-vec [xs]
  (mapv str/lower-case (as-string-vec xs)))

(defn- distinct-vec [xs]
  (vec (distinct xs)))

(defn- clip-text [s max-len]
  (let [s (normalize-text s)]
    (if (> (count s) max-len)
      (str (subs s 0 (- max-len 3)) "...")
      s)))

(defn- extract-json-object [s]
  (let [s (normalize-text s)
        start (.indexOf s "{")
        end (.lastIndexOf s "}")]
    (when (and (<= 0 start) (< start end))
      (subs s start (inc end)))))

(defn- parse-json-content [content]
  (let [candidate (or (extract-json-object content) content)]
    (json/read-str candidate :key-fn keyword)))

(defn- image-index-prompt [id ocr-text]
  (str
   "You are indexing one reaction image for retrieval.\n"
   "Return JSON only.\n"
   "Return exactly these keys: "
   "{\"id\":\"string\",\"caption\":\"string\",\"reaction_tags\":[\"string\"],"
   "\"scene_tags\":[\"string\"],\"visible_text\":\"string\",\"people\":[\"string\"],"
   "\"emotions\":[\"string\"],\"notes\":\"string\"}\n"
   "Rules:\n"
   "- id must be exactly \"" id "\".\n"
   "- caption: max 12 words.\n"
   "- reaction_tags: max 4 short lowercase tags.\n"
   "- scene_tags: max 4 short lowercase tags.\n"
   "- visible_text: short important text only, max 160 chars, or empty.\n"
   "- people: max 3 short lowercase descriptions.\n"
   "- emotions: max 3 short lowercase words.\n"
   "- notes: one short usage hint, max 12 words.\n"
   "- Do not include markdown fences.\n"
   (if (str/blank? ocr-text)
     ""
     (str "OCR hint from a separate tool, use only as a hint if it matches the image:\n"
          (clip-text ocr-text 280)
          "\n"))))

(defn- vision-cache-dir []
  (ensure-dir! (io/file cache-dir "vision-cache")))

(defn- vision-cache-path [config ^java.io.File image-file]
  (let [base-name (str (.getName image-file)
                       "--" (.lastModified image-file)
                       "--" (.length image-file)
                       "--" (:vision-max-side config)
                       ".jpg")]
    (.getAbsolutePath (io/file (vision-cache-dir) base-name))))

(defn- prepare-vision-image! [config ^java.io.File image-file]
  (let [source (.getAbsolutePath image-file)
        target (vision-cache-path config image-file)
        target-file (io/file target)]
    (if (.exists target-file)
      target
      (do
        (cond
          (command-exists? "sips")
          (command! "sips"
                    "-s" "format" "jpeg"
                    "-s" "formatOptions" "70"
                    "-Z" (str (:vision-max-side config))
                    source
                    "--out" target)

          (command-exists? "python3")
          (command! "python3" "-c"
                    (str
                     "from PIL import Image, ImageOps; "
                     "import sys; "
                     "src, dst, max_side = sys.argv[1], sys.argv[2], int(sys.argv[3]); "
                     "img = Image.open(src); "
                     "img = ImageOps.exif_transpose(img).convert('RGB'); "
                     "img.thumbnail((max_side, max_side), Image.Resampling.LANCZOS); "
                     "img.save(dst, format='JPEG', quality=70, optimize=True)")
                    source
                    target
                    (str (:vision-max-side config)))

          :else
          (throw (ex-info "No supported image resize tool found. Install python3 with Pillow or use macOS sips."
                          {:source source})))
        target))))

(defn- normalize-semantic-payload [image-file payload ocr-text config]
  (let [id (.getName ^java.io.File image-file)
        visible-text (or (non-blank (:visible_text payload))
                         (non-blank ocr-text)
                         "")]
     {:id id
     :caption (or (non-blank (:caption payload)) "")
     :reaction-tags (lower-string-vec (:reaction_tags payload))
     :scene-tags (lower-string-vec (:scene_tags payload))
     :visible-text visible-text
     :people (lower-string-vec (:people payload))
     :emotions (lower-string-vec (:emotions payload))
     :notes (or (non-blank (:notes payload)) "")
     :semantic-model (:vision-model config)
     :semantic-revision semantic-revision
     :vision-max-side (:vision-max-side config)}))

(defn- describe-image! [config ^java.io.File image-file ocr-text]
  (let [prepared-image (prepare-vision-image! config image-file)
        prompt (image-index-prompt (.getName image-file) ocr-text)
        response (ollama/chat! (:host config)
                               (:vision-model config)
                               prompt
                               {:images [prepared-image]
                                :format "json"})
        payload (parse-json-content (ollama/assistant-content response))]
    (normalize-semantic-payload image-file payload ocr-text config)))

(defn- build-search-text [{:keys [caption reaction-tags scene-tags visible-text people emotions notes ocr-text]}]
  (str/join
   " "
   (remove str/blank?
           [(or caption "")
            (str/join " " reaction-tags)
            (str/join " " reaction-tags)
            (str/join " " scene-tags)
            (str/join " " emotions)
            (str/join " " people)
            (or notes "")
            (or visible-text "")
            (or ocr-text "")])))

(defn- now-ms []
  (System/currentTimeMillis))

(defn- build-entry [config ^java.io.File image-file progress-label]
  (let [path (.getAbsolutePath image-file)
        base-entry {:id (.getName image-file)
                    :path path
                    :size (.length image-file)
                    :last-modified (.lastModified image-file)}]
    (println (str progress-label " OCR"))
    (let [total-start (now-ms)
          ocr-start total-start
          ocr-result (try
                       {:ocr-text (or (ocr-image! path) "")}
                       (catch Exception ex
                         {:ocr-text ""
                          :ocr-error (.getMessage ex)}))
          ocr-ms (- (now-ms) ocr-start)
          _ (println (str progress-label " semantic"))
          semantic-start (now-ms)
          semantic-result (try
                            (describe-image! config image-file (:ocr-text ocr-result))
                            (catch Exception ex
                              {:caption ""
                               :reaction-tags []
                               :scene-tags []
                               :visible-text (or (non-blank (:ocr-text ocr-result)) "")
                               :people []
                               :emotions []
                               :notes ""
                               :semantic-model (:vision-model config)
                               :semantic-error (.getMessage ex)}))
          semantic-ms (- (now-ms) semantic-start)
          entry (merge base-entry
                       ocr-result
                       semantic-result
                       {:semantic-model (:vision-model config)
                        :semantic-revision semantic-revision
                        :vision-max-side (:vision-max-side config)})
          search-text (build-search-text entry)]
      (println (format "%s done (ocr=%dms semantic=%dms total=%dms)"
                       progress-label
                       ocr-ms
                       semantic-ms
                       (- (now-ms) total-start)))
      (assoc entry
             :search-text search-text
             :token-freq (token-frequencies search-text)))))

(defn- reusable-entry? [old-entry ^java.io.File image-file config]
  (and old-entry
       (= (:size old-entry) (.length image-file))
       (= (:last-modified old-entry) (.lastModified image-file))
       (= (:semantic-model old-entry) (:vision-model config))
       (= (:vision-max-side old-entry) (:vision-max-side config))
       (= (:semantic-revision old-entry) semantic-revision)
       (contains? old-entry :caption)
       (contains? old-entry :token-freq)))

(defn- summarize-refresh [existing total new-count changed-count removed-count]
  (println (if (seq (:entries existing))
             "refreshing index..."
             "building index..."))
  (println (format "total=%d new=%d changed=%d removed=%d"
                   total new-count changed-count removed-count)))

(defn- make-index-data [config images-dir entries complete?]
  {:version index-version
   :complete? complete?
   :images-dir images-dir
   :vision-model (:vision-model config)
   :rank-model (:rank-model config)
   :vision-max-side (:vision-max-side config)
   :semantic-revision semantic-revision
   :entry-count (count entries)
   :entries entries})

(defn- build-index! [config]
  (ensure-prerequisites!)
  (let [images-dir (:images-dir config)
        index-file (:index-file config)
        images (collect-images images-dir)
        existing (load-index index-file)
        old-by-path (previous-entry-by-path (:entries existing))
        removed-count (count (remove (set (map #(.getAbsolutePath ^java.io.File %) images))
                                     (keys old-by-path)))
        total (count images)]
    (when (zero? total)
      (throw (ex-info "No supported image files found" {:images-dir images-dir})))
    (let [statuses (mapv (fn [^java.io.File image-file]
                           (let [path (.getAbsolutePath image-file)
                                 old-entry (get old-by-path path)
                                 status (cond
                                          (nil? old-entry) :new
                                          (reusable-entry? old-entry image-file config) :reused
                                          :else :changed)]
                             {:file image-file
                              :old-entry old-entry
                              :status status}))
                         images)
          new-count (count (filter #(= :new (:status %)) statuses))
          changed-count (count (filter #(= :changed (:status %)) statuses))
          process-total (+ new-count changed-count)
          checkpoint-every (max 1 (long (or (:checkpoint-every config)
                                            default-checkpoint-every)))]
      (summarize-refresh existing total new-count changed-count removed-count)
      (if (and (zero? process-total)
               (zero? removed-count)
               (= (:images-dir existing) images-dir)
               (= (:version existing) index-version)
               (= (:vision-model existing) (:vision-model config))
               (= (:rank-model existing) (:rank-model config)))
        (do
          (println "index is up to date.")
          existing)
        (loop [remaining statuses
               entries []
               changed-processed 0]
          (if-let [{:keys [^java.io.File file old-entry status]} (first remaining)]
            (let [[entry changed-processed']
                  (case status
                    :reused [old-entry changed-processed]
                    (let [current (inc changed-processed)
                          label (format "[%d/%d] %s ->" current process-total (.getName file))
                          entry (build-entry config file label)]
                      [entry current]))
                  entries' (conj entries entry)]
              (when (and (pos? changed-processed')
                         (or (= changed-processed' process-total)
                             (zero? (mod changed-processed' checkpoint-every))))
                (write-edn-file! index-file (make-index-data config images-dir entries' false))
                (println (format "checkpoint saved (%d/%d changed, %d entries)"
                                 changed-processed'
                                 process-total
                                 (count entries'))))
              (recur (next remaining) entries' changed-processed'))
            (let [index-data (make-index-data config images-dir entries true)]
              (write-edn-file! index-file index-data)
              (println "saved index to" index-file)
              index-data)))))))

(defn- document-frequencies [entries]
  (reduce (fn [acc {:keys [token-freq]}]
            (reduce (fn [inner token]
                      (update inner token (fnil inc 0)))
                    acc
                    (keys token-freq)))
          {}
          entries))

(defn- inverse-doc-frequency [total-docs doc-frequency]
  (Math/log (double (+ 1.0 (/ (+ total-docs 1.0)
                              (+ doc-frequency 1.0))))))

(defn- overlap-score [query-freq candidate-freq doc-freq total-docs]
  (reduce-kv
   (fn [score token query-count]
     (let [candidate-count (get candidate-freq token 0)]
       (if (zero? candidate-count)
         score
         (+ score
            (* (double query-count)
               (min 4.0 (double candidate-count))
               (inverse-doc-frequency total-docs (get doc-freq token 0)))))))
   0.0
   query-freq))

(defn- phrase-bonus [query candidate]
  (let [query (normalize-text (str/lower-case query))
        candidate (normalize-text (str/lower-case candidate))
        short-candidate? (< (count candidate) 8)]
    (cond
      (or (str/blank? query) (str/blank? candidate) short-candidate?) 0.0
      (str/includes? candidate query) 6.0
      (str/includes? query candidate) 2.0
      :else 0.0)))

(defn- score-entry [query query-freq doc-freq total-docs entry]
  (+ (overlap-score query-freq (:token-freq entry) doc-freq total-docs)
     (phrase-bonus query (:search-text entry))
     (if (some #(= % (str/lower-case (normalize-text query))) (:reaction-tags entry)) 2.0 0.0)))

(defn- candidate-summary [entry]
  {:id (:id entry)
   :caption (:caption entry)
   :reaction_tags (:reaction-tags entry)
   :scene_tags (:scene-tags entry)
   :emotions (:emotions entry)
   :people (:people entry)
   :notes (:notes entry)
   :visible_text (clip-text (:visible-text entry) 200)})

(defn- query-analysis-prompt [query]
  (str
   "You are extracting reaction-search intent from a conversation snippet.\n"
   "Return JSON only in this shape: "
   "{\"query_text\":\"string\",\"intent\":\"string\",\"desired_reaction_tags\":[\"string\"],"
   "\"tone\":[\"string\"],\"keywords\":[\"string\"]}\n"
   "Rules:\n"
   "- intent: one short sentence.\n"
   "- desired_reaction_tags: max 6 short lowercase tags for the kind of reaction image that would fit.\n"
   "- tone: max 4 short lowercase tags.\n"
   "- keywords: max 6 short lowercase words or short phrases.\n"
   "- Use the original text when possible, but infer likely reaction style if the text is colloquial.\n\n"
   "Conversation snippet:\n"
   query))

(defn- normalize-query-profile [query payload]
  {:query-text (or (non-blank (:query_text payload)) query)
   :intent (or (non-blank (:intent payload)) "")
   :desired-reaction-tags (distinct-vec (lower-string-vec (:desired_reaction_tags payload)))
   :tone (distinct-vec (lower-string-vec (:tone payload)))
   :keywords (distinct-vec (lower-string-vec (:keywords payload)))})

(defn- analyze-query! [config query]
  (let [response (ollama/chat! (:host config)
                               (:rank-model config)
                               (query-analysis-prompt query)
                               {:format "json"
                                :temperature 0.1})
        payload (parse-json-content (ollama/assistant-content response))]
    (normalize-query-profile query payload)))

(defn- build-query-search-text [query profile]
  (str/join
   " "
   (remove str/blank?
           [query
            (:intent profile)
            (str/join " " (:desired-reaction-tags profile))
            (str/join " " (:desired-reaction-tags profile))
            (str/join " " (:tone profile))
            (str/join " " (:keywords profile))])))

(defn- rerank-prompt [query profile candidates]
  (str
   "You are choosing the best reaction image for a conversation snippet.\n"
   "Pick the single best candidate id from the provided metadata.\n"
   "Return JSON only in this shape: "
   "{\"best_id\":\"string\",\"reason\":\"string\",\"alternate_ids\":[\"string\"]}\n"
   "Rules:\n"
   "- best_id must be exactly one of the candidate ids.\n"
   "- alternate_ids may contain up to 3 other ids from the candidate list.\n"
   "- Prefer images that feel like a reaction, not just a topical keyword match.\n"
   "- Keep reason to one short sentence.\n\n"
   "User query:\n"
   query
   "\n\nAnalyzed intent:\n"
   (json/write-str {:intent (:intent profile)
                    :desired_reaction_tags (:desired-reaction-tags profile)
                    :tone (:tone profile)
                    :keywords (:keywords profile)})
   "\n\nCandidates:\n"
   (json/write-str (mapv candidate-summary candidates))))

(defn- batch-select-prompt [query profile candidates]
  (str
   "You are choosing promising reaction image candidates for a conversation snippet.\n"
   "From the candidate metadata, return the ids that are most likely to work as a reaction image.\n"
   "Return JSON only in this shape: "
   "{\"selected_ids\":[\"string\"],\"reason\":\"string\"}\n"
   "Rules:\n"
   "- selected_ids must contain 1 to 5 ids from the provided candidate list.\n"
   "- Prefer reaction fit over literal word overlap.\n"
   "- Short colloquial text like \"bro what\" should map to likely reaction intent.\n"
   "- Keep reason short.\n\n"
   "User query:\n"
   query
   "\n\nAnalyzed intent:\n"
   (json/write-str {:intent (:intent profile)
                    :desired_reaction_tags (:desired-reaction-tags profile)
                    :tone (:tone profile)
                    :keywords (:keywords profile)})
   "\n\nCandidates:\n"
   (json/write-str (mapv candidate-summary candidates))))

(defn- rerank-candidates! [config query profile candidates]
  (let [response (ollama/chat! (:host config)
                               (:rank-model config)
                               (rerank-prompt query profile candidates)
                               {:format "json"
                                :temperature 0.1})
        payload (parse-json-content (ollama/assistant-content response))
        allowed-ids (set (map :id candidates))
        best-id (when (contains? allowed-ids (:best_id payload))
                  (:best_id payload))
        alternate-ids (->> (:alternate_ids payload)
                           as-string-vec
                           (filter allowed-ids)
                           (remove #(= % best-id))
                           vec)]
    (when best-id
      {:best-id best-id
       :reason (or (non-blank (:reason payload)) "")
       :alternate-ids alternate-ids})))

(defn- select-candidates-batch! [config query profile candidates]
  (let [response (ollama/chat! (:host config)
                               (:rank-model config)
                               (batch-select-prompt query profile candidates)
                               {:format "json"
                                :temperature 0.1})
        payload (parse-json-content (ollama/assistant-content response))
        allowed-ids (set (map :id candidates))]
    (->> (:selected_ids payload)
         as-string-vec
         (filter allowed-ids)
         distinct-vec)))

(defn- snippet-for [entry]
  (or (non-blank (:caption entry))
      (non-blank (:notes entry))
      (non-blank (:visible-text entry))
      ""))

(defn- lexical-shortlist [entries query-text candidate-count]
  (let [query-freq (token-frequencies query-text)
        total-docs (count entries)
        doc-freq (document-frequencies entries)]
    (->> entries
         (map (fn [entry]
                (assoc entry :score (score-entry query-text query-freq doc-freq total-docs entry))))
         (filter #(pos? (:score %)))
         (sort-by (juxt (comp - :score) :id))
         (take candidate-count)
         vec)))

(defn- model-shortlist [config query profile entries]
  (let [batches (partition-all model-fallback-batch-size entries)
        chosen-ids (reduce (fn [acc batch]
                             (let [picked (try
                                            (select-candidates-batch! config query profile batch)
                                            (catch Exception _
                                              []))]
                               (into acc picked)))
                           []
                           batches)
        by-id (into {} (map (juxt :id identity) entries))]
    (->> chosen-ids
         distinct
         (keep by-id)
         vec)))

(defn- reorder-with-rerank [candidates {:keys [best-id alternate-ids]}]
  (let [by-id (into {} (map (juxt :id identity) candidates))
        preferred-ids (concat [best-id] alternate-ids)
        preferred (keep by-id preferred-ids)
        preferred-set (set preferred-ids)
        remainder (remove #(contains? preferred-set (:id %)) candidates)]
    (vec (concat preferred remainder))))

(defn- resolve-images-dir [config existing-index]
  (or (:images-dir config)
      (:images-dir existing-index)
      (throw (ex-info "Provide --images-dir on the first run so the index knows which folder to watch." {}))))

(defn- refresh-index! [config]
  (let [existing (load-index (:index-file config))
        config (assoc config :images-dir (resolve-images-dir config existing))]
    (build-index! config)))

(defn- print-query-result [ranked rerank]
  (let [best (first ranked)]
    (println (str "best: " (:path best)))
    (println (str "  id: " (:id best)))
    (when-let [reason (non-blank (:reason rerank))]
      (println (str "  reason: " reason)))
    (when-let [caption (non-blank (:caption best))]
      (println (str "  caption: " caption)))
    (when (seq (:reaction-tags best))
      (println (str "  reaction_tags: " (str/join ", " (:reaction-tags best)))))
    (when-let [notes (non-blank (:notes best))]
      (println (str "  notes: " notes)))
    (when (> (count ranked) 1)
      (println "alternates:")
      (doseq [entry (take 4 (rest ranked))]
        (println (str "  - " (:path entry)))
        (when-let [snippet (non-blank (snippet-for entry))]
          (println (str "    " (clip-text snippet 120))))))))

(defn- query-index [config query-text]
  (let [index (refresh-index! config)
        entries (:entries index)]
    (when (empty? entries)
      (throw (ex-info "Index file is empty or missing entries" {:index-file (:index-file config)})))
    (let [profile (try
                    (analyze-query! config query-text)
                    (catch Exception _
                      {:query-text query-text
                       :intent ""
                       :desired-reaction-tags []
                       :tone []
                       :keywords []}))
          enhanced-query (build-query-search-text query-text profile)
          _ (when (seq (:desired-reaction-tags profile))
              (println (str "query tags: " (str/join ", " (:desired-reaction-tags profile)))))
          lexical-candidates (lexical-shortlist entries enhanced-query (:candidate-count config))
          candidates (if (seq lexical-candidates)
                       lexical-candidates
                       (do
                         (println "No lexical shortlist matches found, using model metadata fallback...")
                         (model-shortlist config query-text profile entries)))]
      (when (empty? candidates)
        (println "No semantic shortlist matches found in the current index.")
        (System/exit 2))
      (let [rerank (try
                     (rerank-candidates! config query-text profile candidates)
                     (catch Exception _
                       nil))
            ranked (if rerank
                     (reorder-with-rerank candidates rerank)
                     candidates)]
        (print-query-result (take (:top-n config) ranked) rerank)
        ranked))))

(defn- require-option [opts key-name]
  (or (get opts key-name)
      (throw (ex-info (str "Missing required option --" (name key-name))
                      {:option key-name}))))

(defn- run-index! [args]
  (let [opts (parse-args args)
        config (assoc (default-config opts)
                      :images-dir (canonical-path (require-option opts :images-dir)))]
    (build-index! config)))

(defn- run-query! [args]
  (let [opts (parse-args args)
        config (default-config opts)
        query-text (or (:text opts)
                       (some->> (:positionals opts) seq (str/join " ")))]
    (when (str/blank? query-text)
      (throw (ex-info "Provide query text with --text" {})))
    (query-index config query-text)))

(defn -main [& argv]
  (try
    (let [[command & args] argv]
      (case command
        "index" (run-index! args)
        "query" (run-query! args)
        (do
          (println (usage))
          (System/exit 1))))
    (catch Exception ex
      (binding [*out* *err*]
        (println (.getMessage ex))
        (when-let [err (:err (ex-data ex))]
          (when-not (str/blank? err)
            (println err)))
        (println)
        (println (usage)))
      (System/exit 1))))
