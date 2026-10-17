(ns la-professeure.tools.mixer
  "Bounded native sample-clock mixer. Configure only while its device is closed."
  (:refer-clojure :only [])
  (:require [aguafria.std]
            [aguafria.std.mem :as mem]
            [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]
            [la-professeure.tools.recorder :as recorder]))

(a/defconst max-clips :usize 16)

(a/defconst max-source-frames :usize 5760000)

(a/defconst Decoder (:ma_decoder recorder/api))

(a/defstruct Clip
  [[base :usize] [frames :u64] [start :u64] [gain :f32] [pan :f32]
   [fade :u64] [mute :bool] [solo :bool]])

(a/defvar clips [:array 16 Clip] (mem/zeroes [:array 16 Clip]))

(a/defvar samples [:array 11520000 :f32] undefined)

(a/defvar used :usize 0)

(a/defvar clip-count :usize 0)

(a/defvar duration :u64 0)

(a/defvar device recorder/Device undefined)

(a/defvar opened :bool false)

;; Desired transport state belongs to control callers. The callback must never
;; overwrite a newer play/seek command when it reaches the end of a block.
(a/defvar playing :u8 0)

(a/defvar cursor :u64 0)

(a/defvar seek-request :u64 18446744073709551615)

;; One atomic publication prevents the callback observing mismatched loop edges.
;; The bounded timeline fits two u32 frame positions in one u64; zero disables.
(a/defvar loop-region :u64 0)

(a/defstruct Loop {:layout :extern} [[from :u64] [to :u64] [enabled :bool]])

(a/defn loop-state Loop []
  (let [region (atomicLoad :u64 (k/& loop-region) :acquire)]
    (as {:from (mod region 4294967296) :to (/ region 4294967296) :enabled (!= region 0)} Loop)))

(a/defn set-loop! :bool [[from :u64] [to :u64] [enabled :bool]]
  (when (and enabled (or (>= from to) (> to duration) (> to 4294967295))) (return false))
  (atomicStore :u64 (k/& loop-region) (if enabled (+ (* to 4294967296) from) 0) :release)
  true)

(a/defvar peak :u32 0)

(a/defvar clipped :u8 0)

(a/defvar path-buffer [:array 4096 :u8] undefined)

(a/defn close! :void []
  (when opened ((:ma_device_uninit recorder/api) (k/& device)) (= opened false))
  (atomicStore :u8 (k/& playing) 0 :release))

(a/defn handle-stopped-output! :bool []
  (when (and opened
             (== ((:ma_device_get_state recorder/api) (k/& device))
                 (:ma_device_state_stopped recorder/api)))
    ;; Closing the device leaves clip PCM, loop selection and cursor intact.
    (close!)
    (atomicStore :u32 (k/& peak) 0 :release)
    (return true))
  false)

(a/defn reset! :void []
  (close!) (= used 0) (= clip-count 0) (= duration 0)
  (atomicStore :u64 (k/& cursor) 0 :release)
  (atomicStore :u64 (k/& seek-request) 18446744073709551615 :release)
  (atomicStore :u64 (k/& loop-region) 0 :release)
  (atomicStore :u32 (k/& peak) 0 :release)
  (atomicStore :u8 (k/& clipped) 0 :release))

(a/defn configure-clip! :bool
  [[index :usize] [start :u64] [gain :f32] [pan :f32] [fade :u64] [mute :bool] [solo :bool]]
  (when (or opened (>= index clip-count) (> start 28800000)
            (! (and (>= gain 0.0) (<= gain 2.0) (>= pan -1.0) (<= pan 1.0)))) (return false))
  (let [clip (k/& (a/get clips index))]
    (= (:start clip) start) (= (:gain clip) gain) (= (:pan clip) pan)
    (= (:fade clip) (min fade (/ (:frames clip) 2)))
    (= (:mute clip) mute) (= (:solo clip) solo))
  (= duration 0)
  (dotimes [i clip-count]
    (= duration (max duration (+ (:start (a/get clips i)) (:frames (a/get clips i))))))
  ;; Editing the prepared plan invalidates its old playback region.
  (= :_ (set-loop! 0 0 false))
  true)

(a/defn add-file! :bool [[path [:slice-const :u8]]]
  (when (or opened (>= clip-count max-clips) (>= (:len path) 4096)) (return false))
  (dotimes [i (:len path)] (when (== (a/get path i) 0) (return false)))
  (memcpy (a/slice path-buffer 0 (:len path)) path)
  (= (a/get path-buffer (:len path)) 0)
  (let [config (k/var ((:ma_decoder_config_init recorder/api) (:ma_format_f32 recorder/api) 2 48000))
        decoder (k/var (as undefined Decoder))
        frames (k/var (u64 0)) read (k/var (u64 0))]
    (when (!= ((:ma_decoder_init_file recorder/api) (k/& path-buffer) (k/& config) (k/& decoder)) 0)
      (return false))
    (defer (= :_ ((:ma_decoder_uninit recorder/api) (k/& decoder))))
    (when (or (!= ((:ma_decoder_get_length_in_pcm_frames recorder/api) (k/& decoder) (k/& frames)) 0)
              (== frames 0) (> frames (- max-source-frames used))) (return false))
    (= :_ ((:ma_decoder_read_pcm_frames recorder/api) (k/& decoder) (k/& (a/get samples (* used 2))) frames (k/& read)))
    (when (!= read frames) (return false))
    ;; Reject non-finite PCM before publishing a clip to the callback.
    (dotimes [i (* frames 2)]
      (when (! (< (abs (a/get samples (+ (* used 2) i))) 1000000.0)) (return false)))
    (= (a/get clips clip-count) (as {:base used :frames frames :start 0 :gain 1.0 :pan 0.0 :fade 240 :mute false :solo false} Clip))
    (= used (+ used (as (intCast frames) :usize))) (= clip-count (+ clip-count 1))
    (= duration (max duration frames)) true))

