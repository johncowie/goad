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
            ))

;;; DATABASE

(defn save-goal! [r db]
  (prn (str "Adding: " r))
  (db/save! db "goals" r))
(defn save-event! [r db]
  (prn (str "Adding: " r))
  (db/save! db "events" r))
(defn load-goals [db] (db/query db "goals" {}))
(defn load-events [db] (db/query db "events" {}))

;; DATA PROCESSING

(defn equals [id a]
  #(= (id a) (id %)))

(defn required-per-milli [{:keys [target time-unit]}]
  (/ target (* time-unit 24 60 60 1000)))

(defn calculate-required [goal clock]
  (let [required-per-milli (required-per-milli goal)
        millis-elapsed (- (clock/now clock) (:timestamp goal))
        ;done-per-milli (/ (:total-done goal) (- (now clock) (:timestamp goal)))
        ]
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

(enlive/defsnippet habits-snippet "public/templates/bootstrap.html" [:#habits]
  [goals]
  [:.goal-row]
  (enlive/clone-for [goal goals]
                    [:.goal-row :.hidden-goal-id] (enlive/set-attr :value (:goal-id goal))
                    [:.goal-row :.goal-done-so-far] (enlive/content (str (:total-done goal)))
                    [:.goal-row :.goal-name] (enlive/content (:name goal))
                    [:.goal-row :.goal-target] (enlive/content (str (:target goal)))
                    [:.goal-row :.progress-percentage] (enlive/content (str (:progress goal)))))

(enlive/defsnippet goal-form-snippet "public/templates/bootstrap.html" [:#goal-form]
  [])

(enlive/deftemplate index-template "public/templates/bootstrap.html" [user snip]
  [:title] (enlive/content "Goad")
  [:#content] (enlive/content snip)
  [:#navigation-login :a] (enlive/content (if user (str "Logout, " (:name user)) "Login"))
  [:#navigation-login :a] (enlive/set-attr :href (if user (path :logout) (path :login-form)))
  )

(defn page [user snippet]
  (reduce str (index-template user snippet)))

(defn index-page [user goals]
  (page user [(habits-snippet goals) (goal-form-snippet)]))

(defn login-page []
  (page nil (login-snippet)))

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
     generate-goal-id
     (save-goal! db))
    (r/redirect (path :index))))

(defn add-event [db clock]
  (fn [request]
    (-> request
     :params
     (select-keys [:amount :goal-id :comments])
     (update-in [:amount] #(Integer. %))
     (assoc :timestamp (clock/now clock))
     (save-event! db))
    (r/redirect (path :index))))

(defn main-page [db clock]
  (fn [request]
    (let [user (get-in request [:session :user])
          goals (load-goals db)
          events (load-events db)
          stats (stats goals events clock)]
      (prn goals)
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
    (if (get-in request [:session :user])
      (handler request)
      (r/redirect (path :login-form)))))

(defn route-handlers [db clock twitter-auth]
  {:index (secure (main-page db clock))
   :add-goal (secure (add-goal db clock))
   :add-event (secure (add-event db clock))
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
   request-printer
   site
   ;;(wrap-error-handling (constantly (r/response "ERROR!")))
   ))

(def app-port (or (Integer. (env :port)) 3000))
(def mongo-uri (or (env :mongo-uri) "mongodb://localhost:27017/goad"))

(def mongo (.start (db/new-mongo-db mongo-uri)))
(def clock (clock/new-joda-clock))
(def twauth #_(oauth/new-twitter-oauth)
  (oauth/new-stub-twitter-oauth {:name "John" :id 123 :screen_name "johncowie"}))

(def app (make-app (scenic-handler routes (route-handlers mongo clock twauth) not-found-handler)))

(defn -main [& args]
  (run-jetty app {:port app-port}))
