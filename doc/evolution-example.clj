(require '[causatum.event-streams :refer :all])
(require '[causatum.evolution :as ev])
(require '[causatum.util :as util])
(require '[clojure.data.generators :as dg])

;; Goal: a model that produces events with these [frequency tolerance]:

(def target-distribution
  {:home-page      [0.2 0.02]
   :product-page-1 [0.7 0.07]
   :product-page-2 [0.1 0.01]})

(defn within-tolerance?
  [target actual]
  (every? (fn [[k [freq tol]]]
            (< (- freq tol) (get actual k 0) (+ freq tol)))
          target))


;; Here's our starting guess at what that might look like. Users
;; bounce around between the home page and two product pages, and
;; eventually leave. We think they prefer the product page 1. Note
;; that there are no successor states for :gone.

(def initial-model
  {:graph {:home-page      [{:home-page      {:weight 2 :delay [:rand-exp 1]}
                             :product-page-1 {:weight 7 :delay [:rand-exp 1]}
                             :product-page-2 {:weight 1 :delay [:rand-exp 1]}
                             :gone           {:weight 1}}]
           :product-page-1 [{:home-page      {:weight 2 :delay [:rand-exp 1]}
                             :product-page-1 {:weight 7 :delay [:rand-exp 1]}
                             :product-page-2 {:weight 1 :delay [:rand-exp 1]}
                             :gone           {:weight 1}}]
           :product-page-2 [{:home-page      {:weight 2 :delay [:rand-exp 1]}
                             :product-page-1 {:weight 7 :delay [:rand-exp 1]}
                             :product-page-2 {:weight 1 :delay [:rand-exp 1]}
                             :gone           {:weight 1}}]}
   :delay-ops {:rand-exp (fn [rtime lambda] (util/rand-exp lambda))}})

;; Users arrive at the home page every second. We number them by their
;; arrival time.

(def user-arrivals
  (map (fn [rtime]
         {:state :home-page
          :rtime rtime
          :user-id rtime})
       (iterate inc 0)))

(take 5 user-arrivals)

;; =>
[{:state :home-page, :rtime 0, :user-id 0}
 {:state :home-page, :rtime 1, :user-id 1}
 {:state :home-page, :rtime 2, :user-id 2}
 {:state :home-page, :rtime 3, :user-id 3}
 {:state :home-page, :rtime 4, :user-id 4}]


;; Users arrive, bounce around, and leave
(->> (event-stream initial-model user-arrivals)
     (drop 1000)
     (take 10)
     (map (juxt :state :rtime :user-id)))

;; =>
[[:home-page 92.95190216047972 62]
 [:product-page-1 92.98783780708989 92]
 [:home-page 93 93]
 [:product-page-1 93.06423741247553 67]
 [:product-page-1 93.07512849941065 91]
 [:product-page-1 93.1200991986615 62]
 [:product-page-1 93.18719019365743 67]
 [:product-page-1 93.21932513984366 85]
 [:gone 93.21932513984366 85]
 [:home-page 93.22512472586982 89]]

;; That looks like a decent mix. Note that the stream is random, but
;; we can make it use a particular seed like this. Note the doall,
;; which is important to ensure that - because the result of calling
;; event-stream is lazy - that the computations happen while the
;; binding is in effect.

(binding [dg/*rnd* (java.util.Random. 42)]
  (->> (event-stream initial-model user-arrivals)
       (drop 1000)
       (take 4)
       (map (juxt :state :rtime :user-id))
       doall))

;; =>
[[:product-page-1 82.50739031997686 66]
 [:product-page-1 82.71814766291598 68]
 [:product-page-1 82.77743322809987 82]
 [:product-page-1 82.85120663636485 48]]

;; And if we run it again:

[[:product-page-1 82.50739031997686 66]
 [:product-page-1 82.71814766291598 68]
 [:product-page-1 82.77743322809987 82]
 [:product-page-1 82.85120663636485 48]]

;; So, how are we doing compared to our desired results?

(->> (event-stream initial-model user-arrivals)
     (take 10000)
     (map :state)
     frequencies)

;; =>
{:home-page 2603,
 :product-page-1 5824,
 :product-page-2 737,
 :gone 836}

;; Let's get rid of the :gone states since we don't care about those
;; and normalize those numbers to total to one:

(defn mapvals
  [f m]
  (reduce-kv (fn [m* k v] (assoc m* k (f v))) {} m))

(defn normalize
  [m]
  (let [total (reduce + (vals m))]
    (mapvals #(/ % total) m)))

(defn normalized-frequencies
  [model]
  (->> (event-stream model user-arrivals)
     (take 10000)
     (map :state)
     (remove #{:gone})
     frequencies
     normalize
     (mapvals double)))

(normalized-frequencies initial-model)

;; =>
{:home-page 0.2783189230600854,
 :product-page-1 0.6313888584874685,
 :product-page-2 0.0902922184524461}

;; Hmm. That's close to our target, but not within tolerance:

(within-tolerance? target-distribution (normalized-frequencies initial-model))

;; => false

;; So clearly we need a better model. We could go into the model and
;; monkey around with the weights until we get one, but the model is
;; data, and we can write functions that mess with it for us instead.
;;
;; Why don't we just randomly change all the weights in the range
;; [-1.0 1.0]? Of course, we don't let the weights get less than zero.

(defn perturb-model
  [model]
  (ev/walk-model
   (fn [model outbound inbound transition]
     (let [change (-> (dg/double) (* 2) (- 1))]
       (update-in transition [:weight] #(max 0 (+ % change)))))
   model))

;; In fact, we can easily create an infinite sequence of models, where
;; each subsequent model is a perturbation of the old one:

(def models (iterate perturb-model initial-model))

(nth models 20)

;; =>
{:graph
 {:product-page-1
  [{:product-page-1 {:weight 3.2712337641594824, :delay [:rand-exp 1]},
    :product-page-2 {:weight 2.9911439846916426, :delay [:rand-exp 1]},
    :home-page {:weight 3.1063473164538404, :delay [:rand-exp 1]},
    :gone {:weight 4.27274504551622}}],
  :product-page-2
  [{:product-page-1 {:weight 4.846772104131773, :delay [:rand-exp 1]},
    :product-page-2
    {:weight 0.34973658415402586, :delay [:rand-exp 1]},
    :home-page {:weight 2.8970832928049166, :delay [:rand-exp 1]},
    :gone {:weight 3.8695133417918637}}],
  :home-page
  [{:product-page-1 {:weight 3.328242308481987, :delay [:rand-exp 1]},
    :product-page-2 {:weight 2.009888774976096, :delay [:rand-exp 1]},
    :home-page {:weight 4.360578104902075, :delay [:rand-exp 1]},
    :gone {:weight 0.06140099375574648}}]},}

;; And we can search for one that meets our requirements, limiting the
;; search to the first 100 models in case we don't find one:

(->> models
     (take 100)
     (filter #(within-tolerance? target-distribution (normalized-frequencies %)))
     first
     time)

;; =>
;; "Elapsed time: 17130.341 msecs"
;; nil

;; Bummer: we didn't find anything. Although the search is random, so
;; we might have gotten lucky. Still, we need a better strategy than
;; just hoping we'll find a good model randomly.

;; One thing we could try is to develop a "fitness" metric for a
;; model, and insist that each subsequent model be better than the
;; last by this metric. Let's try that, using a fitness metric that's
;; one minus the sum of the squared difference between the observed
;; frequencies and the ones we're after.

(defn deltas
  [model target-distribution]
  (let [target-frequencies (->> target-distribution
                                (map (fn [[k [freq _]]] [k freq]))
                                (into {}))
        model-frequencies  (->> (event-stream model user-arrivals)
                                (take 10000)
                                (map :state)
                                (remove #{:gone})
                                frequencies
                                normalize)]
    (merge-with - target-frequencies model-frequencies)))


(defn fitness
  [model target]
  (->> (deltas model target)
       vals
       (map #(* % %))
       (reduce +)
       (- 1.0)))

(->> models
     (take 5)
     (map #(fitness % target-distribution)))

;; =>
(0.9904594315816784
 0.9997128871616449
 0.9959990720767672
 0.9849809694043083
 0.9821456849381306)

;; Looks promising! Some of them were better than the original, some
;; worse. That's okay, because we can go through the stream, filtering
;; it to give us models in increasing order of fitness. Which is
;; exactly what `causatum.evolution/model-stream` does. Let's use it
;; to to generate an ever-improving steam of models, which we can walk
;; through until we find one that fits our termination criteria.

(->> (ev/model-stream initial-model
                      #(fitness % target-distribution)
                      (fn [model round]
                        (println "Generating in round" round)
                        (perturb-model model)))
     (filter #(within-tolerance? target-distribution
                                 (normalized-frequencies %)))
     first)

;; Hmm. While that does eventually find an answer (at least when I ran
;; it - randomness is involved, so there are no guarantees) it takes
;; quite a while. Part of the problem here is that we're always
;; perturbing the model by the same amount in every round. But if we
;; have a model that's getting better and better every time,
;; presumably we don't need to change it as much to find one that's
;; closer to the right answer. Indeed, by generating a new model
;; that's a lot different, we're actually *less* likely to find better
;; answers as the rounds go on. And we can see that from the printlns
;; showing that each round takes longer than the last one.

;; Note, however, that the round number is passed to the generator
;; function we give to model-stream. If we write a slightly modified
;; version of perturb-model, we can decrease the perturbation with
;; each round:

(defn perturb-model-variable
  [model scale]
  (ev/walk-model
   (fn [model outbound inbound transition]
     (let [change (-> (dg/double) (* 2) (- 1) (* scale))]
       (update-in transition [:weight] #(max 0 (+ % change)))))
   model))

;; Now let's try again:

(->> (ev/model-stream initial-model
                      #(fitness % target-distribution)
                      (fn [model round]
                        (let [scale (Math/pow 0.9 round)]
                          (println "Generating in round" round "with scale" scale)
                          (perturb-model-variable model scale))))
     (filter #(within-tolerance? target-distribution
                                 (normalized-frequencies %)))
     first)

;; I'm not going to copy the output because it's sort of verbose, but
;; if you run this yourself you'll see that it *much* more quickly
;; moves on to higher numbered rounds. Which means that it's finding
;; better models more quickly, which is what we want!

;; Also: yay! The computer did all the work of finding a model that
;; meets our criteria!

;; This technique of adding lots of randomness to the search at first
;; and less as you go on is known as "simulated annealing". The
;; implementation shown here is pretty naive, and probably overkill
;; for a model witih only a few variables, but the model-stream
;; function is flexible enough to let you try significantly more
;; sophisticated strategies.

;; I hope that was at least somewhat illuminating. Contact me through
;; the project page if you have any questions.
