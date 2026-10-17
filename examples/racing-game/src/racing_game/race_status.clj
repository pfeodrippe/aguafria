(ns racing-game.race-status
  "Race classification is separate from body pose and crossing the finish line."
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as ak :refer :all]
            [aguafria.zig :as a]))

(a/defconst reason-none :u8 0)

(a/defconst reason-overturned :u8 1)

(a/defconst reason-outside-world :u8 2)

(a/defconst retirement-delay-ticks :u32 600)

(a/defstruct Entry {:layout :extern}
             [[:retired :bool] [:reason :u8] [:invalid_ticks :u32]
              [:retired_tick :u64] [:lap :u16] [:progress :f32]])

(a/defn observe Entry
  "At 120Hz, retire after five continuous seconds upside-down below 5m/s.
  A transient roll, airborne car, ordinary stop or stationary pit service does
  not qualify. Retirement freezes classification, never the physical body." [[entry Entry] [up :f32] [speed :f32] [tick :u64]
                                                                             [lap :u16] [progress :f32] [finished :bool]]
  (let [^:var result entry]
    (when (and (! (a/field entry retired)) (! finished))
      (= (a/field result invalid_ticks)
         (if (and (< up 0.2) (< speed 5.0))
           (+ (a/field entry invalid_ticks) 1)
           0))
      (when (>= (a/field result invalid_ticks) retirement-delay-ticks)
        (= (a/field result retired) true)
        (= (a/field result reason) reason-overturned)
        (= (a/field result retired_tick) tick)
        (= (a/field result lap) lap)
        (= (a/field result progress) progress)))
    result))

(a/defn observe-world-position Entry
  "Retire a car irrecoverably below the entire supported course, not an
  ordinary airborne car or one stopped on grass. No body is moved or deleted.
  The 50m margin is deliberately below all terrain, not a local slope test." [[entry Entry] [up :f32] [speed :f32] [z :f32] [minimum-elevation :f32]
                                                                              [tick :u64] [lap :u16] [progress :f32] [finished :bool]]
  (let [^:var result (observe entry up speed tick lap progress finished)]
    (when (and (! finished) (! (a/field result retired))
               (< z (- minimum-elevation 50.0)))
      (= (a/field result retired) true)
      (= (a/field result reason) reason-outside-world)
      (= (a/field result retired_tick) tick)
      (= (a/field result lap) lap)
      (= (a/field result progress) progress))
    result))
