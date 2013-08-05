(ns causatum.evolution
  "Functions for evolving models towards a fitness goal."
  (:require [clojure.data.generators :as dg]))

(defn- walk-consequents
  "Given an option map, updates all the transition descriptions via
  `f`, where `f` is per `walk-model`."
  [consequents model outbound-state f]
  (reduce-kv (fn [m inbound-state transition]
               (assoc m inbound-state
                      (f model
                         outbound-state
                         inbound-state
                         transition)))
             {}
             consequents))

(defn walk-model
  "Given a model, returns a new model whose have been changed by f. f
  must be a function of four arguments: the model, the outbound state,
  the inbound state, and the original transition description."
  [f model]
  (assoc model
    :graph (reduce-kv (fn [m outbound-state consequents]
                        (assoc m
                          outbound-state
                          (map #(walk-consequents % model outbound-state f)
                               consequents)))
                      {}
                      (:graph model))))

;; TODO: This is a pretty ad-hoc way of perturbation. If someone knows
;; how to do this right, please let me know.
(defn perturb-number
  "Returns a number in the range [(- x (* amount x) to (+ x (* amount x)]."
  [x amount]
  (+ x (* x amount (-> (dg/double) (* 2) (- 1)))))

(defn perturb-weight
  "Given a map describing a single state transition, return a new one
  with its `:weight`` value randomly altered by amount, although never
  to less than zero."
  [transition amount]
  (let [weight (:weight transition)]
   (if weight
     (assoc transition :weight (max 0.0 (perturb-number weight amount))))))

(defn perturb-edge
  "Given a map defining a state transition edge, returns a new map
  whose weights have been randomly changed by `amount`, although never
  to less than zero."
  [edge amount]
  (reduce-kv (fn [m k v]
               (assoc m k (perturb-weight amount v)))
             {}
             edge))

(defn perturb-weights
  "Given a model, returns a new model whose weights have been randomly
  changed by `amount`, although never to less than zero."
  [model amount]
)

(defn- model-stream*
  "Internal implementation of model-stream"
  [model score scorer generator index]
  (let [[next-score next-model] (->> (repeatedly #(generator model index))
                                     (map (fn [m] [(scorer m) m]))
                                     (drop-while (fn [[s m]] (<= s score)))
                                     first)]
    (lazy-seq
     (cons model
           (when next-model
             (model-stream* next-model next-score scorer generator (inc index)))))))

(defn model-stream
  "Returns an infinite sequence of models starting with `initial`. Each
  successive model will have a higher fitness (as determined by
  calling the `fitness-fn` function on it). Creates candidate models
  with `generator`, a function of the previous model and the iteration
  number.

  `(generator model index)` will be called repeatedly until it either
  returns a more-fit model or nil."
  [initial fitness-fn generator]
  (model-stream* initial (fitness-fn initial) fitness-fn generator 0))
