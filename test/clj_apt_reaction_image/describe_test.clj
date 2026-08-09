(ns clj-apt-reaction-image.describe-test
  (:require
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is run-tests]]
   [clj-apt-reaction-image.describe :as describe]))

;; ─── helpers ──────────────────────────────────────────────────────────

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "clj-apt-reaction-image-describe-test"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- fake-run [result-map]
  (fn [_args _stdin] result-map))

(defn- still-run
  "Fake runner for still images: answers auge single-file calls."
  []
  (fn [args _stdin]
    (case (second args)
      "--ocr" {:exit 0 :out (json/write-str {:results {:text "hello world"}}) :err ""}
      "--classify" {:exit 0 :out (json/write-str
                                  {:results {:classifications [{:label "cat" :confidence 0.9}]}})
                    :err ""}
      "--faces" {:exit 0 :out (json/write-str {:results {:count 2}}) :err ""}
      {:exit 1 :out "" :err "unexpected auge call"})))

(defn- clip-run
  "Fake runner for clips: ffprobe duration, ffmpeg frame files (distinct
  content per timestamp so MD5 dedupe keeps them), auge ndjson batches."
  [calls]
  (fn [args stdin]
    (swap! calls conj {:args args :stdin stdin})
    (cond
      (= "ffprobe" (first args)) {:exit 0 :out "10.0" :err ""}
      (= "ffmpeg" (first args))
      (do (spit (last args) (nth args 4))   ; content = timestamp, distinct per frame
          {:exit 0 :out "" :err ""})
      (= "auge" (first args))
      (if (some #{"--ndjson"} args)
        (let [paths (str/split-lines (str/trim stdin))
              op (second args)
              line (fn [p]
                     (json/write-str
                      (case op
                        "--ocr" {:file p :results {:text "frame text"}}
                        "--classify" {:file p :results {:classifications [{:label "cat" :confidence 0.9}]}}
                        "--faces" {:file p :results {:count 1}})))]
          {:exit 0 :out (str/join "\n" (map line paths)) :err ""})
        {:exit 1 :out "" :err "unexpected single auge call"})
      :else {:exit 0 :out "" :err ""})))

