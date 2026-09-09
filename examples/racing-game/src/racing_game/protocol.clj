(ns racing-game.protocol
  "Single source of truth for semantic prompts, actions, and provenance."
  (:require [aguafria.zig :as az]
            [aguafria.keyword :as ak]
            [aguafria.std.fmt :as fmt]
            [aguafria.std.mem :as mem]))

(az/defconst drivers-per-team :usize 2)

(az/defconst team-count :usize 10)

(az/defconst racer-count :usize (* team-count drivers-per-team))

(az/defconst actor-count :usize (+ racer-count team-count))

(az/defconst observation-schema-version :u8 4)

(az/defconst action-schema-version :u8 1)

(az/defconst model-fingerprint :u64 0x7adb3d5765ad12b8)

(az/defconst action-head-fingerprint :u64 0xf73458570f802041)

(az/defconst action-head-training-revision :u32 7)

(az/defconst team-head-fingerprint :u64 0xe6cbd7efed326bae)

(az/defconst team-head-training-revision :u32 2)

(az/defconst team-training-data-fingerprint :u64 0xef1a7bb27f02431c)

(az/defconst tokenizer-version :u16 2)

(az/defconst quantization-version :u16 1)

(az/defconst quantization-format :u8 2)

(az/defconst training-data-fingerprint :u64 0x4a3c38c8723716e1)

(az/defconst training-data-sha256 [:array 32 :u8]
  (az/array-init
   [:array 32 :u8]
   [0x4a 0x3c 0x38 0xc8 0x72 0x37 0x16 0xe1
    0x0a 0xcd 0xc9 0x90 0xd2 0x40 0x17 0x0a
    0xdf 0x24 0x69 0x22 0xcc 0xac 0xf9 0x05
    0xcb 0xae 0x56 0xa4 0x65 0xe3 0x7b 0x4f]))

(az/defconst replay-golden-ticks :u32 1200)

(az/defconst replay-golden-intent-count :u16 301)

(az/defconst replay-golden-fingerprint :u64 0xaaf601ce97cfb964)

;; Native discriminants are internal implementation details. Model messages
;; use the ordinary words documented by driving-plan-name, never these numbers.
(az/defconst plan-invalid :u8 0)

(az/defconst plan-hold :u8 1)

(az/defconst plan-follow :u8 2)

(az/defconst plan-pass-left :u8 3)

(az/defconst plan-pass-right :u8 4)

(az/defconst plan-pit :u8 5)

(az/defconst plan-yield :u8 6)

(az/defconst plan-ok :u8 0)

(az/defconst plan-malformed :u8 1)

(az/defconst plan-incomplete :u8 2)

(az/defconst plan-wrong-epoch :u8 3)

(az/defconst plan-old-revision :u8 4)

(az/defconst plan-expired :u8 5)

(az/defconst plan-left-blocked :u8 6)

(az/defconst plan-right-blocked :u8 7)

(az/defconst plan-red-flag :u8 8)

(az/defconst plan-no-overtaking :u8 9)

(az/defconst plan-pit-unavailable :u8 10)

(az/defconst plan-inactive-driver :u8 11)

(az/defstruct DrivingPlan
  "Parsed command plus offsets into the UNCHANGED generated radio text."
  {:layout :extern}
  [[:valid :bool] [:kind :u8] [:rejection :u8]
   [:radio_start :u16] [:radio_length :u16]])

(az/defstruct DrivingPlanContext
  "Authoritative state at installation, never values supplied by model text."
  {:layout :extern}
  [[:epoch :u64] [:tick :u64] [:latest_revision :u64]
   [:active :bool] [:left_clear :bool] [:right_clear :bool]
   [:red_flag :bool] [:overtaking_allowed :bool] [:pit_available :bool]])

(az/defn driving-plan-name :- [:slice-const :u8] [[kind :u8]]
  (cond
    (ak/== kind plan-hold) "hold"
    (ak/== kind plan-follow) "follow"
    (ak/== kind plan-pass-left) "pass left"
    (ak/== kind plan-pass-right) "pass right"
    (ak/== kind plan-pit) "pit"
    (ak/== kind plan-yield) "yield"
    :else "invalid"))

