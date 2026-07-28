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
     (doseq [ext ["jpg" "jpeg" "png" "gif" "webp" "heic" "heif" "bmp" "tif" "tiff"]]
       (let [f (write-file! dir (str "test." ext) "data")]
         (is (#'core/image-file? f) (str "should accept ." ext)))))))

(deftest image-file-rejects-unsupported-extensions
  (with-temp-dir
   (fn [dir]
     (doseq [ext ["txt" "md" "pdf" "mp4" "mov" "mp3" "" "svg"]]
       (let [f (write-file! dir (str "test." ext) "data")]
         (is (not (#'core/image-file? f)) (str "should reject ." ext)))))))

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

;; ─── entry point ──────────────────────────────────────────────────────────

(deftest usage-is-printed-on-help
  (let [output (with-out-str
                 (println (#'core/usage)))]
    (is (str/includes? output "organize"))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'clj-apt-reaction-image.core-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
