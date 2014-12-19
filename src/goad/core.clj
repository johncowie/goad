(ns goad.core
  (:require [thingies.db.document :as db]
            [thingies.clock :as clock]
            [thingies.oauth.twitter :as oauth]
            [environ.core :refer [env]]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.util.response :as r]
            [middleware.core :refer [site wrap-error-handling]]
            [scenic.routes :refer [scenic-handler]]
            [goad.routes :refer [routes path]]
            [clojure.string :as s]
            [net.cgrand.enlive-html :as enlive]
            [clj-time.core :as t]
            [clj-time.coerce :as tc]
            [clj-time.format :as tf]
            ))

;;; DATABASE

(defn save-goal! [r db]
  (prn (str "Adding: " r))
  (db/save! db "goals" r))
(defn save-event! [r db]
  (prn (str "Adding: " r))
  (db/save! db "events" r))
(defn update-goal! [r db]
  (prn (str "Updating: " r))
  (db/update! db "goals" {:goal-id (:goal-id r)} r))
(defn load-goals [db user-id] (db/query db "goals" {:user-id user-id}))
(defn load-goal [db user-id goal-id] (first (db/query db "goals" {:user-id user-id :goal-id goal-id})))
(defn load-events [db user-id] (db/query db "events" {:user-id user-id}))

;; DATA PROCESSING

