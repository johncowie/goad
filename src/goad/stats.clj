(ns goad.stats
  (:require [thingies.clock :as clock]))

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

(defn min-zero [v] (if (< v 0) 0 v))

(defn calculate-remaining [goal]
  (->> (- (:required goal) (:total-done goal))
       (assoc goal :remaining)))

(defn calculate-percentage-done [goal]
  (let [{:keys [required total-done]} goal]
    (assoc goal :progress (Math/floor (* (/ total-done required) 100.0)))))

(defn stats-for-goal [goal events clock]
  (->
    goal
    (calculate-total-done events)
    (calculate-required clock)
    calculate-remaining
    calculate-percentage-done))

(defn stats [goals events clock]
  (map #(stats-for-goal % events clock) goals))
