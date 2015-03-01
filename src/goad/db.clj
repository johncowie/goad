(ns goad.db
  (:require [thingies.db.document :as db]))

;;; DATABASE

(defn start-db! [mongo-uri]
  (.start (db/new-mongo-db mongo-uri)))

(defn save-goal! [r db]
  (prn (str "Adding: " r))
  (db/save! db "goals" r))
(defn save-event! [r db]
  (prn (str "Adding: " r))
  (db/save! db "events" r))
(defn update-goal! [r db]
  (prn (str "Updating: " r))
  (db/update! db "goals" {:goal-id (:goal-id r)} r))
(defn load-goals [db user-id]
  (->>
    (db/query db "goals" {:user-id user-id})
    (remove :hidden)))
(defn load-goal [db user-id goal-id] (first (db/query db "goals" {:user-id user-id :goal-id goal-id})))
(defn load-events [db user-id] (db/query db "events" {:user-id user-id}))
