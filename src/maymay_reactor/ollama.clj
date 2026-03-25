(ns maymay-reactor.ollama
  (:require
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   (java.net URI)
   (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
   (java.time Duration)
   (java.util Base64)))

(defn default-host []
  (or (System/getenv "OLLAMA_HOST")
      "http://localhost:11434"))

(defn- trim-trailing-slash [s]
  (str/replace s #"/+$" ""))

(defn- image->base64 [path]
  (.encodeToString (Base64/getEncoder)
                   (java.nio.file.Files/readAllBytes (.toPath (io/file path)))))

(defn chat!
  [host model prompt {:keys [images format keep-alive temperature timeout-seconds]
                      :or {images []
                           keep-alive "5m"
                           temperature 0.2
                           timeout-seconds 180}}]
  (let [body (cond-> {:model model
                      :stream false
                      :keep_alive keep-alive
                      :options {:temperature temperature}
                      :messages [(cond-> {:role "user"
                                          :content prompt}
                                   (seq images) (assoc :images (mapv image->base64 images)))]}
               format (assoc :format format))
        client (-> (HttpClient/newBuilder)
                   (.connectTimeout (Duration/ofSeconds 15))
                   .build)
        request (-> (HttpRequest/newBuilder)
                    (.uri (URI/create (str (trim-trailing-slash host) "/api/chat")))
                    (.timeout (Duration/ofSeconds timeout-seconds))
                    (.header "Content-Type" "application/json")
                    (.POST (HttpRequest$BodyPublishers/ofString (json/write-str body)))
                    .build)
        response (.send client request (HttpResponse$BodyHandlers/ofString))
        status (.statusCode response)
        parsed (json/read-str (.body response) :key-fn keyword)]
    (when-not (<= 200 status 299)
      (throw (ex-info (str "Ollama request failed with status " status)
                      {:status status
                       :response parsed
                       :body (.body response)
                       :host host
                       :model model})))
    parsed))

(defn assistant-content [response]
  (some-> response :message :content str/trim))
