(defproject org.craigandera/causatum "0.3.0"
  :description "A library to stochastically generate streams of events from state machines."
  :url "https://github.com/candera/causatum"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/data.generators "0.1.2"]]
  :profiles
  {:dev {:dependencies [[org.clojure/tools.namespace "0.2.4"]]
         :source-paths ["dev"]}})
