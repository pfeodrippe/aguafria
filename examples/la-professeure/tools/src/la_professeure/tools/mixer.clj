(ns la-professeure.tools.mixer
  "Bounded native sample-clock mixer. Configure only while its device is closed."
  (:require [aguafria.std] [aguafria.std.mem :as mem]
            [aguafria.keyword :as ak] [aguafria.zig :as az]
            [la-professeure.tools.recorder :as recorder]))

(az/defconst max-clips :usize 16)
(az/defconst max-source-frames :usize 5760000)
(az/defconst Decoder (az/field recorder/api ma_decoder))
(az/defstruct Clip
  [[base :usize] [frames :u64] [start :u64] [gain :f32] [pan :f32]
   [fade :u64] [mute :bool] [solo :bool]])
(az/defvar clips [:array 16 Clip] (mem/zeroes (az/type [:array 16 Clip])))
(az/defvar samples [:array 11520000 :f32] ak/undefined)
(az/defvar used :usize 0)
(az/defvar clip-count :usize 0)
(az/defvar duration :u64 0)
(az/defvar device recorder/Device ak/undefined)
(az/defvar opened :bool false)
;; Desired transport state belongs to control callers. The callback must never
;; overwrite a newer play/seek command when it reaches the end of a block.
(az/defvar playing :u8 0)
(az/defvar cursor :u64 0)
(az/defvar seek-request :u64 18446744073709551615)
;; One atomic publication prevents the callback observing mismatched loop edges.
;; The bounded timeline fits two u32 frame positions in one u64; zero disables.
(az/defvar loop-region :u64 0)
(az/defstruct Loop {:layout :extern} [[from :u64] [to :u64] [enabled :bool]])
(az/defn loop-state :- Loop []
  (let [region (ak/atomicLoad :u64 (ak/& loop-region) :.acquire)]
    (ak/as Loop {:from (mod region 4294967296) :to (/ region 4294967296) :enabled (ak/!= region 0)})))
(az/defn set-loop! :- :bool [[from :u64] [to :u64] [enabled :bool]]
  (when (and enabled (or (>= from to) (> to duration) (> to 4294967295))) (ak/return false))
  (ak/atomicStore :u64 (ak/& loop-region) (if enabled (+ (* to 4294967296) from) 0) :.release)
  true)
(az/defvar peak :u32 0)
(az/defvar clipped :u8 0)
(az/defvar path-buffer [:array 4096 :u8] ak/undefined)

(az/defn close! :- :void []
  (when opened ((az/field recorder/api ma_device_uninit) (ak/& device)) (set! opened false))
  (ak/atomicStore :u8 (ak/& playing) 0 :.release))

(az/defn reset! :- :void []
  (close!) (set! used 0) (set! clip-count 0) (set! duration 0)
  (ak/atomicStore :u64 (ak/& cursor) 0 :.release)
  (ak/atomicStore :u64 (ak/& seek-request) 18446744073709551615 :.release)
  (ak/atomicStore :u64 (ak/& loop-region) 0 :.release)
  (ak/atomicStore :u32 (ak/& peak) 0 :.release)
  (ak/atomicStore :u8 (ak/& clipped) 0 :.release))

(az/defn configure-clip! :- :bool
  [[index :usize] [start :u64] [gain :f32] [pan :f32] [fade :u64] [mute :bool] [solo :bool]]
  (when (or opened (>= index clip-count) (> start 28800000)
            (ak/! (and (>= gain 0.0) (<= gain 2.0) (>= pan -1.0) (<= pan 1.0)))) (ak/return false))
  (let [clip (ak/& (az/index clips index))]
    (set! (az/field clip start) start) (set! (az/field clip gain) gain) (set! (az/field clip pan) pan)
    (set! (az/field clip fade) (ak/min fade (/ (az/field clip frames) 2)))
    (set! (az/field clip mute) mute) (set! (az/field clip solo) solo))
  (set! duration 0)
  (dotimes [i clip-count]
    (set! duration (ak/max duration (+ (az/field (az/index clips i) start) (az/field (az/index clips i) frames)))))
  ;; Editing the prepared plan invalidates its old playback region.
  (set! _ (set-loop! 0 0 false))
  true)

