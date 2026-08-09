(ns clj-apt-reaction-image.tools-test
  (:require
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is run-tests]]
   [clj-apt-reaction-image.tools :as tools]))

;; ─── helpers ──────────────────────────────────────────────────────────

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "clj-apt-reaction-image-tools-test"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- fake-run [result-map]
  (fn [_args _stdin] result-map))

(defn- capturing-run [calls]
  (fn [args stdin]
    (swap! calls conj {:args args :stdin stdin})
    {:exit 0 :out "" :err ""}))

;; ─── auge ─────────────────────────────────────────────────────────────

(deftest auge-json-parses-output
  (let [config {:run-fn (fake-run {:exit 0 :out (json/write-str {:results {:text "hello"}})
                                   :err ""})}]
    (is (= {:results {:text "hello"}}
           (tools/auge-json! config "--ocr" "/img.png")))))

(deftest auge-json-degrades-on-tool-failure
  (is (nil? (tools/auge-json! {:run-fn (fake-run {:exit 1 :out "" :err "boom"})}
                              "--ocr" "/img.png"))))

(deftest auge-json-degrades-on-unparseable-output
  (is (nil? (tools/auge-json! {:run-fn (fake-run {:exit 0 :out "not json" :err ""})}
                              "--ocr" "/img.png"))))

(deftest auge-json-passes-expected-flags
  (let [calls (atom [])
        config {:run-fn (capturing-run calls)}]
    (tools/auge-json! config "--faces" "/img.png")
    (is (= ["auge" "--faces" "/img.png" "--json" "-q"]
           (:args (first @calls))))
    (is (nil? (:stdin (first @calls))))))

(deftest auge-ndjson-parses-lines
  (let [paths ["/f1.png" "/f2.png"]
        out (str/join "\n" [(json/write-str {:file "/f1.png" :results {:text "a"}})
                            (json/write-str {:file "/f2.png" :results {:text "b"}})])
        config {:run-fn (fake-run {:exit 0 :out out :err ""})}
        result (tools/auge-ndjson! config "--ocr" paths)]
    (is (= "a" (get-in result ["/f1.png" :results :text])))
    (is (= "b" (get-in result ["/f2.png" :results :text])))))

(deftest auge-ndjson-degrades-to-empty
  (is (= {} (tools/auge-ndjson! {:run-fn (fake-run {:exit 1 :out "" :err "x"})}
                                "--ocr" ["/a.png"])))
  (is (= {} (tools/auge-ndjson! {:run-fn (fake-run {:exit 0 :out "garbage\n" :err ""})}
                                "--ocr" ["/a.png"]))))

(deftest auge-ndjson-feeds-paths-on-stdin
  (let [calls (atom [])
        config {:run-fn (capturing-run calls)}]
    (tools/auge-ndjson! config "--classify" ["/a.png" "/b.png"])
    (let [{:keys [args stdin]} (first @calls)]
      (is (= ["auge" "--classify" "--ndjson" "-q"] args))
      (is (= "/a.png\n/b.png" stdin)))))

;; ─── apfel-tag ────────────────────────────────────────────────────────

(deftest apfel-tag-parses-tags
  (let [config {:run-fn (fake-run {:exit 0 :out (json/write-str {:tags ["funny"] :topics ["meme"]})
                                   :err ""})}]
    (is (= {:tags ["funny"] :topics ["meme"]}
           (tools/apfel-tag! config "some text")))))

(deftest apfel-tag-degrades
  (is (nil? (tools/apfel-tag! {:run-fn (fake-run {:exit 1 :out "" :err "x"})} "t"))))

(deftest apfel-tag-passes-flags-and-stdin
  (let [calls (atom [])
        config {:run-fn (capturing-run calls)}]
    (tools/apfel-tag! config "input text")
    (let [{:keys [args stdin]} (first @calls)]
      (is (= ["apfel-tag" "--permissive" "-o" "json" "-q"] args))
      (is (= "input text" stdin)))))

;; ─── apfel ────────────────────────────────────────────────────────────

(deftest apfel-name-parses-name
  (let [config {:run-fn (fake-run {:exit 0 :out (json/write-str {:name "cat-meme"})
                                   :err ""})}]
    (is (= {:name "cat-meme"} (tools/apfel-name! config "prompt" "system")))))

(deftest apfel-name-degrades
  (is (nil? (tools/apfel-name! {:run-fn (fake-run {:exit 1 :out "" :err "x"})}
                               "p" "s"))))

