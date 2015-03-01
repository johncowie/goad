(ns goad.core
  (:gen-class)
  (:require [thingies.clock :as clock]
            [thingies.oauth.twitter :as oauth]
            [environ.core :refer [env]]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.util.response :as r]
            [middleware.core :refer [site wrap-error-handling]]
            [scenic.routes :refer [scenic-handler]]
            [goad.routes :refer [routes path]]
            [clojure.string :as s]
            [goad.db :as db]
            [goad.controller :as ctr]
            [goad.view :as v]
            ))

;;; AUTH

(defn absolute-url-from-request [request relative-url]
  (if (s/blank? (env :app-url))
    (format "%s://%s%s%s"
            (name (request :scheme))
            (:server-name request)
            (if (= (:server-port request) 80) "" (str ":" (:server-port request)))
            relative-url)
    (str (env :app-url) relative-url)
    ))

(defn login-form [request]
  (ctr/html-response (v/login-page)))

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

(defn whitelist-handlers [twitter-auth]
  {:twitter-callback (twitter-callback twitter-auth)
   :login-form       login-form
   :login            (login twitter-auth)
   :logout           logout}
  )

(defn route-handlers [db clock twitter-auth]
  (merge (whitelist-handlers twitter-auth)
         (ctr/controller-handlers db clock)))

;;; APP SET UP

(defn not-found-handler [request]
  (ctr/html-response "Not found."))

(defn request-printer [handler]
  (fn [r] (prn r)
    (handler r)))

(defn make-app [handler]
  (->
    handler
    site
    ;;(wrap-error-handling (constantly (r/response "ERROR!")))
    ))

(def app-port (Integer. (or (env :port) "3000")))
(def mongo-uri (or (env :mongo-uri) "mongodb://localhost:27017/goad"))

(def mongo (db/start-db! mongo-uri))
(def clock (clock/new-joda-clock))
(def twauth
  (if (= (env :environment) "dev")
    (oauth/new-stub-twitter-oauth {:name "John" :id 123 :screen_name "johncowie"})
    (.start (oauth/new-twitter-oauth (env :twitter-key) (env :twitter-secret)))))

(def app (make-app (scenic-handler routes (route-handlers mongo clock twauth) not-found-handler)))

(defn -main [& args]
  (run-jetty app {:port app-port}))
