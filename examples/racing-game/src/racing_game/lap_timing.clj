(ns racing-game.lap-timing
  "Race-clock timing. Never estimates a lap from speed or changes progression."
  (:require [aguafria.keyword :as ak]
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
    (when (and running (ak/! (a/field previous terminal)))
      (when (ak/! (a/field result started))
        (ak/= (a/field result started) true)
        (ak/= (a/field result start_tick) tick)
        (ak/= (a/field result lap) lap))
      (when (>= tick (a/field result observed_tick))
        (when (ak/!= lap (a/field result lap))
          (let [elapsed (- tick (a/field result start_tick))
                sequential (ak/== lap (+ (a/field result lap) 1))]
            (when (and sequential (a/field result complete_start) (> elapsed 0))
              (ak/= (a/field result last_ticks) elapsed)
              (ak/= (a/field result best_ticks)
                    (if (ak/== (a/field result samples) 0) elapsed
                      (ak/min elapsed (a/field result best_ticks))))
              (ak/= (a/field result samples) (+ (a/field result samples) 1)))
            (ak/= (a/field result complete_start) sequential)
            (ak/= (a/field result start_tick) tick)
            (ak/= (a/field result lap) lap)))
        (ak/= (a/field result observed_tick) tick)
        (ak/= (a/field result terminal) terminal)))
    result))
