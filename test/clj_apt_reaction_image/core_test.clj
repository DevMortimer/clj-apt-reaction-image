(ns clj-apt-reaction-image.core-test
  (:require
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is run-tests]]
   [clj-apt-reaction-image.core :as core]))

;; ─── helpers ──────────────────────────────────────────────────────────

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

(defn- pipeline-run
  "Fake runner for the whole organise pipeline: no real tools needed.
  every external call is answered with canned output."
  [calls]
  (fn [args stdin]
    (swap! calls conj {:args args :stdin stdin})
    (cond
      (= "which" (first args)) {:exit 0 :out "/usr/bin/fake" :err ""}
      (= "swiftc" (first args)) {:exit 0 :out "" :err ""}
      (str/ends-with? (first args) "/tags") {:exit 0 :out "" :err ""}
      (= "auge" (first args))
      (case (second args)
        "--ocr" {:exit 0 :out (json/write-str {:results {:text "lol"}}) :err ""}
        "--classify" {:exit 0 :out (json/write-str
                                     {:results {:classifications [{:label "cat" :confidence 0.9}]}})
                       :err ""}
        "--faces" {:exit 0 :out (json/write-str {:results {:count 1}}) :err ""})
      (= "apfel-tag" (first args)) {:exit 0 :out (json/write-str {:tags ["funny"]}) :err ""}
      (= "apfel" (first args)) {:exit 0 :out (json/write-str {:name "cat-meme"}) :err ""}
      :else {:exit 0 :out "" :err ""})))

;; ─── collect-images ───────────────────────────────────────────────────

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

;; ─── sanitize-filename ────────────────────────────────────────────────

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

;; ─── unique-target ────────────────────────────────────────────────────

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

;; ─── parse-args ───────────────────────────────────────────────────────

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

(deftest parse-args-extracts-all-flag
  (is (true? (:all (#'core/parse-args ["--all"]))))
  (is (true? (:all (#'core/parse-args ["-a"]))))
  (is (nil? (:all (#'core/parse-args ["--images-dir" "/path"])))))

(deftest default-config-all-defaults-to-false
  (is (false? (:all (#'core/default-config {}))))
  (is (true? (:all (#'core/default-config {:all true})))))

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

;; ─── organise end-to-end at the tools seam ────────────────────────────

(deftest organize-images-runs-full-pipeline-via-fake-runner
  (with-temp-dir
   (fn [dir]
     (write-file! dir "a.png" "data")
     (write-file! dir "b.jpg" "data")
     (let [calls (atom [])
           config (assoc (#'core/default-config {:images-dir (str dir)})
                         :run-fn (pipeline-run calls))
           result (core/organize-images! config)]
       (is (= 2 (:total result)))
       (is (= 0 (:skipped result)))
       (is (= ["cat-meme.png" "cat-meme.jpg"] (mapv :new-name (:results result))))
       (is (= "funny" (:tags (first (:results result)))))
       (is (= "cat" (:classes (second (:results result)))))
       (is (= 1 (:faces (second (:results result)))))
       (is (some #(and (>= (count (:args %)) 3)
                       (= ["auge" "--ocr"] (subvec (:args %) 0 2))
                       (str/ends-with? (nth (:args %) 2) "a.png"))
                 @calls))
       (is (some #(and (>= (count (:args %)) 4)
                       (= ["apfel" "-q" "--permissive" "--schema"]
                          (subvec (:args %) 0 4)))
                 @calls))))))

(deftest organize-images-skips-already-tagged
  (with-temp-dir
   (fn [dir]
     (write-file! dir "a.png" "data")
     (let [calls (atom [])
           run (fn [args stdin]
                 (swap! calls conj {:args args :stdin stdin})
                 (cond
                   (= "which" (first args)) {:exit 0 :out "" :err ""}
                   (= "swiftc" (first args)) {:exit 0 :out "" :err ""}
                   (str/ends-with? (first args) "/tags") {:exit 0 :out "already-funny" :err ""}
                   :else {:exit 0 :out "" :err ""}))
           config (assoc (#'core/default-config {:images-dir (str dir)})
                         :run-fn run)
           result (core/organize-images! config)]
       (is (= 0 (:total result)))
       (is (= 1 (:skipped result)))
       (is (empty? (:results result)))))))

(deftest organize-images-uses-fallback-name-when-apfel-fails
  (with-temp-dir
   (fn [dir]
     (write-file! dir "a.png" "data")
     (let [run (fn [args _stdin]
                 (cond
                   (= "which" (first args)) {:exit 0 :out "" :err ""}
                   (= "swiftc" (first args)) {:exit 0 :out "" :err ""}
                   (str/ends-with? (first args) "/tags") {:exit 0 :out "" :err ""}
                   (= "auge" (first args))
                   (case (second args)
                     "--ocr" {:exit 0 :out (json/write-str {:results {:text "lol"}}) :err ""}
                     "--classify" {:exit 0 :out (json/write-str
                                                 {:results {:classifications [{:label "cat" :confidence 0.9}]}})
                                   :err ""}
                     "--faces" {:exit 0 :out (json/write-str {:results {:count 1}}) :err ""})
                   (= "apfel-tag" (first args)) {:exit 0 :out (json/write-str {:tags ["funny"]}) :err ""}
                   (= "apfel" (first args)) {:exit 1 :out "" :err "safety guardrails"}
                   :else {:exit 0 :out "" :err ""}))
           config (assoc (#'core/default-config {:images-dir (str dir)})
                         :run-fn run)
           result (core/organize-images! config)]
       (is (= "lol-1face-cat.png" (:new-name (first (:results result)))))))))

;; ─── entry point ──────────────────────────────────────────────────────

(deftest usage-is-printed-on-help
  (let [output (#'core/usage)]
    (is (str/includes? output "organize"))
    (is (str/includes? output "--frames"))))

(defn -main [& _]
  (require 'clj-apt-reaction-image.describe-test
           'clj-apt-reaction-image.tools-test)
  (let [{:keys [fail error]}
        (run-tests 'clj-apt-reaction-image.core-test
                   'clj-apt-reaction-image.describe-test
                   'clj-apt-reaction-image.tools-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