(az/defn add-file! :- :bool [[path [:slice-const :u8]]]
  (when (or opened (>= clip-count max-clips) (>= (az/field path len) 4096)) (ak/return false))
  (dotimes [i (az/field path len)] (when (ak/== (az/index path i) 0) (ak/return false)))
  (ak/memcpy (az/slice path-buffer 0 (az/field path len)) path)
  (set! (az/index path-buffer (az/field path len)) 0)
  (let [^:var config ((az/field recorder/api ma_decoder_config_init) (az/field recorder/api ma_format_f32) 2 48000)
        ^{:var Decoder} decoder ak/undefined
        ^{:var :u64} frames 0 ^{:var :u64} read 0]
    (when (ak/!= ((az/field recorder/api ma_decoder_init_file) (ak/& path-buffer) (ak/& config) (ak/& decoder)) 0)
      (ak/return false))
    (ak/defer (set! _ ((az/field recorder/api ma_decoder_uninit) (ak/& decoder))))
    (when (or (ak/!= ((az/field recorder/api ma_decoder_get_length_in_pcm_frames) (ak/& decoder) (ak/& frames)) 0)
              (ak/== frames 0) (> frames (- max-source-frames used))) (ak/return false))
    (set! _ ((az/field recorder/api ma_decoder_read_pcm_frames) (ak/& decoder) (ak/& (az/index samples (* used 2))) frames (ak/& read)))
    (when (ak/!= read frames) (ak/return false))
    ;; Reject non-finite PCM before publishing a clip to the callback.
    (dotimes [i (* frames 2)]
      (when (ak/! (< (ak/abs (az/index samples (+ (* used 2) i))) 1000000.0)) (ak/return false)))
    (set! (az/index clips clip-count) (ak/as Clip {:base used :frames frames :start 0 :gain 1.0 :pan 0.0 :fade 240 :mute false :solo false}))
    (set! used (+ used (ak/as :usize (ak/intCast frames)))) (set! clip-count (+ clip-count 1))
    (set! duration (ak/max duration frames)) true))