(a/defn process! :void
  "The sole audio path, also called by offline tests. No allocation, locks or I/O." [[output [:c-pointer :f32]] [frames :u32]]
  (when (== output null) (return))
  (let [active (!= (atomicLoad :u8 (k/& playing) :acquire) 0)
        loop (loop-state)
        requested (atomicRmw :u64 (k/& seek-request) :Xchg 18446744073709551615 :acq_rel)
        frame-position (k/var (u64 (atomicLoad :u64 (k/& cursor) :acquire)))
        any-solo (k/var (bool false)) block-peak (k/var (f32 0.0))]
    (when (!= requested 18446744073709551615) (= frame-position (min requested duration)))
    (dotimes [j clip-count] (when (:solo (a/get clips j)) (= any-solo true)))
    (dotimes [i frames]
      (let [left (k/var (f32 0.0)) right (k/var (f32 0.0))]
        (when (and active (:enabled loop)
                   (or (< frame-position (:from loop)) (>= frame-position (:to loop))))
          (= frame-position (:from loop)))
        (when (and active (< frame-position duration))
          (dotimes [j clip-count]
            (let [clip (a/get clips j)]
              (when (and (! (:mute clip)) (or (! any-solo) (:solo clip))
                         (>= frame-position (:start clip)) (< (- frame-position (:start clip)) (:frames clip)))
                (let [offset (- frame-position (:start clip))
                      fade (as (floatFromInt (max (as 1 :u64) (:fade clip))) :f32)
                      envelope (if (== (:fade clip) 0) 1.0
                                   (min 1.0 (/ (as (floatFromInt (min offset (- (:frames clip) 1 offset))) :f32) fade)))
                      gain (* envelope (:gain clip))
                      sample (* (+ (:base clip) offset) 2)]
                  (= left (+ left (* (a/get samples sample) gain (- 1.0 (max 0.0 (:pan clip))))))
                  (= right (+ right (* (a/get samples (+ sample 1)) gain (+ 1.0 (min 0.0 (:pan clip))))))))))
          (= frame-position (+ frame-position 1))
          (when (and (:enabled loop) (== frame-position (:to loop)))
            (= frame-position (:from loop))))
        (= block-peak (max block-peak (max (abs left) (abs right))))
        (when (> block-peak 1.0) (atomicStore :u8 (k/& clipped) 1 :release))
        (= (a/get output (* i 2)) (max -1.0 (min 1.0 left)))
        (= (a/get output (+ (* i 2) 1)) (max -1.0 (min 1.0 right)))))
    (atomicStore :u64 (k/& cursor) frame-position :release)
    (atomicStore :u32 (k/& peak) (intFromFloat (* 1000.0 (min 1.0 block-peak))) :release)))

(a/defn callback :void {:callconv :c}
  [[device-pointer [:c-pointer recorder/Device]] [output [:optional [:* :anyopaque]]]
   [input [:optional [:*const :anyopaque]]] [frames :u32]]
  (= :_ device-pointer) (= :_ input)
  (process! (ptrCast (alignCast output)) frames))

(a/defvar output-index :u32 4294967295)

(a/defn open! :bool []
  (when opened (return true))
  (when (or (== clip-count 0) (! recorder/initialized) (>= output-index recorder/playback-count)) (return false))
  (let [config (k/var ((:ma_device_config_init recorder/api) (:ma_device_type_playback recorder/api)))]
    (a/merge! config {:sampleRate 48000 :dataCallback (k/& callback)})
    (a/merge! (:playback config)
              {:pDeviceID (k/& (:id (a/get recorder/playback-info output-index)))
               :format (:ma_format_f32 recorder/api)
               :channels 2})
    (when (!= ((:ma_device_init recorder/api) (k/& recorder/context) (k/& config) (k/& device)) 0) (return false))
    (when (!= ((:ma_device_start recorder/api) (k/& device)) 0)
      ((:ma_device_uninit recorder/api) (k/& device)) (return false))
    (= opened true) true))

(a/defn play! :void [] (atomicStore :u8 (k/& playing) 1 :release))

(a/defn pause! :void [] (atomicStore :u8 (k/& playing) 0 :release))

(a/defn seek! :void [[frame :u64]] (atomicStore :u64 (k/& seek-request) (min frame duration) :release))

(a/defn cursor-frame :u64 [] (atomicLoad :u64 (k/& cursor) :acquire))

(a/defn playing? :bool []
  (and (!= (atomicLoad :u8 (k/& playing) :acquire) 0)
       (or (< (cursor-frame) duration) (:enabled (loop-state)))))
