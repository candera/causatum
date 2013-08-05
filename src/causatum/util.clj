(ns causatum.util
  "The usual dumping ground for stuff I'm not clever enough to think
  of a better home for."
  (:require [clojure.data.generators :as dg]))

;;; Things that should live in clojure.data.generators

(defn rand-exp
  "Return an exponentially-distributed random number with an expected
  value of lambda. Respects the binding of
  clojure.data.generators/*rnd*."
  [lambda]
  (- (* (Math/log (dg/double)) lambda)))
