# causatum

A Clojure library designed to generate streams of timed events based
on stochastic state machines.

# Motivation (aka "Huh?")

Imagine that you were asked to model user behavior on a website. You
might need to do this in order to drive some sort of test. One obvious
way to do it would be with a state machine, wherein each page on the
site was a state, and a map described the legal transitions:

```clojure
{:home #{:home :product}
 :product #{:product :home :shopping-cart}}
```

That's somewhat lacking, however, since it captures neither the time
these events occur (important when generating load tests) nor their
relative likelihoods. Something more like this might be better:

```clojure
{:home    {:home          {:weight 1 :delay 10}
           :product       {:weight 2 :delay 20}}
 :product {:product       {:weight 1 :delay 30}
           :home          {:weight 2 :delay 40}
           :shopping-cart {:weight 4 :delay 15}}}
```

Even that isn't quite right, since the delays are fixed, but in the
real world delays vary. Further, there's no real reason that a state
like :home-page can't have more than one follow-on state, for instance
to model an asynchronous event.

causatum attempts to provide a library with exactly those
capabilities.

# Usage

> cau·sa·tum noun \kau̇ˈzätəm, kȯˈzāt-\
> pl causa·ta
>
> : something that is caused : effect

[Merriam-Webster Online Dictionary](http://www.merriam-webster.com/dictionary/causatum)

The main activity performed by causatum is the generation of streams
of _events_. An event is a map with at least `:state` and `:rtime`
keys. A state is usually a keyword, and identifies the type of event
that has occurred. The "r" in "rtime" stands for "relative", and
captures an offset from some arbitrary point in time at which the
event occurred.

Event streams are generated from _models_. A model is concretely a map
with at least a `:graph` key. The graph describes the possible states
and the legal transitions between them, including relative likelihoods
and the distribution of inter-event times. Here is a simple model that
attempts to capture the idea of a user arriving at a website, visiting
the home page, possibly moving on to a product page, and maybe a
shopping cart page. They can go back to previous pages, and they might
leave forever (`:gone`).

```clojure
{:graph {:home    [{:home    {:weight 3
                              :delay [:constant 2]}
                    :product {:weight 1
                              :delay [:constant 3]}
                    :gone    {:weight 10}}]
         :product [{:home    {:weight 1
                              :delay [:random 10]}
                    :cart    {:weight 3
                              :delay [:contant 4]}
                    :gone    {:weight 2}}]
         :cart      [{:home  {:weight 4
                              :delay [:constant 17]}
                      :gone  {:weight 1}}
                     {:process-order {}}]}
 :delay-ops {:constant (fn [rtime n] n)
             :random   (fn [rtime n] (rand n))}}
```

The value of the `:graph` key is a map of outbound states to vectors
of maps. One new event will be generated for each of the maps in those
vectors. So in our example above, `:home-page` events will always
produce one outbound events, but a `:cart` event will produce two
outbound events.

Only one event is chosen (randomly, according to the weights) from
each map of outbound events. Note that if only one state is present in
the map, the `:weight` key is optional. So in our example, exactly one
event of `:home`, `:cart`, or `:gone` will follow an event of type
`:product`.

The delay between events is determined by the `:delay` key. If absent,
it defaults to zero. If present, it must be a sequence whose first
element is present in the `:delay-ops` map of the model. The value for
that entry must be a function that takes at least the rtime of the
precedent event. Any other elements in the delay vector will also be
passed. The delay function returns a number, which is the delay
between the preceding and the new event. Passing the rtime to the
delay function allows for time-varying delays, such as might be
present when modeling a site that has high load during certain times
of the day, week, or year.

The primary function used to generate streams of events is
`causatum.core/event-stream`, which takes both a model and another
event stream. This allows the output of one model to function as the
input of another. This input can come from anywhere, however, and need
not be the return value of another call to `event-stream`. For instance:

```clojure
(def model {:graph {:a [{:b {:weight 1 :delay [:constant 1]}
                         :c {:weight 1}}]
                    :b [{:a {:weight 2 :delay [:constant 2]}
                         :c {:weight 1}}]}
            :delay-ops {:constant (fn [_ x] x)}})

(event-stream model [{:state :a :rtime 0}
                     {:state :b :rtime 1.5}])

;; =>
[{:state :a, :rtime 0}
 {:weight 1, :state :c, :rtime 0}
 {:state :b, :rtime 1.5}
 {:delay [:constant 2], :weight 2, :state :a, :rtime 3.5}
 {:delay [:constant 1], :weight 1, :state :b, :rtime 4.5}
 {:delay [:constant 1], :weight 1, :state :c, :rtime 4.5}]

```

Note that the output events are in increasing order of rtime, and that
they may include some information from the state transition.

Here, we used a simple vector of events to seed the event stream. We
could just have easily used an infinite stream of events:

```clojure
(->> (event-stream model
                   (map (fn [rtime]
                          {:state :a :rtime rtime})
                        (iterate inc 0)))
     (drop 1000)
     (take 5))

;; =>

({:state :a, :rtime 325}
 {:state :a, :rtime 325, :delay [:constant 2], :weight 2}
 {:state :a, :rtime 326}
 {:state :b, :rtime 326, :delay [:constant 1], :weight 1}
 {:state :b, :rtime 326, :delay [:constant 1], :weight 1})


```

There are enormous benefits to representing models as pure data. Not
least among these is that it becomes trivial to serialize and store
them. It also becomes possible to manipulate models using the same
techniques we apply to other data. Functions for manipulating models
to iterate towards a goal are defined in the `causatum.evolution`
namespace. For an interesting example of that code, see
`doc/evolution-example.clj`.

## License

Copyright © 2013 Crag Andera

Distributed under the Eclipse Public License, the same as Clojure.
