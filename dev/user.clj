(ns user
  "Functions to support development. Not used by releases."
  (:require [causatum.event-streams :refer :all]
            [causatum.evolution :as ev]
            [clojure.data.generators :as dg]
            [clojure.repl :refer :all]
            [clojure.tools.namespace.repl :refer [refresh]]))
