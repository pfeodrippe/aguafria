(ns la-professeure.tools.mixer
  "Bounded native sample-clock mixer. Configure only while its device is closed."
  (:require [aguafria.std] [aguafria.std.mem :as mem]
            [aguafria.keyword :as k] [aguafria.zig :as a]
            [la-professeure.tools.recorder :as recorder]))

(a/defconst max-clips :usize 16)

(a/defconst max-source-frames :usize 5760000)

(a/defconst Decoder (:ma_decoder recorder/api))

(a/defstruct Clip
  [[base :usize] [frames :u64] [start :u64] [gain :f32] [pan :f32]
   [fade :u64] [mute :bool] [solo :bool]])

(a/defvar clips [:array 16 Clip] (mem/zeroes [:array 16 Clip]))

(a/defvar samples [:array 11520000 :f32] k/undefined)

(a/defvar used :usize 0)

(a/defvar clip-count :usize 0)

(a/defvar duration :u64 0)

(a/defvar device recorder/Device k/undefined)

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
  (let [region (k/atomicLoad :u64 (k/& loop-region) :.acquire)]
    (k/as {:from (k/mod region 4294967296) :to (k// region 4294967296) :enabled (k/!= region 0)} Loop)))

(a/defn set-loop! :bool [[from :u64] [to :u64] [enabled :bool]]
  (when (and enabled (or (k/>= from to) (k/> to duration) (k/> to 4294967295))) (k/return false))
  (k/atomicStore :u64 (k/& loop-region) (if enabled (k/+ (k/* to 4294967296) from) 0) :.release)
  true)

(a/defvar peak :u32 0)

(a/defvar clipped :u8 0)

(a/defvar path-buffer [:array 4096 :u8] k/undefined)

(a/defn close! :void []
  (when opened ((:ma_device_uninit recorder/api) (k/& device)) (k/= opened false))
  (k/atomicStore :u8 (k/& playing) 0 :.release))

(a/defn handle-stopped-output! :bool []
  (when (and opened
             (k/== ((:ma_device_get_state recorder/api) (k/& device))
                   (:ma_device_state_stopped recorder/api)))
    ;; Closing the device leaves clip PCM, loop selection and cursor intact.
    (close!)
    (k/atomicStore :u32 (k/& peak) 0 :.release)
    (k/return true))
  false)

(a/defn reset! :void []
  (close!) (k/= used 0) (k/= clip-count 0) (k/= duration 0)
  (k/atomicStore :u64 (k/& cursor) 0 :.release)
  (k/atomicStore :u64 (k/& seek-request) 18446744073709551615 :.release)
  (k/atomicStore :u64 (k/& loop-region) 0 :.release)
  (k/atomicStore :u32 (k/& peak) 0 :.release)
  (k/atomicStore :u8 (k/& clipped) 0 :.release))

(a/defn configure-clip! :bool
  [[index :usize] [start :u64] [gain :f32] [pan :f32] [fade :u64] [mute :bool] [solo :bool]]
  (when (or opened (k/>= index clip-count) (k/> start 28800000)
            (k/! (and (k/>= gain 0.0) (k/<= gain 2.0) (k/>= pan -1.0) (k/<= pan 1.0)))) (k/return false))
  (let [clip (k/& (a/get clips index))]
    (k/= (:start clip) start) (k/= (:gain clip) gain) (k/= (:pan clip) pan)
    (k/= (:fade clip) (k/min fade (k// (:frames clip) 2)))
    (k/= (:mute clip) mute) (k/= (:solo clip) solo))
  (k/= duration 0)
  (dotimes [i clip-count]
    (k/= duration (k/max duration (k/+ (:start (a/get clips i)) (:frames (a/get clips i))))))
  ;; Editing the prepared plan invalidates its old playback region.
  (k/= :_ (set-loop! 0 0 false))
  true)

(a/defn add-file! :bool [[path [:slice-const :u8]]]
  (when (or opened (k/>= clip-count max-clips) (k/>= (:len path) 4096)) (k/return false))
  (dotimes [i (:len path)] (when (k/== (a/get path i) 0) (k/return false)))
  (k/memcpy (a/slice path-buffer 0 (:len path)) path)
  (k/= (a/get path-buffer (:len path)) 0)
  (let [config (k/var ((:ma_decoder_config_init recorder/api) (:ma_format_f32 recorder/api) 2 48000))
        decoder (k/var (k/as k/undefined Decoder))
        frames (k/var (k/u64 0)) read (k/var (k/u64 0))]
    (when (k/!= ((:ma_decoder_init_file recorder/api) (k/& path-buffer) (k/& config) (k/& decoder)) 0)
      (k/return false))
    (k/defer (k/= :_ ((:ma_decoder_uninit recorder/api) (k/& decoder))))
    (when (or (k/!= ((:ma_decoder_get_length_in_pcm_frames recorder/api) (k/& decoder) (k/& frames)) 0)
              (k/== frames 0) (k/> frames (k/- max-source-frames used))) (k/return false))
    (k/= :_ ((:ma_decoder_read_pcm_frames recorder/api) (k/& decoder) (k/& (a/get samples (k/* used 2))) frames (k/& read)))
    (when (k/!= read frames) (k/return false))
    ;; Reject non-finite PCM before publishing a clip to the callback.
    (dotimes [i (k/* frames 2)]
      (when (k/! (k/< (k/abs (a/get samples (k/+ (k/* used 2) i))) 1000000.0)) (k/return false)))
    (k/= (a/get clips clip-count) (k/as {:base used :frames frames :start 0 :gain 1.0 :pan 0.0 :fade 240 :mute false :solo false} Clip))
    (k/= used (k/+ used (k/as (k/intCast frames) :usize))) (k/= clip-count (k/+ clip-count 1))
    (k/= duration (k/max duration frames)) true))

(a/defn process! :void
  "The sole audio path, also called by offline tests. No allocation, locks or I/O." [[output [:c-pointer :f32]] [frames :u32]]
  (when (k/== output k/null) (k/return))
  (let [active (k/!= (k/atomicLoad :u8 (k/& playing) :.acquire) 0)
        loop (loop-state)
        requested (k/atomicRmw :u64 (k/& seek-request) :.Xchg 18446744073709551615 :.acq_rel)
        frame-position (k/var (k/u64 (k/atomicLoad :u64 (k/& cursor) :.acquire)))
        any-solo (k/var (k/bool false)) block-peak (k/var (k/f32 0.0))]
    (when (k/!= requested 18446744073709551615) (k/= frame-position (k/min requested duration)))
    (dotimes [j clip-count] (when (:solo (a/get clips j)) (k/= any-solo true)))
    (dotimes [i frames]
      (let [left (k/var (k/f32 0.0)) right (k/var (k/f32 0.0))]
        (when (and active (:enabled loop)
                   (or (k/< frame-position (:from loop)) (k/>= frame-position (:to loop))))
          (k/= frame-position (:from loop)))
        (when (and active (k/< frame-position duration))
          (dotimes [j clip-count]
            (let [clip (a/get clips j)]
              (when (and (k/! (:mute clip)) (or (k/! any-solo) (:solo clip))
                         (k/>= frame-position (:start clip)) (k/< (k/- frame-position (:start clip)) (:frames clip)))
                (let [offset (k/- frame-position (:start clip))
                      fade (k/as (k/floatFromInt (k/max (k/as 1 :u64) (:fade clip))) :f32)
                      envelope (if (k/== (:fade clip) 0) 1.0
                                   (k/min 1.0 (k// (k/as (k/floatFromInt (k/min offset (k/- (:frames clip) 1 offset))) :f32) fade)))
                      gain (k/* envelope (:gain clip))
                      sample (k/* (k/+ (:base clip) offset) 2)]
                  (k/= left (k/+ left (k/* (a/get samples sample) gain (k/- 1.0 (k/max 0.0 (:pan clip))))))
                  (k/= right (k/+ right (k/* (a/get samples (k/+ sample 1)) gain (k/+ 1.0 (k/min 0.0 (:pan clip))))))))))
          (k/= frame-position (k/+ frame-position 1))
          (when (and (:enabled loop) (k/== frame-position (:to loop)))
            (k/= frame-position (:from loop))))
        (k/= block-peak (k/max block-peak (k/max (k/abs left) (k/abs right))))
        (when (k/> block-peak 1.0) (k/atomicStore :u8 (k/& clipped) 1 :.release))
        (k/= (a/get output (k/* i 2)) (k/max -1.0 (k/min 1.0 left)))
        (k/= (a/get output (k/+ (k/* i 2) 1)) (k/max -1.0 (k/min 1.0 right)))))
    (k/atomicStore :u64 (k/& cursor) frame-position :.release)
    (k/atomicStore :u32 (k/& peak) (k/intFromFloat (k/* 1000.0 (k/min 1.0 block-peak))) :.release)))

(a/defn callback :void {:zig/qualifiers "callconv(.c)"}
  [[device-pointer [:c-pointer recorder/Device]] [output [:optional [:* :anyopaque]]]
   [input [:optional [:*const :anyopaque]]] [frames :u32]]
  (k/= :_ device-pointer) (k/= :_ input)
  (process! (k/ptrCast (k/alignCast output)) frames))

(a/defvar output-index :u32 4294967295)

(a/defn open! :bool []
  (when opened (k/return true))
  (when (or (k/== clip-count 0) (k/! recorder/initialized) (k/>= output-index recorder/playback-count)) (k/return false))
  (let [config (k/var ((:ma_device_config_init recorder/api) (:ma_device_type_playback recorder/api)))]
    (a/merge! config {:sampleRate 48000 :dataCallback (k/& callback)})
    (a/merge! (:playback config)
               {:pDeviceID (k/& (:id (a/get recorder/playback-info output-index)))
                :format (:ma_format_f32 recorder/api)
                :channels 2})
    (when (k/!= ((:ma_device_init recorder/api) (k/& recorder/context) (k/& config) (k/& device)) 0) (k/return false))
    (when (k/!= ((:ma_device_start recorder/api) (k/& device)) 0)
      ((:ma_device_uninit recorder/api) (k/& device)) (k/return false))
    (k/= opened true) true))

(a/defn play! :void [] (k/atomicStore :u8 (k/& playing) 1 :.release))

(a/defn pause! :void [] (k/atomicStore :u8 (k/& playing) 0 :.release))

(a/defn seek! :void [[frame :u64]] (k/atomicStore :u64 (k/& seek-request) (k/min frame duration) :.release))

(a/defn cursor-frame :u64 [] (k/atomicLoad :u64 (k/& cursor) :.acquire))

(a/defn playing? :bool []
  (and (k/!= (k/atomicLoad :u8 (k/& playing) :.acquire) 0)
       (or (k/< (cursor-frame) duration) (:enabled (loop-state)))))
