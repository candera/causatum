(ns causatum.event-streams
  "Functions for generating event streams based on stochastic state
  machine models."
  (:require [clojure.data.generators :as dg]))

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

;; Yes it's weird that this is its own function. But it lets me write
;; a test to verify that the agenda doesn't grow unbounded over
;; time, which was a big bug in v0.1.0.
(defn- next-state
  "Calculate the tuple [now-events next-event-stream next-agenda] given
  the model, the current event stream and the current agenda."
  [model event-stream agenda]
  (let [now-rtime         (min (-> agenda ffirst (or Double/POSITIVE_INFINITY))
                               (-> event-stream first :rtime (or Double/POSITIVE_INFINITY)))
        [es-now-events
         es-later-events] (split-with #(<= (:rtime %) now-rtime)
                                      event-stream)
        agenda-now-events (get agenda now-rtime)
        agenda-later      (dissoc agenda now-rtime)
        now-events        (into agenda-now-events es-now-events)
        generated-events  (mapcat #(generate model % now-rtime) now-events)]
    [now-events es-later-events (merge-event-stream agenda-later generated-events)]))

(defn- event-stream-generator
  "Given a model, an event stream and an agenda, generates a
  potentially infinite sequence of events."
  [model event-stream agenda]
  (let [[now-events next-event-stream next-agenda] (next-state model event-stream agenda)]
    (when (seq now-events)
      (lazy-cat now-events (event-stream-generator model next-event-stream next-agenda)))))

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
  (event-stream-generator model event-stream (sorted-map)))


