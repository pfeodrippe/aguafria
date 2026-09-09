(ns racing-game.lap-timing
  "Race-clock timing. Never estimates a lap from speed or changes progression."
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defstruct Entry {:layout :extern}
  [[:started :bool] [:complete_start :bool] [:terminal :bool]
   [:lap :u16] [:samples :u32]
   [:start_tick :u64] [:observed_tick :u64]
   [:last_ticks :u64] [:best_ticks :u64]])

(az/defn observe
  "One observation per simulation tick, after progression/classification.
  Countdown and pauses do not advance the race clock. A timer attached during
  a race discards its first partial lap; the next crossing starts a full one.
  Pits, slow traffic and incidents remain included, not subtracted away."
  :- Entry [[previous Entry] [tick :u64] [lap :u16]
            [running :bool] [terminal :bool]]
  (let [^:var result previous]
    (when (and running (ak/! (az/field previous terminal)))
      (when (ak/! (az/field result started))
        (set! (az/field result started) true)
        (set! (az/field result start_tick) tick)
        (set! (az/field result lap) lap))
      (when (>= tick (az/field result observed_tick))
        (when (ak/!= lap (az/field result lap))
          (let [elapsed (- tick (az/field result start_tick))
                sequential (ak/== lap (+ (az/field result lap) 1))]
            (when (and sequential (az/field result complete_start) (> elapsed 0))
              (set! (az/field result last_ticks) elapsed)
              (set! (az/field result best_ticks)
                    (if (ak/== (az/field result samples) 0) elapsed
                      (ak/min elapsed (az/field result best_ticks))))
              (set! (az/field result samples) (+ (az/field result samples) 1)))
            (set! (az/field result complete_start) sequential)
            (set! (az/field result start_tick) tick)
            (set! (az/field result lap) lap)))
        (set! (az/field result observed_tick) tick)
        (set! (az/field result terminal) terminal)))
    result))