(az/defn process!
  "The sole audio path, also called by offline tests. No allocation, locks or I/O."
  :- :void [[output [:c-pointer :f32]] [frames :u32]]
  (when (ak/== output ak/null) (ak/return))
  (let [active (ak/!= (ak/atomicLoad :u8 (ak/& playing) :.acquire) 0)
        loop (loop-state)
        requested (ak/atomicRmw :u64 (ak/& seek-request) :.Xchg 18446744073709551615 :.acq_rel)
        ^{:var :u64} frame-position (ak/atomicLoad :u64 (ak/& cursor) :.acquire)
        ^{:var :bool} any-solo false ^{:var :f32} block-peak 0.0]
    (when (ak/!= requested 18446744073709551615) (set! frame-position (ak/min requested duration)))
    (dotimes [j clip-count] (when (az/field (az/index clips j) solo) (set! any-solo true)))
    (dotimes [i frames]
      (let [^{:var :f32} left 0.0 ^{:var :f32} right 0.0]
        (when (and active (az/field loop enabled)
                   (or (< frame-position (az/field loop from)) (>= frame-position (az/field loop to))))
          (set! frame-position (az/field loop from)))
        (when (and active (< frame-position duration))
          (dotimes [j clip-count]
            (let [clip (az/index clips j)]
              (when (and (ak/! (az/field clip mute)) (or (ak/! any-solo) (az/field clip solo))
                         (>= frame-position (az/field clip start)) (< (- frame-position (az/field clip start)) (az/field clip frames)))
                (let [offset (- frame-position (az/field clip start))
                      fade (ak/as :f32 (ak/floatFromInt (ak/max (ak/as :u64 1) (az/field clip fade))))
                      envelope (if (ak/== (az/field clip fade) 0) 1.0
                                 (ak/min 1.0 (/ (ak/as :f32 (ak/floatFromInt (ak/min offset (- (az/field clip frames) 1 offset)))) fade)))
                      gain (* envelope (az/field clip gain))
                      sample (* (+ (az/field clip base) offset) 2)]
                  (set! left (+ left (* (az/index samples sample) gain (- 1.0 (ak/max 0.0 (az/field clip pan))))))
                  (set! right (+ right (* (az/index samples (+ sample 1)) gain (+ 1.0 (ak/min 0.0 (az/field clip pan))))))))))
          (set! frame-position (+ frame-position 1))
          (when (and (az/field loop enabled) (ak/== frame-position (az/field loop to)))
            (set! frame-position (az/field loop from))))
        (set! block-peak (ak/max block-peak (ak/max (ak/abs left) (ak/abs right))))
        (when (> block-peak 1.0) (ak/atomicStore :u8 (ak/& clipped) 1 :.release))
        (set! (az/index output (* i 2)) (ak/max -1.0 (ak/min 1.0 left)))
        (set! (az/index output (+ (* i 2) 1)) (ak/max -1.0 (ak/min 1.0 right)))))
    (ak/atomicStore :u64 (ak/& cursor) frame-position :.release)
    (ak/atomicStore :u32 (ak/& peak) (ak/intFromFloat (* 1000.0 (ak/min 1.0 block-peak))) :.release)))

(az/defn callback {:zig/qualifiers "callconv(.c)"} :- :void
  [[device-pointer [:c-pointer recorder/Device]] [output [:optional [:* :anyopaque]]]
   [input [:optional [:*const :anyopaque]]] [frames :u32]]
  (set! _ device-pointer) (set! _ input)
  (process! (ak/ptrCast (ak/alignCast output)) frames))

(az/defvar output-index :u32 4294967295)
(az/defn open! :- :bool []
  (when opened (ak/return true))
  (when (or (ak/== clip-count 0) (ak/! recorder/initialized) (>= output-index recorder/playback-count)) (ak/return false))
  (let [^:var config ((az/field recorder/api ma_device_config_init) (az/field recorder/api ma_device_type_playback))]
    (set! (az/field config sampleRate) 48000)
    (set! (az/field (az/field config playback) pDeviceID) (ak/& (az/field (az/index recorder/playback-info output-index) id)))
    (set! (az/field (az/field config playback) format) (az/field recorder/api ma_format_f32))
    (set! (az/field (az/field config playback) channels) 2)
    (set! (az/field config dataCallback) (ak/& callback))
    (when (ak/!= ((az/field recorder/api ma_device_init) (ak/& recorder/context) (ak/& config) (ak/& device)) 0) (ak/return false))
    (when (ak/!= ((az/field recorder/api ma_device_start) (ak/& device)) 0)
      ((az/field recorder/api ma_device_uninit) (ak/& device)) (ak/return false))
    (set! opened true) true))

(az/defn play! :- :void [] (ak/atomicStore :u8 (ak/& playing) 1 :.release))
(az/defn pause! :- :void [] (ak/atomicStore :u8 (ak/& playing) 0 :.release))
(az/defn seek! :- :void [[frame :u64]] (ak/atomicStore :u64 (ak/& seek-request) (ak/min frame duration) :.release))
(az/defn cursor-frame :- :u64 [] (ak/atomicLoad :u64 (ak/& cursor) :.acquire))
(az/defn playing? :- :bool []
  (and (ak/!= (ak/atomicLoad :u8 (ak/& playing) :.acquire) 0)
       (or (< (cursor-frame) duration) (az/field (loop-state) enabled))))
