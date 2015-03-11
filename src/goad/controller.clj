(ns goad.controller
  (:require [ring.util.response :as r]
            [goad.db :as db]
            [thingies.clock :as clock]
            [clojure.string :as s]
            [goad.routes :refer [path]]
            [goad.view :as v]
            [goad.stats :as st]
            [environ.core :refer [env]]))

;; HANDLERS

(defn generate-goal-id [r]
  (let [id (-> r :name s/lower-case (s/replace #"[^a-z]+" ""))]
    (assoc r :goal-id id)))


(defn html-response [content]
  (-> content
      r/response
      (assoc-in [:headers "Content-Type"] "text/html")))


(defn add-goal [db clock]
  (fn [request]
    (-> request
        :params
        (select-keys [:name :target :unit :time-unit])
        (update-in [:target] #(Integer. %))
        (update-in [:time-unit] #(Integer. %))
        (assoc :timestamp (clock/now clock))
        (assoc :user-id (get-in request [:session :user :id]))
        generate-goal-id
        (db/save-goal! db))
    (r/redirect (path :index))))

(defn edit-goal [db]
  (fn [request]
    (let [user-id (get-in request [:session :user :id])
          goal-id (get-in request [:params :goal])
          existing-goal (db/load-goal db user-id goal-id)]
      (-> request
          :params
          (select-keys [:name :target :unit :time-unit])
          (update-in [:target] #(Integer. %))
          (update-in [:time-unit] #(Integer. %))
          (merge (select-keys existing-goal [:timestamp :user-id :goal-id]))
          (db/update-goal! db)))
    (r/redirect (path :index))))

(defn hide-goal [db]
  (fn [request]
    (let [user-id (get-in request [:session :user :id])
          goal-id (get-in request [:params :goal])
          existing-goal (db/load-goal db user-id goal-id)]
      (-> existing-goal
          (assoc-in [:hidden] true)
          (db/update-goal! db)))
    (r/redirect (path :index))))

(defn edit-goal-form [db]
  (fn [request]
    (let [user (get-in request [:session :user])
          goal-id (get-in request [:params :goal])]
      (when-let [goal (db/load-goal db (:id user) goal-id)]
        (html-response (v/edit-goal-page user goal))))))

(defn add-event [db clock]
  (fn [request]
    (-> request
        :params
        (select-keys [:amount :goal-id :comments])
        (update-in [:amount] #(Double. %))
        (assoc :timestamp (clock/now clock))
        (assoc :user-id (get-in request [:session :user :id]))
        (db/save-event! db))
    (r/redirect (path :index))))

(defn event-list [db]
  (fn [request]
    (let [user (get-in request [:session :user])
          goals (db/load-goals db (:id user))
          events (db/load-events db (:id user))]
      (html-response (v/event-list-page user events goals)))))

(defn main-page [db clock]
  (fn [request]
    (let [user (get-in request [:session :user])
          goals (db/load-goals db (:id user))
          events (db/load-events db (:id user))
          stats (st/stats goals events clock)]
      (html-response (v/index-page user stats)))))

(defn secure [handler]
  (fn [request]
    (cond (get-in request [:session :user])
          (handler request)
          (= (env :environment) "dev")
          (-> request (assoc-in [:session :user] {:id 123 :name "John" :screen_name "john"}) handler)
          :else
          (r/redirect (path :login-form)))))

(defn secure-all [m]
  (into {} (map (fn [[k v]] [k (secure v)]) m)))

(defn controller-handlers [db clock]
  (->
    {:index          (main-page db clock)
     :add-goal       (add-goal db clock)
     :add-event      (add-event db clock)
     :event-list     (event-list db)
     :edit-goal-form (edit-goal-form db)
     :edit-goal      (edit-goal db)
     :hide-goal      (hide-goal db)
     }
    secure-all
    ))