(defn- no-frames-run
  "Fake runner where probing and frame extraction fail: clip falls back
  to still analysis."
  [calls]
  (fn [args stdin]
    (swap! calls conj {:args args :stdin stdin})
    (cond
      (= "ffprobe" (first args)) {:exit 1 :out "" :err "no media"}
      (= "ffmpeg" (first args)) {:exit 1 :out "" :err "no frames"}
      (= "auge" (first args))
      (if (some #{"--ndjson"} args)
        {:exit 1 :out "" :err "no frames"}
        (case (second args)
          "--ocr" {:exit 0 :out (json/write-str {:results {:text "fallback text"}}) :err ""}
          "--classify" {:exit 0 :out (json/write-str {:results {:classifications []}}) :err ""}
          "--faces" {:exit 0 :out (json/write-str {:results {:count 0}}) :err ""}))
      :else {:exit 0 :out "" :err ""})))

;; ─── media classification ─────────────────────────────────────────────

(deftest extension-of-extracts-lowercase-extension
  (is (= "jpg" (describe/extension-of (io/file "cat.jpg"))))
  (is (= "png" (describe/extension-of (io/file "screenshot.PNG"))))
  (is (= "gif" (describe/extension-of (io/file "reaction.GIF"))))
  (is (nil? (describe/extension-of (io/file "noextension")))))

(deftest media-file-detects-supported-extensions
  (let [dir (temp-dir)]
    (doseq [ext ["jpg" "jpeg" "png" "gif" "webp" "heic" "heif" "bmp" "tif" "tiff"
                 "mp4" "mov" "m4v" "webm" "mkv"]]
      (let [f (io/file dir (str "test." ext))]
        (spit f "data")
        (is (describe/media-file? f) (str "should accept ." ext))))))

(deftest media-file-rejects-unsupported-extensions
  (let [dir (temp-dir)]
    (doseq [ext ["txt" "md" "pdf" "mp3" "" "svg"]]
      (let [f (io/file dir (str "test." ext))]
        (spit f "data")
        (is (not (describe/media-file? f)) (str "should reject ." ext))))))

(deftest clip-file-detects-clips
  (doseq [ext ["gif" "mp4" "mov" "m4v" "webm" "mkv"]]
    (is (describe/clip-file? (io/file (str "test." ext)))
        (str "should accept ." ext))))

(deftest clip-file-rejects-stills
  (doseq [ext ["png" "jpg" "txt"]]
    (is (not (describe/clip-file? (io/file (str "test." ext))))
        (str "should reject ." ext))))

;; ─── normalize-text ───────────────────────────────────────────────────

(deftest normalize-text-collapses-whitespace
  (is (= "hello world" (describe/normalize-text " hello   world ")))
  (is (= "" (describe/normalize-text nil)))
  (is (= "" (describe/normalize-text ""))))

;; ─── sample-fractions ─────────────────────────────────────────────────

(deftest sample-fractions-n6
  (let [fractions (describe/sample-fractions 6)]
    (is (= 6 (count fractions)))
    (is (< 0 (first fractions) 1))
    (is (< 0 (last fractions) 1))
    (is (= (sort fractions) fractions) "ascending")
    (is (< (Math/abs (- (first fractions) (/ 1 12))) 1e-9))
    (is (< (Math/abs (- (last fractions) (/ 11 12))) 1e-9))))

(deftest sample-fractions-n1
  (let [fractions (describe/sample-fractions 1)]
    (is (= 1 (count fractions)))
    (is (< (Math/abs (- (first fractions) 0.5)) 1e-9))))

;; ─── frame merge ──────────────────────────────────────────────────────

(deftest merge-ocr-texts-dedupes-order-preserving
  (is (= "lol boom" (describe/merge-ocr-texts ["lol" "lol" "boom"])))
  (is (= "hi" (describe/merge-ocr-texts ["" "hi" nil])))
  (is (= "Hey" (describe/merge-ocr-texts ["Hey" "hey"])))
  (is (= "" (describe/merge-ocr-texts []))))

(deftest merge-classifications-groups-by-label
  (let [result (describe/merge-classifications
                [[{:label "cat" :confidence 0.9}]
                 [{:label "cat" :confidence 0.5} {:label "dog" :confidence 0.7}]])]
    (is (= "cat, dog" result)))
  (is (= "" (describe/merge-classifications [])))
  (is (= "" (describe/merge-classifications [[] []]))))

(deftest merge-classifications-caps-at-five
  (let [many (vec (for [i (range 10)] [{:label (str "l" i) :confidence 0.5}]))
        result (describe/merge-classifications many)]
    (is (= 5 (count (str/split result #", "))))))

(deftest merge-classifications-handles-nil-entries
  (is (= "cat" (describe/merge-classifications [nil [{:label "cat" :confidence 0.9}]])))
  (is (= "" (describe/merge-classifications [nil nil]))))

(deftest merge-face-counts-takes-max
  (is (= 2 (describe/merge-face-counts [0 2 1])))
  (is (= 0 (describe/merge-face-counts [])))
  (is (= 5 (describe/merge-face-counts [1 5 3]))))

;; ─── the describe seam ────────────────────────────────────────────────

(deftest describe-still-builds-description
  (let [dir (temp-dir)
        f (io/file dir "img.png")]
    (spit f "x")
    (let [desc (describe/describe {:run-fn (still-run)} f)]
      (is (= "hello world" (:ocr-text desc)))
      (is (= "cat" (:classes desc)))
      (is (= 2 (:faces desc))))))

(deftest describe-still-degrades-on-tool-failure
  (let [dir (temp-dir)
        f (io/file dir "img.png")]
    (spit f "x")
    (let [desc (describe/describe {:run-fn (fake-run {:exit 1 :out "" :err "boom"})} f)]
      (is (= "" (:ocr-text desc)))
      (is (= "" (:classes desc)))
      (is (= 0 (:faces desc))))))

(deftest describe-clip-samples-frames-and-merges
  (let [dir (temp-dir)
        f (io/file dir "clip.gif")
        calls (atom [])]
    (spit f "gifdata")
    (let [desc (describe/describe {:run-fn (clip-run calls) :frames 6} f)]
      (is (= "frame text" (:ocr-text desc)))
      (is (= "cat" (:classes desc)))
      (is (= 1 (:faces desc)))
      (is (some #(and (= ["auge" "--ocr" "--ndjson" "-q"] (:args %))
                      (= 6 (count (str/split-lines (:stdin %)))))
                @calls))
      (is (some #(= "ffprobe" (first (:args %))) @calls)))))

(deftest describe-clip-falls-back-to-still-analysis
  (let [dir (temp-dir)
        f (io/file dir "clip.mp4")
        calls (atom [])]
    (spit f "mp4data")
    (let [desc (describe/describe {:run-fn (no-frames-run calls) :frames 6} f)]
      (is (= "fallback text" (:ocr-text desc)))
      (is (= "" (:classes desc)))
      (is (= 0 (:faces desc)))
      (is (some #(= "ffprobe" (first (:args %))) @calls)))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'clj-apt-reaction-image.describe-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
