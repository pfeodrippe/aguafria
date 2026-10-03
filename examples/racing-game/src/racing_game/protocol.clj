(ns racing-game.protocol
  "Single source of truth for semantic prompts, actions, and provenance."
  (:require [aguafria.zig :as a]
            [aguafria.keyword :as ak]
            [aguafria.std.mem :as mem]))

(a/defconst drivers-per-team :usize 2)

(a/defconst team-count :usize 10)

(a/defconst racer-count :usize (* team-count drivers-per-team))

(a/defconst actor-count :usize (+ racer-count team-count))

(a/defconst observation-schema-version :u8 4)

(a/defconst action-schema-version :u8 1)

(a/defconst model-fingerprint :u64 0x7adb3d5765ad12b8)

(a/defconst action-head-fingerprint :u64 0xf73458570f802041)

(a/defconst action-head-training-revision :u32 7)

(a/defconst team-head-fingerprint :u64 0xe6cbd7efed326bae)

(a/defconst team-head-training-revision :u32 2)

(a/defconst team-training-data-fingerprint :u64 0xef1a7bb27f02431c)

(a/defconst tokenizer-version :u16 2)

(a/defconst quantization-version :u16 1)

(a/defconst quantization-format :u8 2)

(a/defconst training-data-fingerprint :u64 0x4a3c38c8723716e1)

(a/defconst training-data-sha256 [:array 32 :u8]
  (a/init [0x4a 0x3c 0x38 0xc8 0x72 0x37 0x16 0xe1
    0x0a 0xcd 0xc9 0x90 0xd2 0x40 0x17 0x0a
    0xdf 0x24 0x69 0x22 0xcc 0xac 0xf9 0x05
    0xcb 0xae 0x56 0xa4 0x65 0xe3 0x7b 0x4f] [:array 32 :u8]))

(a/defconst replay-golden-ticks :u32 1200)

(a/defconst replay-golden-intent-count :u16 301)

(a/defconst replay-golden-fingerprint :u64 0xaaf601ce97cfb964)

;; Native discriminants are internal implementation details. Model messages
;; use the ordinary words documented by driving-plan-name, never these numbers.
(a/defconst plan-invalid :u8 0)

(a/defconst plan-hold :u8 1)

(a/defconst plan-follow :u8 2)

(a/defconst plan-pass-left :u8 3)

(a/defconst plan-pass-right :u8 4)

(a/defconst plan-pit :u8 5)

(a/defconst plan-yield :u8 6)

(a/defconst plan-ok :u8 0)

(a/defconst plan-malformed :u8 1)

(a/defconst plan-incomplete :u8 2)

(a/defconst plan-wrong-epoch :u8 3)

(a/defconst plan-old-revision :u8 4)

(a/defconst plan-expired :u8 5)

(a/defconst plan-left-blocked :u8 6)

(a/defconst plan-right-blocked :u8 7)

(a/defconst plan-red-flag :u8 8)

(a/defconst plan-no-overtaking :u8 9)

(a/defconst plan-pit-unavailable :u8 10)

(a/defconst plan-inactive-driver :u8 11)

(a/defstruct DrivingPlan
  "Parsed command plus offsets into the UNCHANGED generated radio text."
  {:layout :extern}
  [[:valid :bool] [:kind :u8] [:rejection :u8]
   [:radio_start :u16] [:radio_length :u16]])

(a/defstruct DrivingPlanContext
  "Authoritative state at installation, never values supplied by model text."
  {:layout :extern}
  [[:epoch :u64] [:tick :u64] [:latest_revision :u64]
   [:active :bool] [:left_clear :bool] [:right_clear :bool]
   [:red_flag :bool] [:overtaking_allowed :bool] [:pit_available :bool]])

(a/defn driving-plan-name [:slice-const :u8] [[kind :u8]]
  (cond
    (ak/== kind plan-hold) "hold"
    (ak/== kind plan-follow) "follow"
    (ak/== kind plan-pass-left) "pass left"
    (ak/== kind plan-pass-right) "pass right"
    (ak/== kind plan-pit) "pit"
    (ak/== kind plan-yield) "yield"
    :else "invalid"))

