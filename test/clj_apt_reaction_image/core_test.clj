(ns clj-apt-reaction-image.core-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is run-tests]]
   [clj-apt-reaction-image.core :as core]))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory "clj-apt-reaction-image-test" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- write-file! [dir name contents]
  (let [f (io/file dir name)]
    (spit f contents)
    f))

(deftest semantic-search-text-is-indexed
  (let [entry {:caption "woman staring at phone in disbelief"
               :reaction-tags ["disbelief" "speechless"]
               :scene-tags ["phone" "indoors"]
               :people ["woman"]
               :emotions ["shocked"]
               :notes "good for unbelievable messages"
               :visible-text ""
               :ocr-text ""}
        search-text (#'core/build-search-text entry)]
    (is (str/includes? search-text "disbelief"))
    (is (str/includes? search-text "speechless"))
    (is (str/includes? search-text "unbelievable"))))

(deftest unchanged-files-are-reused
  (let [dir (temp-dir)
        index-file (.getAbsolutePath (io/file dir "index.edn"))
        _ (write-file! dir "a.jpg" "one")
        _ (write-file! dir "b.png" "two")
        config {:images-dir (.getAbsolutePath dir)
                :index-file index-file
                :vision-model "qwen2.5vl:3b"
                :rank-model "qwen2.5vl:3b"
                :host "http://localhost:11434"}
        semantic-calls (atom [])
        ocr-calls (atom [])]
    (with-redefs-fn
      {#'core/ensure-prerequisites! (fn [] true)
       #'core/ocr-image! (fn [path]
                           (swap! ocr-calls conj (.getName (io/file path)))
                           "")
       #'core/describe-image! (fn [cfg file ocr-text]
                                (swap! semantic-calls conj (.getName file))
                                {:id (.getName file)
                                 :caption (str "caption " (.getName file))
                                 :reaction-tags ["test"]
                                 :scene-tags ["scene"]
                                 :visible-text ocr-text
                                 :people []
                                 :emotions ["neutral"]
                                 :notes "note"
                                 :semantic-model (:vision-model cfg)})}
      (fn []
        (#'core/build-index! config)
        (is (= 2 (count @semantic-calls)))
        (reset! semantic-calls [])
        (reset! ocr-calls [])
        (#'core/build-index! config)
        (is (empty? @semantic-calls))
        (is (empty? @ocr-calls))))))

(deftest changed-files-are-reprocessed
  (let [dir (temp-dir)
        index-file (.getAbsolutePath (io/file dir "index.edn"))
        a (write-file! dir "a.jpg" "one")
        b (write-file! dir "b.png" "two")
        config {:images-dir (.getAbsolutePath dir)
                :index-file index-file
                :vision-model "qwen2.5vl:3b"
                :rank-model "qwen2.5vl:3b"
                :host "http://localhost:11434"}
        semantic-calls (atom [])]
    (with-redefs-fn
      {#'core/ensure-prerequisites! (fn [] true)
       #'core/ocr-image! (fn [_] "")
       #'core/describe-image! (fn [cfg file _]
                                (swap! semantic-calls conj (.getName file))
                                {:id (.getName file)
                                 :caption (str "caption " (.getName file))
                                 :reaction-tags ["test"]
                                 :scene-tags ["scene"]
                                 :visible-text ""
                                 :people []
                                 :emotions ["neutral"]
                                 :notes "note"
                                 :semantic-model (:vision-model cfg)})}
      (fn []
        (#'core/build-index! config)
        (reset! semantic-calls [])
        (Thread/sleep 5)
        (spit b "two updated")
        (#'core/build-index! config)
        (is (= ["b.png"] @semantic-calls))
        (is (.exists a))))))

(deftest lexical-shortlist-uses-semantic-fields
  (let [entry-a {:id "a.jpg"
                 :caption "woman staring at phone in disbelief"
                 :reaction-tags ["disbelief" "speechless"]
                 :scene-tags ["phone"]
                 :people []
                 :emotions ["shocked"]
                 :notes "good for unexpected messages"
                 :visible-text ""
                 :ocr-text ""
                 :search-text (#'core/build-search-text {:caption "woman staring at phone in disbelief"
                                                         :reaction-tags ["disbelief" "speechless"]
                                                         :scene-tags ["phone"]
                                                         :people []
                                                         :emotions ["shocked"]
                                                         :notes "good for unexpected messages"
                                                         :visible-text ""
                                                         :ocr-text ""})}
        entry-b {:id "b.jpg"
                 :caption "happy thumbs up"
                 :reaction-tags ["approval"]
                 :scene-tags ["person"]
                 :people []
                 :emotions ["happy"]
                 :notes "good for saying yes"
                 :visible-text ""
                 :ocr-text ""
                 :search-text (#'core/build-search-text {:caption "happy thumbs up"
                                                         :reaction-tags ["approval"]
                                                         :scene-tags ["person"]
                                                         :people []
                                                         :emotions ["happy"]
                                                         :notes "good for saying yes"
                                                         :visible-text ""
                                                         :ocr-text ""})}
        entries (mapv #(assoc % :token-freq (#'core/token-frequencies (:search-text %))) [entry-a entry-b])
        ranked (#'core/lexical-shortlist entries "bro what disbelief" 5)]
    (is (= "a.jpg" (:id (first ranked))))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'clj-apt-reaction-image.core-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