(defn equals [id a]
  #(= (id a) (id %)))

(defn required-per-milli [{:keys [target time-unit]}]
  (/ target (* time-unit 24 60 60 1000)))

(defn calculate-required [goal clock]
  (let [required-per-milli (required-per-milli goal)
        millis-elapsed (- (clock/now clock) (:timestamp goal))]
    (assoc goal :required (* millis-elapsed required-per-milli))))

(defn calculate-total-done [goal events]
  (->> events
       (filter (equals :goal-id goal))
       (map :amount)
       (reduce +)
       (assoc goal :total-done)))

(defn calculate-percentage-done [goal]
  (let [{:keys [required total-done]} goal]
    (assoc goal :progress (format "%.0f%%" (* (/ total-done required) 100.0)))))

(defn stats-for-goal [goal events clock]
  (->
   goal
   (calculate-total-done events)
   (calculate-required clock)
   calculate-percentage-done))

(defn stats [goals events clock]
  (map #(stats-for-goal % events clock) goals))

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
                    [:.goal-row :.goal-done-so-far] (enlive/content (str (:total-done goal)))
                    [:.goal-row :.goal-name :a] (enlive/content (:name goal))
                    [:.goal-row :.goal-name :a]
                    (enlive/set-attr :href (path :edit-goal-form :goal (:goal-id goal)))
                    [:.goal-row :.goal-target] (enlive/content (goal-unit-text goal))
                    [:.goal-row :.progress-percentage] (enlive/content (str (:progress goal)))
                    [:.goal-row :.goal-required] (enlive/content (str (int (:required goal))))
                    [:.goal-row :form] (enlive/set-attr :action (path :add-event))
                    ))

(enlive/defsnippet goal-form-snippet "public/templates/bootstrap.html" [:#goal-form]
  [goal action]
  [:#name] (enlive/set-attr :value (:name goal))
  [:#target] (enlive/set-attr :value (str (:target goal)))
  [:#unit] (enlive/set-attr :value (str (:unit goal)))
  [:form.add-goal] (enlive/set-attr :action action)
  [:form.add-goal :button] (enlive/content (if goal "Edit" "Add")))

(def event-date-format (tf/formatter "HH:mm dd/MM/yyyy"))

(defn format-timestamp [timestamp formatter]
  (->> (tc/from-long timestamp)
       (tf/unparse formatter)))

(enlive/defsnippet event-list-snippet "public/templates/bootstrap.html" [:#events]
  [events goal-id->name-map]
  [:.event-row]
  (enlive/clone-for [event (sort-events events)]
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
  [:.events-nav-link] (if (= context :event-list) (enlive/add-class "active") (enlive/remove-class "active"))
  )

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
     (save-goal! db))
    (r/redirect (path :index))))

(defn edit-goal [db]
  (fn [request]
    (let [user-id (get-in request [:session :user :id])
          goal-id (get-in request [:params :goal])
          existing-goal (load-goal db user-id goal-id)]
      (-> request
          :params
          (select-keys [:name :target :unit :time-unit])
          (update-in [:target] #(Integer. %))
          (update-in [:time-unit] #(Integer. %))
          (merge (select-keys existing-goal [:timestamp :user-id :goal-id]))
          (update-goal! db)))
    (r/redirect (path :index))))

(-> {} (merge (select-keys {:a 1 :b 2} [:a])))

(defn edit-goal-form [db]
  (fn [request]
    (let [user (get-in request [:session :user])
          goal-id (get-in request [:params :goal])]
      (when-let [goal (load-goal db (:id user) goal-id)]
        (html-response (edit-goal-page user goal))))))

(defn add-event [db clock]
  (fn [request]
    (-> request
     :params
     (select-keys [:amount :goal-id :comments])
     (update-in [:amount] #(Integer. %))
     (assoc :timestamp (clock/now clock))
     (assoc :user-id (get-in request [:session :user :id]))
     (save-event! db))
    (r/redirect (path :index))))

(defn event-list [db]
  (fn [request]
    (let [user (get-in request [:session :user])
          goals (load-goals db (:id user))
          events (load-events db (:id user))]
      (html-response (event-list-page user events goals)))))

(defn main-page [db clock]
  (fn [request]
    (let [user (get-in request [:session :user])
          goals (load-goals db (:id user))
          events (load-events db (:id user))
          stats (stats goals events clock)]
      (html-response (index-page user stats)))))

;;; AUTH

(defn absolute-url-from-request [request relative-url]
  (format "%s://%s%s%s"
          (name (request :scheme))
          (:server-name request)
          (if (= (:server-port request) 80) "" (str ":" (:server-port request)))
          relative-url))

(defn login-form [request]
  (html-response (login-page)))

(defn logout [request]
  (-> (r/redirect (path :login-form))
      (assoc :session {})))

(defn login [twitter-oauth]
  (fn [request]
    (let [callback-url (absolute-url-from-request request (path :twitter-callback))
          {:keys [request-token authentication-url]} (oauth/get-request-token twitter-oauth callback-url)]
      (assoc-in (r/redirect-after-post authentication-url) [:session :request-token] request-token))))

(defn twitter-callback [twitter-oauth]
  (fn [request]
    (let [request-token (get-in request [:session :request-token])
          oauth-verifier (get-in request [:params :oauth_verifier])
          user (oauth/callback twitter-oauth request-token oauth-verifier)]
      (if user
        (-> (r/redirect (path :index))
            (assoc-in [:session :user] (select-keys user [:name :id :screen_name])))
        (r/redirect (path :index))))))

;;; HANDLERS AND STUFF

(defn secure [handler]
  (fn [request]
    (cond (get-in request [:session :user])
          (handler request)
          (= (env :environment) "dev")
          (-> request (assoc-in [:session :user] {:id 123 :name "John" :screen_name "john"}) handler)
          :else
          (r/redirect (path :login-form)))))

(defn route-handlers [db clock twitter-auth]
  {:index (secure (main-page db clock))
   :add-goal (secure (add-goal db clock))
   :add-event (secure (add-event db clock))
   :event-list (secure (event-list db))
   :edit-goal-form (secure (edit-goal-form db))
   :edit-goal (secure (edit-goal db))
   :twitter-callback (twitter-callback twitter-auth)
   :login-form login-form
   :login (login twitter-auth)
   :logout logout
   })

;;; APP SET UP

(defn not-found-handler [request]
  (html-response "Not found."))

(defn request-printer [handler]
  (fn [r] (prn r)
    (handler r)))

(defn make-app [handler]
  (->
   handler
   site
   ;;(wrap-error-handling (constantly (r/response "ERROR!")))
   ))

(def app-port (Integer. (or (env :port) "3000" )))
(def mongo-uri (or (env :mongo-uri) "mongodb://localhost:27017/goad"))

(def mongo (.start (db/new-mongo-db mongo-uri)))
(def clock (clock/new-joda-clock))
(def twauth
  (if (= (env :environment) "dev")
    (oauth/new-stub-twitter-oauth {:name "John" :id 123 :screen_name "johncowie"})
    (.start (oauth/new-twitter-oauth (env :twitter-key) (env :twitter-secret)))))

(def app (make-app (scenic-handler routes (route-handlers mongo clock twauth) not-found-handler)))

(defn -main [& args]
  (run-jetty app {:port app-port}))
