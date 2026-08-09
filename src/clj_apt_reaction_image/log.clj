(ns clj-apt-reaction-image.log
  "Shared logging helper: the pipeline logs through the config's
  :log-fn so output format (text vs json) is decided in one place.")

(defn log!
  "Apply log-fn to xs when the config carries one."
  [config & xs]
  (when-let [log-fn (:log-fn config)]
    (apply log-fn xs)))
