(ns causatum.event-streams-test
  (:require [causatum.event-streams :refer :all]
            [causatum.util]
            [clojure.data.generators :as dg]
            [clojure.set :as set]
            [clojure.test :refer :all]))

;; The delay operations we use during the tests
(def delay-ops
  {:constant (fn [rtime delay] delay)
   :rand-exp (fn [rtime mean] (causatum.util/rand-exp mean))})

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

(defn- rtime-ordered?
  "Returns true if the event sequence is monotonically nondecreasing in rtime."
  [events]
  (->> (map vector events (drop 1 events))
       (every? (fn [[e1 e2]] (<= (:rtime e1) (:rtime e2))))))

(defmacro throws?
  "Returns true if body throws an exception"
  [body])

(deftest agenda-tests
  (testing "Agendas don't grow too much"
    (let [model {:graph {:a [{:b {}}]}}
          seed-events (map (fn [rtime] {:state :a :rtime rtime}) (iterate inc 0))]
      ;; Yes it's weird that we're testing a private function. It's
      ;; even weirder that that function exists, but if there's more
      ;; than one item in :future-events, it means we're keeping track
      ;; of too many things. v0.1.0 had a bug in it that led to memory
      ;; growth over time. It's important to avoid that.
      ;;
      ;; TODO: Validate this through a functional test instead of a
      ;; unit test.
      (is (>= 1 (-> (->> (iterate (fn [[events event-stream agenda]]
                                    (@#'causatum.event-streams/next-state model event-stream agenda))
                                  [[] seed-events (sorted-map)])
                         (drop 100))
                    first
                    (nth 2)
                    count))))))

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
                             (take 1000)
                             doall)))))
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
                  (map simplify)))))
    (testing "Input stream of length greater than one"
      (is (= 20
             (count (event-stream {:graph {:a [{:b {}}]}}
                                  (map (fn [rtime] {:state :a :rtime rtime})
                                       (range 10)))))))
    (testing "Infinite input event stream"
      (is (= [[10 :a] [10 :b] [11 :a] [11 :b]]
             (->> (event-stream {:graph {:a [{:b {}}]}}
                                (map (fn [rtime] {:state :a :rtime rtime})
                                     (iterate inc 0)))
                  (drop 20)
                  (take 4)
                  (map simplify)))))
    (testing "Out-of-order event bug is gone"
      (is (rtime-ordered?
           (binding [dg/*rnd* (java.util.Random. 42)]
             (doall (event-stream {:graph {:a [{:b {:weight 1 :delay [:rand-exp 1.0]}
                                                :c {:weight 1 :delay [:rand-exp 1.0]}}]}
                                   :delay-ops delay-ops}
                                  (map (fn [rtime] {:state :a :rtime rtime})
                                       (range 100))))))))))