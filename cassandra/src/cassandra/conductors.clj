(ns cassandra.conductors
  (:require [cassandra.core :as cassandra]
            [clojure.set :as set]
            [clojure.tools.logging :refer :all]
            [jepsen
             [control :as c]
             [generator :as gen]
             [nemesis :as nemesis]]
            [jepsen.nemesis.time :as nt]))

(defn bootstrapper
  []
  (reify nemesis/Nemesis
    (setup! [this test] this)
    (invoke! [this test op]
      (let [decommissioned (:decommissioned test)]
        (if-let [node (first @decommissioned)]
          (do (info node "starting bootstrapping")
              (swap! decommissioned (comp set rest))
              (c/on node (cassandra/start! node test))
              (while (some #{cassandra/dns-resolve (name node)}
                           (cassandra/joining-nodes test))
                (info node "still joining")
                (Thread/sleep 1000))
              (assoc op :value (str node " bootstrapped")))
          (assoc op :value "no nodes left to bootstrap"))))
    (teardown! [this test] this)))

(defn decommissioner
  []
  (reify nemesis/Nemesis
    (setup! [this test] this)
    (invoke! [this test op]
      (let [decommissioned (:decommissioned test)]
        (if-let [node (some-> test
                              cassandra/live-nodes
                              (set/difference @decommissioned)
                              shuffle
                              (get (:rf test)))] ; keep at least RF nodes
          (do (info node "decommissioning")
              (info @decommissioned "already decommissioned")
              (swap! decommissioned conj node)
              (cassandra/nodetool node "decommission")
              (assoc op :value (str node " decommissioned")))
          (assoc op :value "no nodes eligible for decommission"))))
    (teardown! [this test] this)))

(defn replayer
  []
  (reify nemesis/Nemesis
    (setup! [this test] this)
    (invoke! [this test op]
      (let [live-nodes (cassandra/live-nodes test)]
        (doseq [node live-nodes]
          (cassandra/nodetool node "replaybatchlog"))
        (assoc op :value (str live-nodes " batch logs replayed"))))
    (teardown! [this test] this)))

(defn flush-and-compacter
  "Flushes to sstables and forces a major compaction on all nodes"
  []
  (reify nemesis/Nemesis
    (setup! [this test] this)
    (invoke! [this test op]
      (case (:f op)
        :start (do (doseq [node (:nodes test)]
                     (cassandra/nodetool node "flush")
                     (cassandra/nodetool node "compact"))
                   (assoc op :value (str (:nodes test) " nodes flushed and compacted")))
        :stop (assoc op :value "stop is a no-op with this nemesis")))
    (teardown! [this test] this)))

(defn mix-failure
  "Make a seq with start and stop for nemesis failure, and mix bootstrapping and decommissioning"
  [opts]
  (let [decop  (when (:decommission (:join opts))
                 {:type :info :f :decommission})
        bootop (when (:bootstrap (:join opts))
                 {:type :info :f :bootstrap})
        reset  (when (:bump (:clock opts))
                 (nt/reset-gen opts nil))
        bump   (when (:bump (:clock opts))
                 (nt/bump-gen opts nil))
        strobe (when (:strobe (:clock opts))
                 (nt/strobe-gen opts nil))

        ops (remove nil? [decop bootop reset bump strobe])

        base [(gen/sleep (+ (rand-int 30) 60))
              {:type :info :f :start}
              (gen/sleep (+ (rand-int 30) 60))
              {:type :info :f :stop}]]
    (if-let [op (-> ops not-empty rand-nth)]
      (conj base (gen/sleep (+ (rand-int 30) 60)) op)
      base)))

(defn mix-failure-seq
  [opts]
  (gen/seq (flatten (repeatedly #(mix-failure opts)))))

(defn terminate-nemesis
  [opts]
  (if (or (:bump (:clock opts)) (:strobe (:clock opts)))
    (gen/nemesis (gen/seq [(gen/once {:type :info :f :stop})
                           (gen/once (nt/reset-gen opts nil))]))
    (gen/nemesis (gen/once {:type :info :f :stop}))))

(defn std-gen
  [opts gen]
  (->> gen
       gen/mix
       (gen/nemesis
         (mix-failure-seq opts))
       (gen/time-limit (:time-limit opts))))
