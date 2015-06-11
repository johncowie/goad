(ns goad.view
  (:require [net.cgrand.enlive-html :as enlive]
            [clj-time.format :as tf]
            [clj-time.coerce :as tc]
            [goad.routes :refer [path]]))

;; VIEWS

(enlive/defsnippet login-snippet "public/templates/bootstrap.html" [:#login] [])

(def time-units {1 "day" 7 "week" 28 "month"})

(defn goal-unit-text [{:keys [target unit time-unit]}]
  (format "%s %s per %s" target unit (time-units time-unit)))

(defn compare-desc [a b]
  (* -1 (compare a b)))

(defn sort-events [events]
  (sort-by :timestamp compare-desc events))

(defn sort-goals [goals]
  (sort-by :progress goals))

(enlive/defsnippet habits-snippet "public/templates/bootstrap.html" [:#habits]
                   [goals]
                   [:.goal-row]
                   (enlive/clone-for [goal (sort-goals goals)]
                                     [:.goal-row :.hidden-goal-id] (enlive/set-attr :value (:goal-id goal))
                                     [:.goal-row :.goal-done-so-far] (enlive/content (format "%.2f" (float (:total-done goal))))
                                     [:.goal-row :.goal-name :a] (enlive/content (:name goal))
                                     [:.goal-row :.goal-name :a]
                                     (enlive/set-attr :href (path :edit-goal-form :goal (:goal-id goal)))
                                     [:.goal-row :.goal-target] (enlive/content (goal-unit-text goal))
                                     [:.goal-row :.progress-percentage] (enlive/content (format "%.0f%%" (:progress goal)))
                                     [:.goal-row :.goal-required] (enlive/content (format "%.2f" (float (:remaining goal))))
                                     [:.goal-row :form] (enlive/set-attr :action (path :add-event))
                                     ))

(enlive/defsnippet goal-form-snippet "public/templates/bootstrap.html" [:#goal-form]
                   [goal action]
                   [:#name] (enlive/set-attr :value (:name goal))
                   [:#target] (enlive/set-attr :value (str (:target goal)))
                   [:#unit] (enlive/set-attr :value (str (:unit goal)))
                   [:form.add-goal] (enlive/set-attr :action action)
                   [:form.add-goal :button] (enlive/content (if goal "Edit" "Add"))
                   [:form.hide-goal :button] (enlive/content "Hide")
                   [:form.hide-goal] (enlive/set-attr :action (path :hide-goal :goal (:goal-id goal "")))
                   [:form.hide-goal] (if goal identity nil)
                   )

(def event-date-format (tf/formatter "HH:mm dd/MM/yyyy"))

(defn format-timestamp [timestamp formatter]
  (->> (tc/from-long timestamp)
       (tf/unparse formatter)))

(enlive/defsnippet event-list-snippet "public/templates/bootstrap.html" [:#events]
                   [events goal-id->name-map]
                   [:.event-row]
                   (enlive/clone-for [event (take 100 (sort-events events))]
                                     [:.event-time] (enlive/content (-> (:timestamp event) (format-timestamp event-date-format)))
                                     [:.event-goal] (enlive/content (get goal-id->name-map (:goal-id event)))
                                     [:.event-done] (enlive/content (str (:amount event)))
                                     [:.event-comments] (enlive/content (str (:comments event)))))

(enlive/deftemplate index-template "public/templates/bootstrap.html" [context user snip]
                    [:title] (enlive/content "Goad")
                    [:#content] (enlive/content snip)
                    [:#navigation-login :a] (enlive/content (if user (str "Logout, " (:name user)) "Login"))
                    [:#navigation-login :a] (enlive/set-attr :href (if user (path :logout) (path :login-form)))
                    [:.index-nav-link :a] (enlive/set-attr :href (path :index))
                    [:.index-nav-link] (if (= context :index) (enlive/add-class "active") (enlive/remove-class "active"))
                    [:.events-nav-link :a] (enlive/set-attr :href (path :event-list))
                    [:.events-nav-link] (if (= context :event-list) (enlive/add-class "active") (enlive/remove-class "active")))

(defn page [context user snippet]
  (reduce str (index-template context user snippet)))

;; PAGES

(defn index-page [user goals]
  (page :index user [(habits-snippet goals) (goal-form-snippet nil (path :add-goal))]))

(defn goal-id->name-map [goals]
  (into {} (map (juxt :goal-id :name) goals)))

(defn event-list-page [user events goals]
  (page :event-list user (event-list-snippet events (goal-id->name-map goals))))

(defn edit-goal-page [user goal]
  (page nil user (goal-form-snippet goal (path :edit-goal :goal (:goal-id goal)))))

(defn login-page []
  (page nil nil (login-snippet)))
