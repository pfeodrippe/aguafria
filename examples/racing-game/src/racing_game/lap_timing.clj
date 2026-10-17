(ns racing-game.lap-timing
  "Race-clock timing. Never estimates a lap from speed or changes progression."
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as ak :refer :all]
            [aguafria.zig :as a]))

(a/defstruct Entry {:layout :extern}
             [[:started :bool] [:complete_start :bool] [:terminal :bool]
              [:lap :u16] [:samples :u32]
              [:start_tick :u64] [:observed_tick :u64]
              [:last_ticks :u64] [:best_ticks :u64]])

(a/defn observe Entry
  "One observation per simulation tick, after progression/classification.
  Countdown and pauses do not advance the race clock. A timer attached during
  a race discards its first partial lap; the next crossing starts a full one.
  Pits, slow traffic and incidents remain included, not subtracted away." [[previous Entry] [tick :u64] [lap :u16]
                                                                           [running :bool] [terminal :bool]]
  (let [^:var result previous]
    (when (and running (! (a/field previous terminal)))
      (when (! (a/field result started))
        (= (a/field result started) true)
        (= (a/field result start_tick) tick)
        (= (a/field result lap) lap))
      (when (>= tick (a/field result observed_tick))
        (when (!= lap (a/field result lap))
          (let [elapsed (- tick (a/field result start_tick))
                sequential (== lap (+ (a/field result lap) 1))]
            (when (and sequential (a/field result complete_start) (> elapsed 0))
              (= (a/field result last_ticks) elapsed)
              (= (a/field result best_ticks)
                 (if (== (a/field result samples) 0) elapsed
                     (min elapsed (a/field result best_ticks))))
              (= (a/field result samples) (+ (a/field result samples) 1)))
            (= (a/field result complete_start) sequential)
            (= (a/field result start_tick) tick)
            (= (a/field result lap) lap)))
        (= (a/field result observed_tick) tick)
        (= (a/field result terminal) terminal)))
    result))
