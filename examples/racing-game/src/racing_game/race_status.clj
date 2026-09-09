(ns racing-game.race-status
  "Race classification is separate from body pose and crossing the finish line."
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defconst reason-none :u8 0)

(az/defconst reason-overturned :u8 1)

(az/defconst reason-outside-world :u8 2)

(az/defconst retirement-delay-ticks :u32 600)

(az/defstruct Entry {:layout :extern}
  [[:retired :bool] [:reason :u8] [:invalid_ticks :u32]
   [:retired_tick :u64] [:lap :u16] [:progress :f32]])

(az/defn observe
  "At 120Hz, retire after five continuous seconds upside-down below 5m/s.
  A transient roll, airborne car, ordinary stop or stationary pit service does
  not qualify. Retirement freezes classification, never the physical body."
  :- Entry [[entry Entry] [up :f32] [speed :f32] [tick :u64]
            [lap :u16] [progress :f32] [finished :bool]]
  (let [^:var result entry]
    (when (and (ak/! (az/field entry retired)) (ak/! finished))
      (set! (az/field result invalid_ticks)
            (if (and (< up 0.2) (< speed 5.0))
              (+ (az/field entry invalid_ticks) 1)
              0))
      (when (>= (az/field result invalid_ticks) retirement-delay-ticks)
        (set! (az/field result retired) true)
        (set! (az/field result reason) reason-overturned)
        (set! (az/field result retired_tick) tick)
        (set! (az/field result lap) lap)
        (set! (az/field result progress) progress)))
    result))

(az/defn observe-world-position
  "Retire a car irrecoverably below the entire supported course, not an
  ordinary airborne car or one stopped on grass. No body is moved or deleted.
  The 50m margin is deliberately below all terrain, not a local slope test."
  :- Entry [[entry Entry] [up :f32] [speed :f32] [z :f32] [minimum-elevation :f32]
            [tick :u64] [lap :u16] [progress :f32] [finished :bool]]
  (let [^:var result (observe entry up speed tick lap progress finished)]
    (when (and (ak/! finished) (ak/! (az/field result retired))
               (< z (- minimum-elevation 50.0)))
      (set! (az/field result retired) true)
      (set! (az/field result reason) reason-outside-world)
      (set! (az/field result retired_tick) tick)
      (set! (az/field result lap) lap)
      (set! (az/field result progress) progress))
    result))
