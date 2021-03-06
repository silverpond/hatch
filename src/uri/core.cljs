(ns uri.core
  (:import goog.Uri))

(defn add-query [url data]
  (let [uri (.parse goog.Uri url)]
    (doseq [[k v] data]
      (.setParameterValue uri (name k) (str v)))
    (.toString uri)))

(defn relative [url]
  (let [uri (.parse goog.Uri url)
        loc (.parse goog.Uri js/window.location)]
    (.toString
     (if (and (.hasSameDomainAs uri loc)
              (= (.getScheme uri)
                 (.getScheme loc)))
       (doto uri
         (.setDomain "")
         (.setScheme ""))
       uri
       ))))

(defn fragment [url]
  (let [uri (.parse goog.Uri url)]
   (when (.hasFragment uri)
     (.getFragment uri))))

(defn base [url]
  (let [uri (.parse goog.Uri url)]
    (.setFragment uri "")
    (.setQuery uri "")
    (.toString uri)))

(defn split-fragment [url]
  (let [uri  (.parse goog.Uri url)
        frag (when (.hasFragment uri) (.getFragment uri))
        base (do (.setFragment uri "") (.toString uri))]
    [base frag]))