(deftest apfel-name-passes-flags-prompt-and-schema
  (let [calls (atom [])
        config {:run-fn (capturing-run calls)}]
    (tools/apfel-name! config "the prompt" "the system")
    (let [{:keys [args]} (first @calls)]
      (is (= ["apfel" "-q" "--permissive" "--schema"] (subvec args 0 4)))
      (is (str/ends-with? (nth args 4) "filename-schema.json"))
      (is (= ["-s" "the system" "the prompt"] (subvec args 5))))))

;; ─── ffprobe / ffmpeg ─────────────────────────────────────────────────

(deftest probe-duration-parses
  (is (= 12.5 (tools/probe-duration! {:run-fn (fake-run {:exit 0 :out "12.5" :err ""})}
                                     "/v.mp4"))))

(deftest probe-duration-degrades
  (is (nil? (tools/probe-duration! {:run-fn (fake-run {:exit 1 :out "" :err ""})}
                                   "/v.mp4")))
  (is (nil? (tools/probe-duration! {:run-fn (fake-run {:exit 0 :out "abc" :err ""})}
                                   "/v.mp4")))
  (is (nil? (tools/probe-duration! {:run-fn (fake-run {:exit 0 :out "0" :err ""})}
                                   "/v.mp4"))))

(deftest extract-frame-returns-success
  (is (true? (tools/extract-frame! {:run-fn (fake-run {:exit 0 :out "" :err ""})}
                                   "/v.mp4" 1.5 (io/file "/tmp/f.png"))))
  (is (false? (tools/extract-frame! {:run-fn (fake-run {:exit 1 :out "" :err "x"})}
                                    "/v.mp4" 1.5 (io/file "/tmp/f.png")))))

(deftest extract-frame-passes-ffmpeg-flags
  (let [calls (atom [])
        config {:run-fn (capturing-run calls)}]
    (tools/extract-frame! config "/v.mp4" 1.5 (io/file "/tmp/f.png"))
    (is (= ["ffmpeg" "-v" "error" "-ss" "1.500" "-i" "/v.mp4"
            "-frames:v" "1" "-y" "/tmp/f.png"]
           (:args (first @calls))))))

;; ─── tags binary ──────────────────────────────────────────────────────

(deftest read-tags-parses-comma-list
  (is (= ["funny" "meme"] (tools/read-tags! {:run-fn (fake-run {:exit 0 :out "funny,meme" :err ""})}
                                            "/f.png")))
  (is (= [] (tools/read-tags! {:run-fn (fake-run {:exit 0 :out "" :err ""})}
                              "/f.png"))))

(deftest read-tags-degrades
  (is (= [] (tools/read-tags! {:run-fn (fake-run {:exit 1 :out "" :err "x"})}
                              "/f.png"))))

(deftest read-tags-invokes-tags-binary
  (let [calls (atom [])
        config {:run-fn (capturing-run calls)}]
    (tools/read-tags! config "/abs/f.png")
    (let [{:keys [args]} (first @calls)]
      (is (= 2 (count args)))
      (is (str/ends-with? (first args) "/tags"))
      (is (= "/abs/f.png" (second args))))))

(deftest write-tags-invokes-tags-binary
  (let [calls (atom [])
        dir (temp-dir)
        f (io/file dir "a.png")]
    (spit f "x")
    (tools/write-tags! {:run-fn (capturing-run calls)} (.getAbsolutePath f) "funny,meme")
    (let [{:keys [args]} (first @calls)]
      (is (= 3 (count args)))
      (is (str/ends-with? (first args) "/tags"))
      (is (= "funny,meme" (nth args 2))))))

(deftest write-tags-throws-on-failure
  (let [dir (temp-dir)
        f (io/file dir "a.png")]
    (spit f "x")
    (is (thrown? clojure.lang.ExceptionInfo
                 (tools/write-tags! {:run-fn (fake-run {:exit 1 :out "" :err "boom"})}
                                    (.getAbsolutePath f) "funny")))))

(deftest write-tags-skips-empty-tags
  (let [calls (atom [])
        dir (temp-dir)
        f (io/file dir "a.png")]
    (spit f "x")
    (tools/write-tags! {:run-fn (capturing-run calls)} (.getAbsolutePath f) "")
    (is (empty? @calls))))

;; ─── tool presence ────────────────────────────────────────────────────

(deftest tool-present-probes-path
  (is (true? (tools/tool-present? {:run-fn (fake-run {:exit 0 :out "" :err ""})} "auge")))
  (is (false? (tools/tool-present? {:run-fn (fake-run {:exit 1 :out "" :err ""})} "auge"))))

(deftest tool-present-asks-which
  (let [calls (atom [])
        config {:run-fn (capturing-run calls)}]
    (tools/tool-present? config "ffmpeg")
    (is (= ["which" "ffmpeg"] (:args (first @calls))))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'clj-apt-reaction-image.tools-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
