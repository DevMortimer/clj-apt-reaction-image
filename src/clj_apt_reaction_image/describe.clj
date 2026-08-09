(ns clj-apt-reaction-image.describe
  "The describe seam: media file → description {:ocr-text :classes :faces}.
  Two adapters behind it: still (per-file auge calls) and clip (frame
  sampling + frame merge). Media classification (what is a media file,
  what is a clip) lives here too, so the pipeline asks one module.

  Failure degrades: a file may legitimately have no text, no recognizable
  classes, no faces — the description falls back to empty values and the
  pipeline proceeds with a fallback name."
  (:require
   [clj-apt-reaction-image.log :as log]
   [clj-apt-reaction-image.tools :as tools]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;; ─── media classification ──────────────────────────────────────────────

(def ^:private still-extensions
  #{"png" "jpg" "jpeg" "webp" "heic" "heif" "bmp" "tif" "tiff"})

(def ^:private clip-extensions
  #{"gif" "mp4" "mov" "m4v" "webm" "mkv"})

(defn extension-of [^java.io.File file]
  "The lowercase extension of a file, or nil when it has none."
  (let [name (.getName file)
        dot (.lastIndexOf name ".")]
    (when (pos? dot)
      (str/lower-case (subs name (inc dot))))))

(defn media-file?
  "Is this a file the describe module can handle?"
  [^java.io.File file]
  (and (.isFile file)
       (contains? (into still-extensions clip-extensions) (extension-of file))))

(defn clip-file?
  "Is this media file a clip (animated GIF or video)?"
  [^java.io.File file]
  (contains? clip-extensions (extension-of file)))

;; ─── helpers ───────────────────────────────────────────────────────────

(defn normalize-text [s]
  (-> (or s "") str (str/replace #"\s+" " ") str/trim))

;; ─── frame sampling ────────────────────────────────────────────────────

(defn sample-fractions
  "N evenly spaced, centered: n=6 → 1/12, 3/12, 5/12, 7/12, 9/12, 11/12."
  [n]
  (mapv #(/ (+ 1.0 (* 2 %)) (* 2 n)) (range n)))

(defn- extract-frames! [config path n]
  (let [temp-dir (.toFile (java.nio.file.Files/createTempDirectory
                           "clj-apt-frames"
                           (make-array java.nio.file.attribute.FileAttribute 0)))
        duration (tools/probe-duration! config path)
        timestamps (if duration
                     (mapv #(* duration %) (sample-fractions n))
                     [0.0])]
    (try
      (doseq [[i t] (map-indexed vector timestamps)]
        (tools/extract-frame! config path t
                              (io/file (.getAbsolutePath temp-dir)
                                       (format "frame-%03d.png" i))))
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

;; ─── frame merge ───────────────────────────────────────────────────────

(defn merge-ocr-texts
  "OCR text from N frames: concatenated in temporal order, repeats
  deduped case-insensitively."
  [texts]
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

(defn merge-classifications
  "Class labels from N frames: unioned, ranked by max confidence,
  capped at five."
  [per-frame-classifications]
  (let [best (->> (flatten (seq per-frame-classifications))
                  (remove nil?)
                  (group-by #(str/lower-case (name (:label %))))
                  (mapv (fn [[_ items]]
                          (apply max-key :confidence items)))
                  (sort-by #(- (:confidence %)))
                  (take 5)
                  (map #(name (:label %))))]
    (str/join ", " best)))

(defn merge-face-counts
  "Face counts from N frames: the max seen in any one frame."
  [counts]
  (reduce max 0 counts))

;; ─── adapters ──────────────────────────────────────────────────────────

(defn- describe-still! [config path]
  (let [ocr-text (or (some-> (tools/auge-json! config "--ocr" path)
                             :results :text normalize-text)
                     "")
        classes (->> (some-> (tools/auge-json! config "--classify" path)
                             (get-in [:results :classifications]))
                     (take 5)
                     (map :label)
                     (map name)
                     (remove str/blank?)
                     (str/join ", "))
        faces (get-in (tools/auge-json! config "--faces" path)
                      [:results :count] 0)]
    {:ocr-text ocr-text :classes classes :faces faces}))

(defn- describe-clip! [config path]
  (let [n (:frames config)
        {:keys [dir frames]} (extract-frames! config path n)]
    (try
      (if (empty? frames)
        (do
          (log/log! config (str "  no frames extracted, falling back to still analysis"))
          (describe-still! config path))
        (let [frame-paths (mapv #(.getAbsolutePath ^java.io.File %) frames)]
          (log/log! config (str "  frames: " (count frames) " sampled"))
          (let [ocr-batch (tools/auge-ndjson! config "--ocr" frame-paths)
                classify-batch (tools/auge-ndjson! config "--classify" frame-paths)
                faces-batch (tools/auge-ndjson! config "--faces" frame-paths)
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

;; ─── the seam ──────────────────────────────────────────────────────────

(defn describe
  "The seam: describe a media file. Dispatch on clip vs still.
  Returns {:ocr-text :classes :faces}."
  [config ^java.io.File media-file]
  (let [path (.getAbsolutePath media-file)]
    (if (clip-file? media-file)
      (describe-clip! config path)
      (describe-still! config path))))
