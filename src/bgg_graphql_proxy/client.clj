(ns bgg-graphql-proxy.client
  "Client code for accessing BoardGameGeek, and converting XML responses into Clojure data."
  (:require
    [clj-http.client :as client]
    [clojure.data.xml :as xml]
    [clojure.string :as str]
    [clojure.tools.logging :as log])
  (:import (java.io StringReader)))

(def ^:private base-url "https://www.boardgamegeek.com/xmlapi")

(defn ^:private expect-tag [tag element]
  (when-not (= tag (:tag element))
    (throw (ex-info (format "Wrong tag.  Expected `%s', not `%s'."
                            (name tag)
                            (-> element :tag name))
                    {:expected-tag tag
                     :actual-tag (:tag element)
                     :element element})))
  element)

(defn ^:private parse-int [s] (Integer/parseInt s))

(defn ^:private prefix-with-https [s] (str "https:" s))

(def ^:private boardgame-content-renames
  {:yearpublished [:publish-year parse-int]
   :minplayers [:min-players parse-int]
   :maxplayers [:max-players parse-int]
   :playingtime [:playing-time parse-int]
   :age [:min-player-age parse-int]
   :description [:description str]
   :thumbnail [:thumbnail prefix-with-https]
   :image [:image prefix-with-https]})

(defmulti process-bg-content
  (fn [_bg element]
    (:tag element)))

(defmethod process-bg-content :default
  [bg element]
  (let [tag (:tag element)
        [output-key parser] (get boardgame-content-renames tag)]
    (if output-key
      (assoc bg output-key (-> element :content first parser))
      bg)))

(defmethod process-bg-content :name
  [bg element]
  ;; non-primary names are usually translations to other languages
  (if (-> element :attrs :primary)
    ;; TODO: Trim/reformat loose HTML
    (assoc bg :name (-> element :content first))
    bg))

(defmethod process-bg-content :boardgamepublisher
  [bg element]
  (update bg :publisher-ids conj (-> element :attrs :objectid)))

(defmethod process-bg-content :boardgamedesigner
  [bg element]
  (update bg :designer-ids conj (-> element :attrs :objectid)))

(defn ^:private xml->board-game
  [element]
  (reduce process-bg-content
          {:id (-> element :attrs :objectid)
           :publisher-ids []}
          (:content element)))

(defn ^:private get-xml
  [url query-params]
  (log/info (str "BGG Query: " url (when query-params
                                     (str " " (pr-str query-params)))))
  (->> (client/get url
                   {:accept "text/xml"
                    :query-params query-params
                    :throw-exceptions false})
       :body
       StringReader.
       xml/parse))

(defn get-board-game
  [id]
  (->> (get-xml (str base-url "/boardgame/" id) nil)
       (expect-tag :boardgames)
       :content
       first
       xml->board-game))

(defn search
  "Performs a search of matching games by name."
  [text]
  (let [game-ids (->> (get-xml (str base-url "/search") {:search text})
                      (expect-tag :boardgames)
                      :content
                      (map (comp :objectid :attrs))
                      (str/join ","))]
    (->> (get-xml (str base-url "/boardgame/" game-ids) nil)
         (expect-tag :boardgames)
         :content
         (map xml->board-game))))

(defn ^:private xml->map
  [element keys]
  (-> (into {}
            (map #(vector (:tag %)
                          ;; Assumes :content is a single element, a string containing the value.
                          (-> % :content first)))
            (:content element))
      (select-keys keys)))

(defn ^:private xml->company
  [id company-element]
  (expect-tag :company company-element)
  (-> company-element
      (xml->map [:name :description])
      (assoc :id (str id))))

(defn ^:private xml->person
  [id person-element]
  (expect-tag :person person-element)
  (-> person-element
      (xml->map [:name :description])
      (assoc :id (str id))))

(defn publishers
  [ids]
  (prn `publishers ids)
  (->> (get-xml (str base-url "/boardgamepublisher/" (str/join "," ids)) nil)
       (expect-tag :companies)
       :content
       ;; BGG doesn't return the company id in the XML, so we have to
       ;; hope it all lines up. Demoware.
       (map xml->company ids)))

(defn designers
  [ids]
  (->> (get-xml (str base-url "/boardgamedesigner/" (str/join "," ids)) nil)
       (expect-tag :people)
       :content
       (map xml->person ids)))
