(ns user
  "Functions to support development. Not used by releases."
  (:require [causatum.event-streams :refer :all]
            [causatum.evolution :as ev]
            [clojure.data.generators :as dg]
            [clojure.repl :refer :all]
            [clojure.tools.namespace.repl :refer [refresh]]))

(defn agenda-parts
  [model agenda]
  (let [{:keys [event-stream future-events]} agenda
        head-rtime (min (-> future-events ffirst (or Double/POSITIVE_INFINITY))
                        (-> event-stream first :rtime (or Double/POSITIVE_INFINITY)))
        [event-stream-head event-stream-tail] (split-with #(<= (:rtime %) head-rtime)
                                                          event-stream)
        future-events* (merge-event-stream future-events event-stream-head)
        [now-rtime now-events] (first future-events*)
        new-events (mapcat #(generate model % now-rtime) now-events)
        rest-future-events (dissoc future-events* now-rtime)
        updated-future-events (merge-event-stream rest-future-events new-events)
        updated-future-events* (if (seq updated-future-events)
                                 updated-future-events
                                 {(:rtime (first event-stream)) [(first event-stream)]})]
    {:event-stream      (concat (take 3 event-stream) ['...])
     :future-events     future-events
     :head-rtime        head-rtime
     :event-stream-head event-stream-head
     :event-stream-tail (concat (take 3 event-stream-tail) ['...])
     :future-events*    future-events*
     :now-rtime         now-rtime
     :now-events        now-events
     :new-events new-events
     :rest-future-events rest-future-events
     :updated-future-events updated-future-events
     :updated-future-events* updated-future-events*}))

