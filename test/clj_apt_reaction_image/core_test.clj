(ns clj-apt-reaction-image.core-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is run-tests]]
   [clj-apt-reaction-image.core :as core]))

;; ─── helpers ──────────────────────────────────────────────────────────────

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "clj-apt-reaction-image-test"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- write-file! [dir name contents]
  (let [f (io/file dir name)]
    (spit f contents)
    f))

(defn- with-temp-dir [f]
  (let [dir (temp-dir)]
    (try
      (f dir)
      (finally
        (doseq [file (.listFiles dir)]
          (io/delete-file file))))))

;; ─── extension / image-file ────────────────────────────────────────────────

(deftest extension-of-extracts-lowercase-extension
  (is (= "jpg" (#'core/extension-of (io/file "cat.jpg"))))
  (is (= "png" (#'core/extension-of (io/file "screenshot.PNG"))))
  (is (= "gif" (#'core/extension-of (io/file "reaction.GIF"))))
  (is (nil? (#'core/extension-of (io/file "noextension")))))

(deftest image-file-detects-supported-extensions
  (with-temp-dir
   (fn [dir]
     (doseq [ext ["jpg" "jpeg" "png" "gif" "webp" "heic" "heif" "bmp" "tif" "tiff"
                   "mp4" "mov" "m4v" "webm" "mkv"]]
       (let [f (write-file! dir (str "test." ext) "data")]
         (is (#'core/media-file? f) (str "should accept ." ext)))))))

(deftest image-file-rejects-unsupported-extensions
  (with-temp-dir
   (fn [dir]
     (doseq [ext ["txt" "md" "pdf" "mp3" "" "svg"]]
       (let [f (write-file! dir (str "test." ext) "data")]
         (is (not (#'core/media-file? f)) (str "should reject ." ext)))))))

;; ─── collect-images ────────────────────────────────────────────────────────

(deftest collect-images-finds-only-images
  (with-temp-dir
   (fn [dir]
     (write-file! dir "a.jpg" "data")
     (write-file! dir "b.png" "data")
     (write-file! dir "c.txt" "data")
     (write-file! dir "d.gif" "data")
     (write-file! dir "e.md" "data")
     (let [images (#'core/collect-images dir)]
       (is (= 3 (count images)))
       (is (some #(.endsWith (.getName %) ".jpg") images))))))

(deftest collect-images-sorts-by-path
  (with-temp-dir
   (fn [dir]
     (write-file! dir "z.jpg" "data")
     (write-file! dir "a.jpg" "data")
     (write-file! dir "m.jpg" "data")
     (let [images (#'core/collect-images dir)
           names (mapv #(.getName ^java.io.File %) images)]
       (is (= ["a.jpg" "m.jpg" "z.jpg"] names))))))

;; ─── sanitize-filename ─────────────────────────────────────────────────────

(deftest sanitize-filename-removes-spaces
  (is (not (str/includes? (#'core/sanitize-filename "hello world test" 30) " ")))
  (is (= "hello-world-test" (#'core/sanitize-filename "hello world test" 30))))

(deftest sanitize-filename-lowercases
  (is (= "upper-case-name" (#'core/sanitize-filename "Upper Case Name" 30))))

(deftest sanitize-filename-removes-special-chars
  (is (= "file-name-123" (#'core/sanitize-filename "file_name!@#$%^&*()_+123" 30))))

(deftest sanitize-filename-collapses-hyphens
  (is (= "multiple-hyphens" (#'core/sanitize-filename "multiple---hyphens" 30))))

(deftest sanitize-filename-strips-leading-trailing-hyphens
  (is (= "mid" (#'core/sanitize-filename "-mid-" 30))))

(deftest sanitize-filename-respects-max-length
  (is (= 30 (count (#'core/sanitize-filename (apply str (repeat 50 "a")) 30))))
  (is (= (apply str (repeat 30 "a")) (#'core/sanitize-filename (apply str (repeat 50 "a")) 30))))

(deftest sanitize-filename-handles-nil
  (is (= "" (#'core/sanitize-filename nil 30))))

(deftest sanitize-filename-never-produces-dot
  (is (not (str/includes? (#'core/sanitize-filename "file.name.with.dots" 30) "."))))

;; ─── unique-target ─────────────────────────────────────────────────────────

(deftest unique-target-returns-name-when-free
  (with-temp-dir
   (fn [dir]
     (let [source (write-file! dir "original.jpg" "data")
           target (#'core/unique-target source "cat-meme" "jpg")]
       (is (= "cat-meme.jpg" (.getName target)))))))

(deftest unique-target-keeps-own-name
  (with-temp-dir
   (fn [dir]
     (let [source (write-file! dir "cat-meme.jpg" "data")
           target (#'core/unique-target source "cat-meme" "jpg")]
       (is (= "cat-meme.jpg" (.getName target)))))))

(deftest unique-target-appends-suffix-when-taken
  (with-temp-dir
   (fn [dir]
     (write-file! dir "cat-meme.jpg" "data")
     (let [source (write-file! dir "original.jpg" "data")
           target (#'core/unique-target source "cat-meme" "jpg")]
       (is (= "cat-meme_1.jpg" (.getName target)))))))

(deftest unique-target-increments-past-existing-suffixes
  (with-temp-dir
   (fn [dir]
     (write-file! dir "cat-meme.jpg" "data")
     (write-file! dir "cat-meme_1.jpg" "data")
     (write-file! dir "cat-meme_2.jpg" "data")
     (let [source (write-file! dir "original.jpg" "data")
           target (#'core/unique-target source "cat-meme" "jpg")]
       (is (= "cat-meme_3.jpg" (.getName target)))))))

;; ─── parse-args ────────────────────────────────────────────────────────────

(deftest parse-args-extracts-flags
  (let [result (#'core/parse-args ["--images-dir" "/path" "--dry-run"])]
    (is (= "/path" (:images-dir result)))
    (is (true? (:dry-run result)))))

(deftest parse-args-collects-positionals
  (let [result (#'core/parse-args ["--images-dir" "/path" "extra"])]
    (is (= "/path" (:images-dir result)))
    (is (= ["extra"] (:positionals result)))))

(deftest parse-args-throws-on-missing-value
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"Missing value"
       (#'core/parse-args ["--images-dir"]))))

;; ─── command-exists? ───────────────────────────────────────────────────────

(deftest command-exists-detects-present-command
  (is (true? (#'core/command-exists? "sh")))
  (is (true? (#'core/command-exists? "ls"))))

(deftest command-exists-rejects-missing-command
  (is (false? (#'core/command-exists? "this-command-definitely-does-not-exist-12345"))))

;; ─── normalize-text ────────────────────────────────────────────────────────

(deftest normalize-text-collapses-whitespace
  (is (= "hello world" (#'core/normalize-text " hello   world ")))
  (is (= "" (#'core/normalize-text nil)))
  (is (= "" (#'core/normalize-text ""))))

;; ─── clip-file? ───────────────────────────────────────────────────────────

(deftest clip-file-detects-clips
  (doseq [ext ["gif" "mp4" "mov" "m4v" "webm" "mkv"]]
    (is (#'core/clip-file? (io/file (str "test." ext)))
        (str "should accept ." ext))))

(deftest clip-file-rejects-stills
  (doseq [ext ["png" "jpg" "txt"]]
    (is (not (#'core/clip-file? (io/file (str "test." ext))))
        (str "should reject ." ext))))

;; ─── sample-fractions ─────────────────────────────────────────────────────

(deftest sample-fractions-n6
  (let [fractions (#'core/sample-fractions 6)]
    (is (= 6 (count fractions)))
    (is (< 0 (first fractions) 1))
    (is (< 0 (last fractions) 1))
    (is (= (sort fractions) fractions) "ascending")
    (is (< (Math/abs (- (first fractions) (/ 1 12))) 1e-9))
    (is (< (Math/abs (- (last fractions) (/ 11 12))) 1e-9))))

(deftest sample-fractions-n1
  (let [fractions (#'core/sample-fractions 1)]
    (is (= 1 (count fractions)))
    (is (< (Math/abs (- (first fractions) 0.5)) 1e-9))))

;; ─── merge-ocr-texts ──────────────────────────────────────────────────────

(deftest merge-ocr-texts-dedupes-order-preserving
  (is (= "lol boom" (#'core/merge-ocr-texts ["lol" "lol" "boom"])))
  (is (= "hi" (#'core/merge-ocr-texts ["" "hi" nil])))
  (is (= "Hey" (#'core/merge-ocr-texts ["Hey" "hey"])))
  (is (= "" (#'core/merge-ocr-texts []))))

;; ─── merge-classifications ────────────────────────────────────────────────

(deftest merge-classifications-groups-by-label
  (let [result (#'core/merge-classifications
                [[{:label "cat" :confidence 0.9}]
                 [{:label "cat" :confidence 0.5} {:label "dog" :confidence 0.7}]])]
    (is (= "cat, dog" result)))
  (is (= "" (#'core/merge-classifications [])))
  (is (= "" (#'core/merge-classifications [[] []]))))

(deftest merge-classifications-caps-at-five
  (let [many (vec (for [i (range 10)] [{:label (str "l" i) :confidence 0.5}]))
        result (#'core/merge-classifications many)]
    (is (= 5 (count (str/split result #", "))))))

(deftest merge-classifications-handles-nil-entries
  (is (= "cat" (#'core/merge-classifications [nil [{:label "cat" :confidence 0.9}]])))
  (is (= "" (#'core/merge-classifications [nil nil]))))

;; ─── merge-face-counts ────────────────────────────────────────────────────

(deftest merge-face-counts-takes-max
  (is (= 2 (#'core/merge-face-counts [0 2 1])))
  (is (= 0 (#'core/merge-face-counts [])))
  (is (= 5 (#'core/merge-face-counts [1 5 3]))))

;; ─── frames config ────────────────────────────────────────────────────────

(deftest parse-args-extracts-frames
  (let [result (#'core/parse-args ["--frames" "8"])]
    (is (= "8" (:frames result)))))

(deftest default-config-defaults-frames-to-6
  (is (= 6 (:frames (#'core/default-config {})))))

(deftest default-config-parses-frames
  (is (= 3 (:frames (#'core/default-config {:frames "3"})))))

(deftest default-config-rejects-invalid-frames
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"Invalid --frames"
       (#'core/default-config {:frames "abc"})))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"Invalid --frames"
       (#'core/default-config {:frames "0"}))))

;; ─── entry point ──────────────────────────────────────────────────────────

(deftest usage-is-printed-on-help
  (let [output (#'core/usage)]
    (is (str/includes? output "organize"))
    (is (str/includes? output "--frames"))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'clj-apt-reaction-image.core-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