(az/defn driving-plan-rejection :- [:slice-const :u8] [[reason :u8]]
  (cond
    (ak/== reason plan-ok) "accepted"
    (ak/== reason plan-malformed) "unrecognized or ambiguous driving command"
    (ak/== reason plan-incomplete) "model response was incomplete or exceeded the limit"
    (ak/== reason plan-wrong-epoch) "reply belongs to another race"
    (ak/== reason plan-old-revision) "reply was superseded"
    (ak/== reason plan-expired) "observation or reply expired"
    (ak/== reason plan-left-blocked) "left lane is obstructed"
    (ak/== reason plan-right-blocked) "right lane is obstructed"
    (ak/== reason plan-red-flag) "red flag requires holding position"
    (ak/== reason plan-no-overtaking) "overtaking is currently prohibited"
    (ak/== reason plan-pit-unavailable) "pit entry or team box is unavailable"
    (ak/== reason plan-inactive-driver) "driver is not active"
    :else "unknown rejection"))

(az/defstruct DrivingObservation
  "Measured physical situation, not a tactical label supplied by the model.
  Clearance is a bounded prediction, never a collision-free guarantee."
  {:layout :extern}
  [[:valid :bool] [:racer :u8] [:speed_kmh :f32]
   [:off_track :bool] [:wrong_way :bool] [:overturned :bool]
   [:ahead_clear :bool] [:left_clear :bool] [:right_clear :bool]
   [:tire_percent :f32] [:damage_percent :f32]
   [:pit_available :bool] [:active :bool]])

(az/defn describe-driving-observation
  "Plain English shared by the inference request and its readable log.
  Return zero on invalid input or insufficient space; never send a truncated
  safety observation. The caller owns the buffer, with no allocation here."
  :- :usize
  [[observation DrivingObservation]
   [output [:c-pointer :u8]] [capacity :usize]]
  (when (or (ak/! (az/field observation valid)) (ak/== output ak/null))
    (ak/return 0))
  (let [text
        (catch
          (fmt/bufPrint (az/slice output 0 capacity)
            "R{d}: {d:.0} km/h. {s}; {s}; {s}; {s}. Ahead {s}; left {s}; right {s}. Tires {d:.0}%, damage {d:.0}%. Pit {s}."
            [(az/field observation racer) (az/field observation speed_kmh)
             (if (az/field observation active) "Racing" "Inactive")
             (if (az/field observation off_track) "off track" "on track")
             (if (az/field observation wrong_way) "wrong way" "facing forward")
             (if (az/field observation overturned) "overturned" "upright")
             (if (az/field observation ahead_clear) "clear" "blocked")
             (if (az/field observation left_clear) "clear" "blocked")
             (if (az/field observation right_clear) "clear" "blocked")
             (az/field observation tire_percent) (az/field observation damage_percent)
             (if (az/field observation pit_available) "available" "unavailable")])
          (ak/return 0))]
    (az/field text len)))

(az/defn- same-plan-word?
  :- :bool [[text [:slice-const :u8]] [expected [:slice-const :u8]]]
  (when (ak/!= (az/field text len) (az/field expected len)) (ak/return false))
  (dotimes [i (az/field text len)]
    (let [byte (az/index text i)
          lower (if (and (>= byte 65) (<= byte 90)) (+ byte 32) byte)]
      (when (ak/!= lower (az/index expected i)) (ak/return false))))
  true)