(a/defn driving-plan-rejection [:slice-const :u8] [[reason :u8]]
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

(a/defstruct DrivingObservation
  "Measured physical situation, not a tactical label supplied by the model.
  Clearance is a bounded prediction, never a collision-free guarantee."
  {:layout :extern}
  [[:valid :bool] [:racer :u8] [:speed_kmh :f32]
   [:off_track :bool] [:wrong_way :bool] [:overturned :bool]
   [:ahead_clear :bool] [:left_clear :bool] [:right_clear :bool]
   [:tire_percent :f32] [:damage_percent :f32]
   [:pit_available :bool] [:active :bool]])

(a/defn describe-driving-observation :usize
  "Plain English shared by the inference request and its readable log.
  Return zero on invalid input or insufficient space; never send a truncated
  safety observation. The caller owns the buffer, with no allocation here."
  [[observation DrivingObservation]
   [output [:c-pointer :u8]] [capacity :usize]]
  (when (or (ak/! (a/field observation valid)) (ak/== output ak/null))
    (ak/return 0))
  (let [text
        (catch
          (mem/print (a/slice output 0 capacity)
            "R{d}: {d:.0} km/h. {s}; {s}; {s}; {s}. Ahead {s}; left {s}; right {s}. Tires {d:.0}%, damage {d:.0}%. Pit {s}."
            [(a/field observation racer) (a/field observation speed_kmh)
             (if (a/field observation active) "Racing" "Inactive")
             (if (a/field observation off_track) "off track" "on track")
             (if (a/field observation wrong_way) "wrong way" "facing forward")
             (if (a/field observation overturned) "overturned" "upright")
             (if (a/field observation ahead_clear) "clear" "blocked")
             (if (a/field observation left_clear) "clear" "blocked")
             (if (a/field observation right_clear) "clear" "blocked")
             (a/field observation tire_percent) (a/field observation damage_percent)
             (if (a/field observation pit_available) "available" "unavailable")])
          (ak/return 0))]
    (a/field text len)))

(a/defn- same-plan-word? :bool [[text [:slice-const :u8]] [expected [:slice-const :u8]]]
  (when (ak/!= (a/field text len) (a/field expected len)) (ak/return false))
  (dotimes [i (a/field text len)]
    (let [byte (a/index text i)
          lower (if (and (>= byte 65) (<= byte 90)) (+ byte 32) byte)]
      (when (ak/!= lower (a/index expected i)) (ak/return false))))
  true)

(a/defn parse-driving-plan DrivingPlan
  "Parse one ordinary plan, optionally labelled Plan: and followed by Radio:.
  A complete short command is useful without invented dialogue; an absent
  radio line is represented by zero radio_length, not a synthetic message.
  No substring guessing, negation guessing, silent truncation or encoded head.
  `complete` must reflect the generator's actual successful end-of-message."
  [[bytes [:* {:size :c :const? true} :u8]] [length :usize] [complete :bool]]
  (let [^:var result (DrivingPlan {:valid false :kind plan-invalid
                                  :rejection plan-malformed :radio_start 0 :radio_length 0})]
    (when (or (ak/! complete) (> length 2048))
      (ak/= (a/field result rejection) plan-incomplete)
      (ak/return result))
    (when (ak/== length 0) (ak/return result))
    (let [text (mem/trim (a/type :u8) (a/slice bytes 0 length) " \t\r\n")
          ^:var end (a/field text len)]
      (dotimes [i (a/field text len)]
        (when (and (ak/== end (a/field text len)) (ak/== (a/index text i) 10))
          (ak/= end i)))
      (let [labelled (and (>= end 5) (same-plan-word? (a/slice text 0 5) "plan:"))
            word (mem/trim (a/type :u8) (a/slice text (if labelled (ak/as 5 :usize) 0) end) " \t\r.")
            ^:var kind (ak/u8 plan-invalid)]
        (dotimes [candidate 6]
          (let [code (ak/as (ak/intCast (+ candidate 1)) :u8)]
            (when (same-plan-word? word (driving-plan-name code)) (ak/= kind code))))
        (when (ak/== kind plan-invalid) (ak/return result))
        (when (ak/== end (a/field text len))
          (ak/return (DrivingPlan {:valid true :kind kind :rejection plan-ok
                                   :radio_start 0 :radio_length 0})))
        (let [tail (mem/trim (a/type :u8) (a/slice text (+ end 1)) " \t\r\n")]
          (when (or (< (a/field tail len) 7)
                    (ak/! (same-plan-word? (a/slice tail 0 6) "radio:")))
            (ak/return result))
          (let [radio (mem/trim (a/type :u8) (a/slice tail 6) " \t\r\n")]
            (when (ak/== (a/field radio len) 0) (ak/return result))
            (ak/= (a/field result valid) true)
            (ak/= (a/field result kind) kind)
            (ak/= (a/field result rejection) plan-ok)
            (ak/= (a/field result radio_start)
                  (ak/intCast (- (ak/intFromPtr (a/field radio ptr)) (ak/intFromPtr bytes))))
            (ak/= (a/field result radio_length) (ak/intCast (a/field radio len)))))))
    result))

(a/defn validate-driving-plan :u8
  "Return a readable-reason discriminant; never install a plan or move a body.
  A valid plan is still subject to continuously measured low-level controls."
  [[plan DrivingPlan] [epoch :u64] [revision :u64]
   [observed-tick :u64] [expires-tick :u64] [context DrivingPlanContext]]
  (cond
    (ak/! (a/field plan valid)) (if (ak/== (a/field plan rejection) plan-ok)
                                  plan-malformed (a/field plan rejection))
    (or (< (a/field plan kind) plan-hold) (> (a/field plan kind) plan-yield)) plan-malformed
    (ak/!= epoch (a/field context epoch)) plan-wrong-epoch
    (<= revision (a/field context latest_revision)) plan-old-revision
    (or (> observed-tick (a/field context tick))
        (<= expires-tick (a/field context tick))
        (<= expires-tick observed-tick)) plan-expired
    (ak/! (a/field context active)) plan-inactive-driver
    (and (a/field context red_flag) (ak/!= (a/field plan kind) plan-hold)) plan-red-flag
    (and (or (ak/== (a/field plan kind) plan-pass-left)
             (ak/== (a/field plan kind) plan-pass-right))
         (ak/! (a/field context overtaking_allowed))) plan-no-overtaking
    (and (ak/== (a/field plan kind) plan-pass-left)
         (ak/! (a/field context left_clear))) plan-left-blocked
    (and (ak/== (a/field plan kind) plan-pass-right)
         (ak/! (a/field context right_clear))) plan-right-blocked
    (and (ak/== (a/field plan kind) plan-pit)
         (ak/! (a/field context pit_available))) plan-pit-unavailable
    :else plan-ok))
