(ns causatum.core-test
  (:require [causatum.core :refer :all]
            [clojure.data.generators :as dg]
            [clojure.set :as set]
            [clojure.test :refer :all]))

;; The delay operations we use during the tests
(def delay-ops
  {:constant (fn [rtime delay] delay)})

(defn- simplify
  "Boils its input down to an [rtime state] pair for easy comparison."
  [event]
  [(:rtime event) (:state event)])

(defn- within-tolerance?
  "Returns true if `x` is within `tolerance` of `target`."
  [x target tolerance]
  (<= (- target tolerance) x (+ target tolerance)))

(defn- normalize
  "Given a map, returns a map with the same keys whose values are
  normized to a sum of 1.0."
  [m]
  (let [total (->> m vals (reduce +))]
   (reduce-kv (fn [a k v]
                (assoc a k (/ v total)))
              {}
              m)))

(defn- plausible?
  "Returns true if the frequencies of states in `events` matches
  `distribution`, a map of states to [probability tolerance] pairs."
  [distribution events]
  (let [freqs (->> events (map :state) frequencies normalize)]
    (and (= (-> freqs keys set) (-> distribution keys set))
         (every? (fn [[k v]]
                   (let [[prob tol] (get distribution k)]
                     (within-tolerance? v prob tol)))
                 freqs))))

(defmacro throws?
  "Returns true if body throws an exception"
  [body])

(deftest event-stream-tests
  (testing "Event stream generation."
    (testing "Null model and input stream produces an empty event stream."
      (is (empty? (event-stream {:graph {}} [])) ))
    (testing "Simple linear model"
      (is (= [[0 :a] [0 :b]]
             (->> (event-stream {:graph {:a [{:b {}}]}}
                                [{:rtime 0 :state :a}])
                  (map simplify)))))
    (testing "Linear model with simple constant delay"
      (is (= [[0 :a] [1 :b]]
             (->> (event-stream {:graph {:a [{:b {:delay [:constant 1]}}]}
                                 :delay-ops delay-ops}
                                [{:rtime 0 :state :a}])
                  (map simplify)))))
    (testing "Loopback model"
      (is (= (take 1000 (repeat [0 :a]))
             (->> (event-stream {:graph {:a [{:a {}}]}} [{:rtime 0 :state :a}])
                  (take 1000)
                  (map simplify)))))
    (testing "Binding dg/*rnd*"
      (let [model {:graph {:a [{:a {:weight 1}
                                :b {:weight 3}}]
                           :b [{:a {:weight 1}
                                :b {:weight 3}}]}}
            seeds [{:rtime 0 :state :a}]]
        (testing  "makes the stream stable if the seed is the same"
          (is (= (binding [dg/*rnd* (java.util.Random. 42)]
                   (doall (take 100 (event-stream model seeds))))
                 (binding [dg/*rnd* (java.util.Random. 42)]
                   (doall (take 100 (event-stream model seeds)))))))
        (testing "produces a difference sequence for different seeds"
          (is (not (= (binding [dg/*rnd* (java.util.Random. 42)]
                        (doall (take 100 (event-stream model seeds))))
                      (binding [dg/*rnd* (java.util.Random. 24)]
                        (doall (take 100 (event-stream model seeds))))))))))
    (testing "Model with weights"
      (is (plausible? {:a [0.25 0.1]
                       :b [0.75 0.1]}
                      (binding [dg/*rnd* (java.util.Random. 42)]
                        (->> (event-stream {:graph {:a [{:a {:weight 1}
                                                         :b {:weight 3}}]
                                                    :b [{:a {:weight 1}
                                                         :b {:weight 3}}]}}
                                           [{:rtime 0 :state :a}])
                             (take 1000))))))
    (testing "Unbound delay op throws"
      (is (thrown? clojure.lang.ExceptionInfo
                   (dorun (event-stream {:graph {:a [{:b {:delay [:unspecified]}}]}
                                         :delay-ops delay-ops}
                                        [{:rtime 0 :state :a}])))))
    (testing "Mutliple destination states"
      (is (= [[0 :a] [1 :b] [1 :c]]
             (->> (event-stream {:graph {:a [{:b {:delay [:constant 1]}}
                                             {:c {:delay [:constant 1]}}]}
                                 :delay-ops delay-ops}
                                [{:rtime 0 :state :a}])
                  (map simplify)))))))