(az/defn parse-driving-plan
  "Parse one ordinary plan, optionally labelled Plan: and followed by Radio:.
  A complete short command is useful without invented dialogue; an absent
  radio line is represented by zero radio_length, not a synthetic message.
  No substring guessing, negation guessing, silent truncation or encoded head.
  `complete` must reflect the generator's actual successful end-of-message."
  :- DrivingPlan
  [[bytes [:pointer {:size :c :const? true} :u8]] [length :usize] [complete :bool]]
  (let [^:var result (DrivingPlan {:valid false :kind plan-invalid
                                  :rejection plan-malformed :radio_start 0 :radio_length 0})]
    (when (or (ak/! complete) (> length 2048))
      (set! (az/field result rejection) plan-incomplete)
      (ak/return result))
    (when (ak/== length 0) (ak/return result))
    (let [text (mem/trim (az/type :u8) (az/slice bytes 0 length) " \t\r\n")
          ^:var end (az/field text len)]
      (dotimes [i (az/field text len)]
        (when (and (ak/== end (az/field text len)) (ak/== (az/index text i) 10))
          (set! end i)))
      (let [labelled (and (>= end 5) (same-plan-word? (az/slice text 0 5) "plan:"))
            word (mem/trim (az/type :u8) (az/slice text (if labelled (ak/as :usize 5) 0) end) " \t\r.")
            ^{:var :u8} kind plan-invalid]
        (dotimes [candidate 6]
          (let [code (ak/as :u8 (ak/intCast (+ candidate 1)))]
            (when (same-plan-word? word (driving-plan-name code)) (set! kind code))))
        (when (ak/== kind plan-invalid) (ak/return result))
        (when (ak/== end (az/field text len))
          (ak/return (DrivingPlan {:valid true :kind kind :rejection plan-ok
                                   :radio_start 0 :radio_length 0})))
        (let [tail (mem/trim (az/type :u8) (az/slice text (+ end 1)) " \t\r\n")]
          (when (or (< (az/field tail len) 7)
                    (ak/! (same-plan-word? (az/slice tail 0 6) "radio:")))
            (ak/return result))
          (let [radio (mem/trim (az/type :u8) (az/slice tail 6) " \t\r\n")]
            (when (ak/== (az/field radio len) 0) (ak/return result))
            (set! (az/field result valid) true)
            (set! (az/field result kind) kind)
            (set! (az/field result rejection) plan-ok)
            (set! (az/field result radio_start)
                  (ak/intCast (- (ak/intFromPtr (az/field radio ptr)) (ak/intFromPtr bytes))))
            (set! (az/field result radio_length) (ak/intCast (az/field radio len)))))))
    result))

(az/defn validate-driving-plan
  "Return a readable-reason discriminant; never install a plan or move a body.
  A valid plan is still subject to continuously measured low-level controls."
  :- :u8
  [[plan DrivingPlan] [epoch :u64] [revision :u64]
   [observed-tick :u64] [expires-tick :u64] [context DrivingPlanContext]]
  (cond
    (ak/! (az/field plan valid)) (if (ak/== (az/field plan rejection) plan-ok)
                                  plan-malformed (az/field plan rejection))
    (or (< (az/field plan kind) plan-hold) (> (az/field plan kind) plan-yield)) plan-malformed
    (ak/!= epoch (az/field context epoch)) plan-wrong-epoch
    (<= revision (az/field context latest_revision)) plan-old-revision
    (or (> observed-tick (az/field context tick))
        (<= expires-tick (az/field context tick))
        (<= expires-tick observed-tick)) plan-expired
    (ak/! (az/field context active)) plan-inactive-driver
    (and (az/field context red_flag) (ak/!= (az/field plan kind) plan-hold)) plan-red-flag
    (and (or (ak/== (az/field plan kind) plan-pass-left)
             (ak/== (az/field plan kind) plan-pass-right))
         (ak/! (az/field context overtaking_allowed))) plan-no-overtaking
    (and (ak/== (az/field plan kind) plan-pass-left)
         (ak/! (az/field context left_clear))) plan-left-blocked
    (and (ak/== (az/field plan kind) plan-pass-right)
         (ak/! (az/field context right_clear))) plan-right-blocked
    (and (ak/== (az/field plan kind) plan-pit)
         (ak/! (az/field context pit_available))) plan-pit-unavailable
    :else plan-ok))
