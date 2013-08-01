(ns causatum.core
  "Functions for generating event streams based on stochastic state
  machine models."
  (:require [clojure.data.generators :as dg]))

;;; Things that should live in clojure.data.generators

(defn rand-exp
  "Return an exponentially-distributed random number with an expected
  value of lambda"
  [lambda]
  (- (* (Math/log (dg/double)) lambda)))

;;; Validation

(defn- seq-of-maps?
  "Returns true if `coll` is a sequence of maps."
  [coll]
  (and (coll? coll) (every? map? coll)))

(defmacro graph-validation-clause
  "Emits an expression that asserts a single fact about model"
  [graph test message reason]
  `(when-not ~test
     (throw (ex-info ~message
                     {:reason ~reason
                      :graph ~graph}))))

;; TODO: Consider returning a result rather than throwing. Probably
;; makes sense to use a validation library, too, although introducing
;; a dependency may not be worth it given that we have almost none
;; now.
(defn assert-graph-valid
  "Throws unless `graph` looks like a reasonable state transition graph."
  [graph]
  (graph-validation-clause graph
                           graph
                           "Graph is nil"
                           :graph-validation/nil)
  (graph-validation-clause graph
                           (map? graph)
                           "Graph is not a map"
                           :graph-validation/not-map)
  (graph-validation-clause graph
                           (->> graph vals (every? seq-of-maps?))
                           "Graph contains values that aren't sequences of maps. Did you make some of them maps instead of seqs of maps?"
                           :graph-validation/values-are-not-map-seqs)
  true)

(defn assert-model-valid
  "Throws unless `model` looks reasonable."
  [model]
  (assert-graph-valid (:graph model))
  ;; TODO: Validate other parts of the model
  )

;;; Event stream generation

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; State machine-based event streams
;;
;; Event times are recorded relative to a notional start time of zero.
;; Noted within the api as an `rtime`.
;;
;; An agenda (an internal implementation detail of this library) is a
;; sorted map whose keys are a relative time whose values are a
;; sequence of events.
;;
;; An event is a map with at least :state and :rtime keys.
;;
;; State transitions are modeled as sort-of Markov chains, which are
;; maps of the current state to edge descriptors. An edge descriptor
;; is a vector of maps from new states to descriptions of how likely
;; those states are and how long the transition takes. Consider the
;; example of modeling the behavior of a user who arrives at the home
;; page, then goes and looks at a few product pages, then goes away
;; for a while or maybe forever:
;;
;; {:home        [{:home         {:weight    1
;;                                :delay [:new-browser-interarrival-time]}}
;;                {:inactive     {:weight    1
;;                                :delay [:rand-exp 1]}
;;                 :view-product {:weight    2
;;                                :delay [:rand-exp 10]}}]
;;  :inactive     [{:view-product {:weight    1
;;                                 :delay [:rand-exp 100]}
;;                  :home         {:weight    0.1
;;                                 :delay [:rand-exp 100]}}]
;;  :view-product [{:inactive     {:weight    1
;;                                 :delay [:constant 0]}
;;                  :gone         {:weight    2
;;                                 :delay [:constant 0]}
;;                  :view-product {:weight    3
;;                                 :delay [:rand-exp 1000]}}]
;;  }
;;
;; An individual edge descriptor:
;;
;; {:inactive     {:weight 1
;;                 :delay [:constant 1]}
;;  :view-product {:weight 2
;;                 :delay [:some-function]}
;;
;; The value of :delay is a vector whose first element is a keyword
;; indicating the algorithm to use for generating delays. The
;; algorithm is mapped to a function via the delay-fns argument of the
;; functions below. The function will be called with the current rtime
;; as the first argument and the other elements (if any) of the :delay
;; vector, and is expected to return an amount of time to delay until
;; the successor state is achieved. If delay is absent, a constant
;; value of zero will be used.
;;
;; Events are created by an event-ctor, which is a function of a
;; previous event and a successor. A successor is one of the values
;; from an edge descriptor with a :state key associated indicating the
;; target state. If the event-ctor returns nil, the successor is used
;; as-is.

(defn cumulative-weight
  "Given an edge descriptor map, returns a [total odds] pair, where
  total is the total weight, and odds is a seq of [cumul target]
  pairs. cumul is the cumulative weight and target is the original
  map with a :state key added."

  ;; An example result:
  ;;
  ;; [3 [[1 {:state :inactive
  ;;         :weight 1
  ;;         :delay [:rand-exp 1]}]
  ;;     [3 {:state :view-product
  ;;         :weight 2
  ;;         :delay [:constant 10k]}]]]
  [edge]
  (reduce-kv
   (fn [[c m] k v]
     (let [l (or (:weight v) 0)]
       (if (zero? l)
         [c m]
         (let [new-c (+ c l)]
           [new-c (conj m [new-c (assoc v :state k)])]))))
   [0.0 []]
   edge))

(defn successor-delay
  "Given a description of a successor state, a map of delay algorithm
  keywords to functions and the current relative time, return an
  amount of time to delay before transitioning to that state, based on
  the description. Otherwise, return zero."
  [successor delay-fns rtime]
  (let [[delay-op & args] (get successor :delay [::default])
        delay-fn (if (= ::default delay-op)
                   (constantly 0)
                   (get delay-fns delay-op))]
    (when-not delay-fn
      (throw (ex-info (str "Couldn't find a delay function for " delay-op)
                      {:reason :no-delay-fn
                       :successor successor
                       :delay-op delay-op})))
    (apply delay-fn rtime args)))

(defn successor-entry
  "Given a state transition edge descriptor, randomly pick a new
  successor entry and return it."
  [edge]
  (if (= 1 (-> edge keys count))
    (assoc (-> edge vals first) :state (-> edge keys first))
    (let [[total cumul] (cumulative-weight edge)
          r             (* total (dg/double))]
      (->> cumul (drop-while (fn [[c m]] (< c r))) first second))))

(defn successor
  "Given a state transition edge descriptor, randomly pick a new state
  and return a new event. Return nil if there is no successor state."
  [rtime event edge delay-fns event-ctor]
  (when edge
    (let [successor (successor-entry edge)]
      (assoc (or (event-ctor event successor) successor)
        :rtime
        (+ rtime (successor-delay successor delay-fns rtime))))))

(defn- default-event-ctor
  "The default event constructor, which constructs a successor by
  merging it with the generating event."
  [event successor]
  (merge event successor))

(defn generate
  "Given a model and an rtime, returns a (potentially empty) seq of
  events to be added to the agenda."
  [model event rtime]
  (map #(successor rtime
                   event
                   %
                   (:delay-ops model)
                   (get model :event-ctor default-event-ctor))
       (get (:graph model) (:state event))))

(defn merge-event-stream
  "Adds the events in `event-stream` to `event-map`, assumed to be a
  sorted map of events by time."
  [event-map event-stream]
  (reduce
   (fn [a event]
     (let [rtime (:rtime event)]
       (merge-with into a {rtime [event]})))
   event-map
   event-stream))

(defn next-agenda
  "Given an model and an agenda, generates new events based on the events at the
  head of the agenda, removes the events at the head, and returns a
  new agenda with the new events inserted."
  [model agenda]
  (let [{:keys [event-stream future-events]} agenda
        head-rtime (or (-> future-events first :rtime)
                       (-> event-stream first :rtime))
        [event-stream-head event-stream-tail] (split-with #(<= (:rtime %) head-rtime)
                                                          event-stream)
        future-events* (merge-event-stream future-events event-stream-head)
        [now-rtime now-events] (first future-events*)
        new-events (mapcat #(generate model % now-rtime) now-events)
        rest-future-events (dissoc future-events* now-rtime)
        updated-future-events (merge-event-stream rest-future-events new-events)]
    (-> agenda
        (assoc :future-events updated-future-events)
        (assoc :event-stream event-stream-tail))))

(defn agenda
  "Creates a new agenda that is seeded with events from `event-stream`."
  [event-stream]
  (let [first-rtime (-> event-stream first :rtime)
        [first-events rest-events] (split-with #(= first-rtime (:rtime %))
                                               event-stream)]
    {:event-stream rest-events
     :future-events (merge-event-stream (sorted-map) first-events)}))

(defn- event-stream-generator
  "Given a model and an agenda, generates an infinite sequence of
  events."
  [model agenda]
  (let [[rtime events] (-> agenda :future-events first)]
    (when (seq events)
      (lazy-cat events
                (event-stream-generator model
                                        (next-agenda model agenda))))))

(defn event-stream
  "Given a model and a seeding event-stream, generates a (lazy,
  potentially infinite) sequence of events.

  A model is a map containing at least the key :graph (a map of event
  states to sequences of state transition descriptions). It may also
  contain :delay-ops, a map of delay operation identifiers to delay
  functions. An :event-ctor key may also be present, in which case its
  value must be a function of three arguments: an event, the chosen
  successor event, and the rtime of the transition. The function must
  return the actual successor event."
  [model event-stream]
  (assert-model-valid model)
  (event-stream-generator model (agenda event-stream)))


