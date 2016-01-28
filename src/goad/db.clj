;; rewrite this to not use thingies and just use plain old monger

(ns goad.db
  (:require [monger.core :as mg]
            [monger.collection :as mc])
  (:import org.bson.types.ObjectId))

;;; DATABASE

(defn start-db! [mongo-uri]
  (let [{:keys [conn db]} (mg/connect-via-uri mongo-uri)]
    db
    ))

(defn save-goal! [r db]
  (prn (str "Adding: " r))
  (mc/insert db "goals" r))

(defn save-event! [r db]
  (prn (str "Adding: " r))
  (mc/insert db "events" r))

(defn delete-event! [id db]
  (prn (str "Deleting: " id))
  (mc/remove-by-id db "events" (ObjectId. id)))

(defn update-goal! [r db]
  (prn (str "Updating: " r))
  (mc/update db "goals" {:goal-id (:goal-id r)} r))

(defn load-goals [db user-id]
  (->>
    (mc/find-maps db "goals" {:user-id user-id})
    (remove :hidden)))

(defn load-goal [db user-id goal-id] (first (mc/find-maps db "goals" {:user-id user-id :goal-id goal-id})))
(defn load-events [db user-id] (mc/find-maps db "events" {:user-id user-id}))
