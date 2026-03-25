(ns maymay-reactor.core
  (:gen-class)
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.java.shell :as sh]
   [clojure.string :as str]))

(def ^:private project-dir
  (.getCanonicalFile (io/file ".")))

(def ^:private cache-dir
  (io/file project-dir ".maymay-reactor"))

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
   ["maymay-reactor"
    ""
    "Commands:"
    "  index --images-dir PATH [--index-file PATH]"
    "  query --text \"message here\" [--index-file PATH] [--top N]"
    ""
    "Examples:"
    "  clojure -M:run index --images-dir \"/path/to/images\""
    "  clojure -M:run query --text \"when they leave me on read\""]))

(defn- ensure-dir! [^java.io.File dir]
  (.mkdirs dir)
  dir)

(defn- normalize-text [s]
  (-> (or s "")
      (str/replace #"\s+" " ")
      str/trim))

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
  (->> (re-seq #"[[:alnum:]]+" (str/lower-case (or s "")))
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

(defn- load-index [index-file]
  (or (read-edn-file index-file)
      {:version 1
       :entries []}))

(defn- previous-entry-by-path [entries]
  (into {} (map (juxt :path identity) entries)))

(defn- build-entry [^java.io.File image-file]
  (let [path (.getAbsolutePath image-file)
        base-entry {:path path
                    :size (.length image-file)
                    :last-modified (.lastModified image-file)}]
    (try
      (let [ocr-text (ocr-image! path)]
        (assoc base-entry
               :ocr-text ocr-text
               :token-freq (token-frequencies ocr-text)))
      (catch Exception ex
        (binding [*out* *err*]
          (println "Skipping OCR for" path)
          (println " " (.getMessage ex)))
        (assoc base-entry
               :ocr-text ""
               :token-freq {}
               :ocr-error (.getMessage ex))))))

(defn- reusable-entry? [old-entry ^java.io.File image-file]
  (and old-entry
       (= (:size old-entry) (.length image-file))
       (= (:last-modified old-entry) (.lastModified image-file))))

(defn- build-index! [images-dir index-file]
  (ensure-prerequisites!)
  (let [images (collect-images images-dir)
        existing (load-index index-file)
        old-by-path (previous-entry-by-path (:entries existing))
        total (count images)]
    (when (zero? total)
      (throw (ex-info "No supported image files found" {:images-dir images-dir})))
    (println "Found" total "images.")
    (let [entries
          (mapv (fn [idx ^java.io.File image-file]
                  (let [path (.getAbsolutePath image-file)
                        old-entry (get old-by-path path)]
                    (println (format "[%d/%d] %s" (inc idx) total (.getName image-file)))
                    (if (reusable-entry? old-entry image-file)
                      old-entry
                      (build-entry image-file))))
                (range total)
                images)
          index-data {:version 1
                      :images-dir (.getAbsolutePath (io/file images-dir))
                      :entry-count total
                      :entries entries}]
      (write-edn-file! index-file index-data)
      (println "Wrote index to" (.getAbsolutePath (io/file index-file)))
      index-data)))

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

(defn- phrase-bonus [query ocr-text]
  (let [query (normalize-text (str/lower-case query))
        ocr-text (normalize-text (str/lower-case ocr-text))
        short-candidate? (< (count ocr-text) 8)]
    (cond
      (or (str/blank? query) (str/blank? ocr-text) short-candidate?) 0.0
      (str/includes? ocr-text query) 6.0
      (str/includes? query ocr-text) 3.0
      :else 0.0)))

(defn- score-entry [query query-freq doc-freq total-docs entry]
  (+ (overlap-score query-freq (:token-freq entry) doc-freq total-docs)
     (phrase-bonus query (:ocr-text entry))))

(defn- snippet-for [ocr-text]
  (let [text (normalize-text ocr-text)]
    (if (> (count text) 120)
      (str (subs text 0 117) "...")
      text)))

(defn- query-index [index-file query-text top-n]
  (let [{:keys [entries]} (load-index index-file)]
    (when (empty? entries)
      (throw (ex-info "Index file is empty or missing entries" {:index-file index-file})))
    (let [query-freq (token-frequencies query-text)
          total-docs (count entries)
          doc-freq (document-frequencies entries)
          ranked (->> entries
                      (map (fn [entry]
                             (assoc entry :score (score-entry query-text query-freq doc-freq total-docs entry))))
                      (filter #(pos? (:score %)))
                      (sort-by (juxt (comp - :score) :path))
                      (take top-n)
                      vec)]
      (when (empty? ranked)
        (println "No OCR-backed matches found.")
        (System/exit 2))
      (doseq [{:keys [score path ocr-text]} ranked]
        (println (format "%.3f\t%s" score path))
        (when-not (str/blank? ocr-text)
          (println (str "  " (snippet-for ocr-text)))))
      ranked)))

(defn- require-option [opts key-name]
  (or (get opts key-name)
      (throw (ex-info (str "Missing required option --" (name key-name))
                      {:option key-name}))))

(defn- run-index! [args]
  (let [opts (parse-args args)
        images-dir (require-option opts :images-dir)
        index-file (or (:index-file opts) (.getAbsolutePath default-index-file))]
    (build-index! images-dir index-file)))

(defn- run-query! [args]
  (let [opts (parse-args args)
        index-file (or (:index-file opts) (.getAbsolutePath default-index-file))
        top-n (Long/parseLong (or (:top opts) "5"))
        query-text (or (:text opts)
                       (some->> (:positionals opts) seq (str/join " ")))]
    (when (str/blank? query-text)
      (throw (ex-info "Provide query text with --text" {})))
    (query-index index-file query-text top-n)))

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
