(ns la-professeure.tools.recorder
  "Native development recorder. Audio callbacks never enter the JVM or touch disk."
  (:require [aguafria.c :as ac]
            [clojure.java.io :as io]
            [aguafria.std] [aguafria.std.mem :as mem]
            [aguafria.keyword :as k] [aguafria.zig :as a]
            [la-professeure.miniaudio :as audio]
            [la-professeure.build :as build]))

(a/configure! {:module-zig-args
                (assoc (:module-zig-args (a/configuration)) "la-professeure.tools.recorder"
                       [(str "-I" (clojure.java.io/file (build/root) "build/vendor/miniaudio"))])})

(a/defconst api audio/c-api)

(a/defconst file-api
  (k/import (a/clj! (ac/import! "la_professeure_recorder_io"
                                 (io/file (io/resource "native/recorder_io.h"))
                                 {:args ["-lc"]}))))

(a/defconst Context (:ma_context api))

(a/defconst Device (:ma_device api))

(a/defconst DeviceInfo (:ma_device_info api))

(a/defconst Decoder (:ma_decoder api))

(a/defconst Encoder (:ma_encoder api))

(a/defconst max-frames :usize 2880000)

(a/defvar context Context k/undefined)

(a/defvar device Device k/undefined)

(a/defvar initialized false)

(a/defvar running false)

(a/defvar capture-enabled :u8 1)

(a/defn hold-capture! :void []
  ;; Devices may open asynchronously while PREPARING remains cancellable.
  ;; Neither input samples nor effects sends belong to a take until committed.
  (k/atomicStore :u8 (k/& capture-enabled) 0 :.release))

(a/defn release-capture! :void []
  (k/atomicStore :u8 (k/& capture-enabled) 1 :.release))

(a/defn capture-held? :bool []
  (k/== (k/atomicLoad :u8 (k/& capture-enabled) :.acquire) 0))

(a/defn- silence-preparing-output! :void
  [[output [:c-pointer :f32]] [frames :u32]]
  (when (k/!= output k/null)
    (dotimes [i (k/* frames 16)]
      (k/= (a/get output i) 0.0))))

(a/defvar playback-info [:c-pointer DeviceInfo] k/null)

(a/defvar capture-info [:c-pointer DeviceInfo] k/null)

(a/defvar playback-count :u32 0)

(a/defvar capture-count :u32 0)

(a/defn device-name [:slice-const :u8] [[capture :bool] [index :u32]]
  (when (or (k/! initialized) (k/>= index (if capture capture-count playback-count))) (k/return ""))
  (let [info (if capture (a/get capture-info index) (a/get playback-info index))
        length (k/var (k/usize 0))]
    (k/while (and (k/< length 256) (k/!= (a/get (:name info) length) 0))
      (k/= length (k/+ length 1)))
    ;; Return the context-owned array, not the local struct copy.
    (let [entry
          (k/as (k/ptrCast (a/unwrap (k/as (k/& (a/get (if capture capture-info playback-info) index)) [:c-pointer DeviceInfo]))) [:* DeviceInfo])]
      (a/slice (:name entry) 0 length))))

(a/defvar dry [:array 5760000 :f32] k/undefined)

(a/defvar wet [:array 5760000 :f32] k/undefined)

(a/defvar dry-frames :u64 0)

(a/defvar recorded-frames :u64 0)

(a/defvar limit-frames :u64 0)

(a/defvar mode :u32 0)

(a/defvar peak-milli :u32 0)

(a/defvar finished :u8 0)

(a/defvar error-code :i32 0)

(a/defvar path-buffer [:array 4096 :u8] k/undefined)

(a/defvar measured-frames :u64 0)

(a/defvar measured-peak :f32 0.0)

(a/defvar source-device Device k/undefined)

(a/defvar source-running false)

(a/defvar source-channels :usize 2)

(a/defvar source-offset :usize 0)

(a/defvar source-frames :u64 0)

(a/defvar source-stop :u8 0)

(a/defvar source-ended :u8 0)

(a/defvar live-tail :u64 48000)

(a/defvar tail-started false)

(a/defvar input-level :u32 0)

(a/defvar clipped :u8 0)

(a/defvar monitor-device Device k/undefined)

(a/defvar monitoring false)

(a/defvar monitor-gain :u32 15)

(a/defvar input-peak-ppm :u32 0)

(a/defvar return-peak-ppm :u32 0)

(a/defvar input-held-ppm :u32 0)

(a/defvar return-held-ppm :u32 0)

;; Preflight owns only a capture device and peak counters: no PCM retention or output.
(a/defvar input-check-device Device k/undefined)

(a/defvar input-check-running :u32 0)

(a/defvar input-check-channels :u32 2)

(a/defvar input-check-offset :u32 0)

(a/defvar input-check-peak :u32 0)

(a/defvar input-check-held :u32 0)

(a/defvar input-check-frames :u64 0)

(a/defn input-check-active? :bool []
  (k/> (k/atomicLoad :u32 (k/& input-check-running) :.acquire) 0))

(a/defn stopped-device-mask :u32
  "Control-worker only. Owned devices that unexpectedly stopped: capture=1,
   FX source/send=2, monitor=4, input check=8. Never probes uninitialized storage." []
  (let [stopped (:ma_device_state_stopped api)
        result (k/var (k/u32 0))]
    (when (and running
               (k/== ((:ma_device_get_state api) (k/& device)) stopped))
      (k/= result (k/| result 1)))
    (when (and source-running
               (k/== ((:ma_device_get_state api) (k/& source-device)) stopped))
      (k/= result (k/| result 2)))
    (when (and monitoring
               (k/== ((:ma_device_get_state api) (k/& monitor-device)) stopped))
      (k/= result (k/| result 4)))
    (when (and (input-check-active?)
               (k/== ((:ma_device_get_state api) (k/& input-check-device)) stopped))
      (k/= result (k/| result 8)))
    result))

(a/defn process-input-check! :void
  [[input [:c-pointer :f32]] [frames :u32]]
  (let [peak (k/var (k/f32 0.0))]
    (when (k/!= input k/null)
      (dotimes [i frames]
        (dotimes [channel 2]
          (let [sample (a/get input (k/+ (k/* i input-check-channels)
                                          input-check-offset channel))]
            (when (k/< (k/abs sample) 100.0)
              (k/= peak (k/max peak (k/abs sample))))))))
    (let [value (k/as (k/intFromFloat (k/* 1000000.0 (k/min peak 1.0))) :u32)]
      (k/atomicStore :u32 (k/& input-check-peak) value :.release)
      (when (k/> value (k/atomicLoad :u32 (k/& input-check-held) :.acquire))
        (k/atomicStore :u32 (k/& input-check-held) value :.release)))
    (k/= :_ (k/atomicRmw :u64 (k/& input-check-frames) :.Add frames :.release))))

(a/defn input-check-callback :void {:zig/qualifiers "callconv(.c)"}
  [[pointer [:c-pointer Device]] [output [:optional [:* :anyopaque]]]
   [input [:optional [:*const :anyopaque]]] [frames :u32]]
  (k/= :_ pointer)
  (k/= :_ output)
  (process-input-check! (k/ptrCast (k/alignCast (k/constCast input))) frames))

(a/defn stop-input-check! :void []
  ;; Lifecycle calls are serialized by the studio's control worker.
  (when (input-check-active?)
    (k/atomicStore :u32 (k/& input-check-running) 0 :.release)
    ((:ma_device_uninit api) (k/& input-check-device))))

(a/defn start-input-check! :bool [[index :u32] [fx-source? :bool]]
  (when (or (input-check-active?) running source-running monitoring
            (k/! initialized) (k/>= index capture-count))
    (k/return false))
  (let [loopback? (and fx-source?
                       (mem/eql :u8 (device-name true index) "BlackHole 16ch"))]
    (k/= input-check-channels (if loopback? 16 2))
    (k/= input-check-offset (if loopback? 4 0)))
  (k/atomicStore :u32 (k/& input-check-peak) 0 :.release)
  (k/atomicStore :u32 (k/& input-check-held) 0 :.release)
  (k/atomicStore :u64 (k/& input-check-frames) 0 :.release)
  (let [config (k/var ((:ma_device_config_init api)
                       (:ma_device_type_capture api)))]
    (a/merge! config {:sampleRate 48000 :dataCallback (k/& input-check-callback)})
    (a/merge! (:capture config)
               {:pDeviceID (k/& (:id (a/get capture-info index)))
                :format (:ma_format_f32 api)
                :channels input-check-channels})
    (when (k/!= ((:ma_device_init api)
                 (k/& context) (k/& config) (k/& input-check-device)) 0)
      (k/return false))
    (when (k/!= ((:ma_device_start api) (k/& input-check-device)) 0)
      ((:ma_device_uninit api) (k/& input-check-device))
      (k/return false)))
  (k/atomicStore :u32 (k/& input-check-running) 1 :.release)
  true)

(a/defn update-signal-level! :void [[input? :bool] [peak :f32]]
  (let [value (k/as (k/intFromFloat (k/* 1000000.0 (k/min 1.0 (k/max 0.0 peak)))) :u32)
        current (if input? (k/& input-peak-ppm) (k/& return-peak-ppm))
        held (if input? (k/& input-held-ppm) (k/& return-held-ppm))]
    (k/atomicStore :u32 current value :.release)
    (when (k/> value (k/atomicLoad :u32 held :.acquire))
      (k/atomicStore :u32 held value :.release))))

(a/defn signal-peak :f32 [[input? :bool] [held? :bool]]
  (let [value (if input?
                (if (input-check-active?)
                  (if held? (k/& input-check-held) (k/& input-check-peak))
                  (if held? (k/& input-held-ppm) (k/& input-peak-ppm)))
                (if held? (k/& return-held-ppm) (k/& return-peak-ppm)))]
    (k/* 0.000001 (k/as (k/floatFromInt (k/atomicLoad :u32 value :.acquire)) :f32))))

(a/defn meter-input! :void [[value :f32]]
  (when (k/! (k/< (k/abs value) 1.0)) (k/atomicStore :u8 (k/& clipped) 1 :.release)))

(a/defn reset-meters! :void []
  (k/atomicStore :u32 (k/& input-peak-ppm) 0 :.release)
  (k/atomicStore :u32 (k/& return-peak-ppm) 0 :.release)
  (k/atomicStore :u32 (k/& input-held-ppm) 0 :.release)
  (k/atomicStore :u32 (k/& return-held-ppm) 0 :.release)
  (k/atomicStore :u8 (k/& clipped) 0 :.release)
  (k/atomicStore :u32 (k/& input-level) 0 :.release)
  (k/atomicStore :u32 (k/& peak-milli) 0 :.release))

(a/defn process-monitor! :void
  [[output [:c-pointer :f32]] [input [:c-pointer :f32]] [frames :u32]]
  (when (k/== output k/null) (k/return))
  (let [gain (k/* 0.01 (k/as (k/floatFromInt (k/min 50 (k/atomicLoad :u32 (k/& monitor-gain) :.acquire))) :f32))
        peak (k/var (k/f32 0.0))]
    (dotimes [i frames]
      (dotimes [c 2]
        (let [v (if (k/== input k/null) 0.0 (a/get input (k/+ (k/* i 16) 2 c)))]
          (when (k/< (k/abs v) 100.0) (k/= peak (k/max peak (k/abs v))))
          (k/= (a/get output (k/+ (k/* i 2) c))
               (if (k/< (k/abs v) 100.0) (k/* gain (k/max -1.0 (k/min 1.0 v))) 0.0)))))
    ;; Monitoring also drives the return meter when no take is being captured.
    (update-signal-level! false peak)))

(a/defn monitor-callback :void {:zig/qualifiers "callconv(.c)"}
  [[pointer [:c-pointer Device]] [output [:optional [:* :anyopaque]]]
   [input [:optional [:*const :anyopaque]]] [frames :u32]]
  (k/= :_ pointer)
  (process-monitor! (k/ptrCast (k/alignCast output))
                    (k/ptrCast (k/alignCast (k/constCast input))) frames))

(a/defn stop-monitor! :void []
  (when monitoring ((:ma_device_uninit api) (k/& monitor-device)) (k/= monitoring false)))

(a/defn headphone-device? :bool [[index :u32]]
  (let [name (device-name false index)]
    (or (k/!= (mem/find :u8 name "Headphones") k/null)
        (k/!= (mem/find :u8 name "AirPods") k/null)
        (k/!= (mem/find :u8 name "Casque") k/null))))

(a/defn start-monitor! :bool [[return-index :u32] [headphone-index :u32]]
  (when (or monitoring (input-check-active?) (k/! initialized) (k/>= return-index capture-count)
            (k/>= headphone-index playback-count) (k/! (headphone-device? headphone-index))
            (k/! (mem/eql :u8 (device-name true return-index) "BlackHole 16ch")))
    (k/return false))
  (let [config (k/var ((:ma_device_config_init api) (:ma_device_type_duplex api)))]
    (a/merge! config {:sampleRate 48000 :dataCallback (k/& monitor-callback)})
    (a/merge! (:capture config)
               {:pDeviceID (k/& (:id (a/get capture-info return-index)))
                :format (:ma_format_f32 api)
                :channels 16})
    (a/merge! (:playback config)
               {:pDeviceID (k/& (:id (a/get playback-info headphone-index)))
                :format (:ma_format_f32 api)
                :channels 2})
    (when (k/!= ((:ma_device_init api) (k/& context) (k/& config) (k/& monitor-device)) 0)
      (k/return false))
    (when (k/!= ((:ma_device_start api) (k/& monitor-device)) 0)
      ((:ma_device_uninit api) (k/& monitor-device)) (k/return false))
    (k/= monitoring true) true))

(a/defn process-source! :void
  "Live source callback: retain dry stereo, send only 1/2. Never read the return bus." [[output [:c-pointer :f32]] [input [:c-pointer :f32]] [frames :u32]]
  (when (capture-held?)
    (silence-preparing-output! output frames)
    (k/return))
  (let [start (k/atomicLoad :u64 (k/& source-frames) :.monotonic)
        stopping (k/!= (k/atomicLoad :u8 (k/& source-stop) :.acquire) 0)
        count (if stopping (k/as 0 :u64) (k/min (k/as frames :u64) (k/- max-frames live-tail start)))
        peak (k/var (k/f32 0.0))]
    (dotimes [i frames]
      (when (k/!= output k/null)
        (dotimes [channel 16] (k/= (a/get output (k/+ (k/* i 16) channel)) 0.0)))
      (when (k/< i count)
        (dotimes [channel 2]
          (let [value (if (k/== input k/null) 0.0
                          (a/get input (k/+ (k/* i source-channels) source-offset channel)))]
            (k/= (a/get dry (k/+ (k/* (k/+ start i) 2) channel)) value)
            (meter-input! value) (k/= peak (k/max peak (k/abs value)))
            (when (k/!= output k/null) (k/= (a/get output (k/+ (k/* i 16) channel)) value))))))
    (k/atomicStore :u32 (k/& input-level) (k/intFromFloat (k/* 1000.0 (k/sqrt (k/min peak 1.0)))) :.release)
    (update-signal-level! true peak)
    (k/atomicStore :u64 (k/& source-frames) (k/+ start count) :.release)
    (when (or stopping (k/>= (k/+ start count) (k/- max-frames live-tail)))
      (k/atomicStore :u8 (k/& source-ended) 1 :.release))))

(a/defn source-callback :void {:zig/qualifiers "callconv(.c)"}
  [[device-pointer [:c-pointer Device]] [output [:optional [:* :anyopaque]]]
   [input [:optional [:*const :anyopaque]]] [frames :u32]]
  (k/= :_ device-pointer)
  (process-source! (k/ptrCast (k/alignCast output))
                   (k/ptrCast (k/alignCast (k/constCast input))) frames))

(a/defn finish-live! :void []
  (k/atomicStore :u8 (k/& source-stop) 1 :.release))

(a/defn tail-active? :bool []
  (and running
       (or (and (k/== mode 3)
                (or (k/!= (k/atomicLoad :u8 (k/& source-stop) :.acquire) 0)
                    (k/!= (k/atomicLoad :u8 (k/& source-ended) :.acquire) 0)))
           (and (k/== mode 2) (k/>= (k/atomicLoad :u64 (k/& recorded-frames) :.acquire) dry-frames)))))

(a/defn set-path! :bool [[path [:slice-const :u8]]]
  (when (or (k/== (:len path) 0) (k/>= (:len path) 4096)) (k/return false))
  (dotimes [i (:len path)] (when (k/== (a/get path i) 0) (k/return false)))
  (k/memcpy (a/slice path-buffer 0 (:len path)) path)
  (k/= (a/get path-buffer (:len path)) 0)
  true)

(a/defn validate-take! :bool
  "Offline bounded decode. Refuse silence, clipping and non-finite samples before publication." [[path [:slice-const :u8]]]
  (when (or running (k/! (set-path! path))) (k/return false))
  (k/= measured-frames 0) (k/= measured-peak 0.0)
  (let [config ((:ma_decoder_config_init api) (:ma_format_f32 api) 2 48000)
        decoder (k/var (mem/zeroes Decoder))
        length (k/var (k/u64 0))
        samples (k/var (k/as k/undefined [:array 4096 :f32]))]
    (when (k/!= ((:ma_decoder_init_file api) (k/& path-buffer) (k/& config) (k/& decoder)) 0)
      (k/return false))
    (k/defer (k/= :_ ((:ma_decoder_uninit api) (k/& decoder))))
    (when (or (k/!= ((:ma_decoder_get_length_in_pcm_frames api) (k/& decoder) (k/& length)) 0)
              (k/== length 0) (k/> length max-frames)) (k/return false))
    (k/while (k/< measured-frames length)
      (let [read (k/var (k/u64 0))
            wanted (k/min (k/as 2048 :u64) (k/- length measured-frames))]
        (k/= :_ ((:ma_decoder_read_pcm_frames api) (k/& decoder) (k/& samples) wanted (k/& read)))
        (when (k/!= read wanted) (k/return false))
        (dotimes [i (k/* read 2)]
          (let [v (k/abs (a/get samples i))]
            ;; NaN fails this comparison too.
            (when (k/! (k/< v 1.0)) (k/return false))
            (k/= measured-peak (k/max measured-peak v))))
        (k/= measured-frames (k/+ measured-frames read))))
    (k/> measured-peak 0.00001)))

(a/defn initialize! :bool []
  (when initialized (k/return true))
  (k/= error-code ((:ma_context_init api) k/null 0 k/null (k/& context)))
  (when (k/!= error-code 0) (k/return false))
  (k/= error-code ((:ma_context_get_devices api) (k/& context)
                                                 (k/& playback-info) (k/& playback-count) (k/& capture-info) (k/& capture-count)))
  (when (k/!= error-code 0)
    (k/= :_ ((:ma_context_uninit api) (k/& context))) (k/return false))
  (k/= initialized true)
  true)

(a/defn process-block! :void
  "Mode 1 captures dry. Mode 2 sends a dry take. Modes 2/3 retain only return 3/4." [[output [:c-pointer :f32]] [input [:c-pointer :f32]] [frames :u32]]
  (when (capture-held?)
    (silence-preparing-output! output frames)
    (k/return))
  (let [start (k/atomicLoad :u64 (k/& recorded-frames) :.monotonic)
        peak (k/var (k/f32 0.0))
        sent-peak (k/var (k/f32 0.0))]
    (when (and (k/== mode 3) (k/! tail-started)
               (k/!= (k/atomicLoad :u8 (k/& source-ended) :.acquire) 0))
      (k/= tail-started true) (k/= limit-frames (k/min max-frames (k/+ start live-tail))))
    (dotimes [i frames]
      (let [position (k/+ start i)]
        (when (and (k/== mode 2) (k/!= output k/null))
          (dotimes [channel 16] (k/= (a/get output (k/+ (k/* i 16) channel)) 0.0))
          (when (and (k/< position dry-frames) (k/< position limit-frames))
            (k/= sent-peak (k/max sent-peak (k/abs (a/get dry (k/* position 2)))
                                  (k/abs (a/get dry (k/+ (k/* position 2) 1)))))
            (k/= (a/get output (k/* i 16)) (a/get dry (k/* position 2)))
            (k/= (a/get output (k/+ (k/* i 16) 1)) (a/get dry (k/+ (k/* position 2) 1)))))
        (when (k/< position limit-frames)
          (dotimes [channel 2]
            (let [value (if (k/== input k/null) 0.0
                            (a/get input (k/+ (k/* i (if (k/>= mode 2) (k/as 16 :usize) 2))
                                               (if (k/>= mode 2) (k/as 2 :usize) 0) channel)))]
              (when (k/== mode 1) (k/= (a/get dry (k/+ (k/* position 2) channel)) value))
              (when (k/>= mode 2) (k/= (a/get wet (k/+ (k/* position 2) channel)) value))
              (meter-input! value)
              (k/= peak (k/max peak (k/abs value))))))))
    ;; Square-root display scale keeps quiet returns visible; not a dB measurement.
    (when (k/>= mode 2) (update-signal-level! false peak))
    (when (k/< mode 3) (update-signal-level! true (if (k/== mode 1) peak sent-peak)))
    (k/atomicStore :u32 (k/& peak-milli) (k/intFromFloat (k/* (k/sqrt (k/min peak 1.0)) 1000.0)) :.release)
    (when (k/< mode 3)
      (k/atomicStore :u32 (k/& input-level)
                     (k/intFromFloat (k/* (k/sqrt (k/min (if (k/== mode 1) peak sent-peak) 1.0)) 1000.0)) :.release))
    (k/atomicStore :u64 (k/& recorded-frames) (k/min (k/+ start frames) limit-frames) :.release)
    (when (k/>= (k/+ start frames) limit-frames) (k/atomicStore :u8 (k/& finished) 1 :.release))))

(a/defn data-callback :void {:zig/qualifiers "callconv(.c)"}
  [[device-pointer [:c-pointer Device]] [output [:optional [:* :anyopaque]]]
   [input [:optional [:*const :anyopaque]]] [frames :u32]]
  (k/= :_ device-pointer)
  (process-block! (k/ptrCast (k/alignCast output))
                  (k/ptrCast (k/alignCast (k/constCast input))) frames))

(a/defn stop! :void []
  (stop-input-check!)
  (stop-monitor!)
  (when source-running
    ((:ma_device_uninit api) (k/& source-device))
    (k/= source-running false)
    (k/= dry-frames (k/atomicLoad :u64 (k/& source-frames) :.acquire)))
  (when running
    ((:ma_device_uninit api) (k/& device))
    (k/= running false)
    (when (k/== mode 1) (k/= dry-frames (k/atomicLoad :u64 (k/& recorded-frames) :.acquire))))
  (release-capture!))

(a/defn start! :bool
  "Explicit device indices only. Mode 1 microphone; mode 2 sixteen-channel effects round-trip." [[capture-index :u32] [playback-index :u32] [capture-mode :u32] [tail-frames :u32]]
  (when (or running (input-check-active?) (k/! initialized) (k/>= capture-index capture-count)
            (and (k/!= capture-mode 1) (k/!= capture-mode 2))
            (and (k/== capture-mode 2)
                 (or (k/>= playback-index playback-count) (k/== dry-frames 0)
                     (k/> (k/+ dry-frames tail-frames) max-frames)))) (k/return false))
  (when (and (k/== capture-mode 2)
             (or (k/! (mem/eql :u8 (device-name true capture-index) "BlackHole 16ch"))
                 (k/! (mem/eql :u8 (device-name false playback-index) "BlackHole 16ch"))))
    (k/return false))
  (k/= mode capture-mode)
  (reset-meters!)
  (k/= limit-frames (if (k/== mode 1) max-frames (k/+ dry-frames tail-frames)))
  (k/atomicStore :u64 (k/& recorded-frames) 0 :.release)
  (k/atomicStore :u8 (k/& finished) 0 :.release)
  (let [config (k/var ((:ma_device_config_init api)
                       (if (k/== mode 1) (:ma_device_type_capture api) (:ma_device_type_duplex api))))]
    (a/merge! config {:sampleRate 48000 :dataCallback (k/& data-callback)})
    (a/merge! (:capture config)
               {:pDeviceID (k/& (:id (a/get capture-info capture-index)))
                :format (:ma_format_f32 api)
                :channels (if (k/== mode 1) 2 16)})
    (when (k/== mode 2)
      (a/merge! (:playback config)
                 {:pDeviceID (k/& (:id (a/get playback-info playback-index)))
                  :format (:ma_format_f32 api)
                  :channels 16}))
    (k/= error-code ((:ma_device_init api) (k/& context) (k/& config) (k/& device)))
    (when (k/!= error-code 0) (k/return false))
    (k/= error-code ((:ma_device_start api) (k/& device)))
    (when (k/!= error-code 0) ((:ma_device_uninit api) (k/& device)) (k/return false)))
  (k/= running true) true)

(a/defn start-live! :bool
  "Capture source and effects return concurrently. BlackHole source uses 5/6 for safe virtual input." [[microphone-index :u32] [return-index :u32] [send-index :u32] [tail-frames :u32]]
  (when (or running source-running (input-check-active?) (k/! initialized)
            (k/>= microphone-index capture-count) (k/>= return-index capture-count)
            (k/>= send-index playback-count) (k/> tail-frames 480000)
            (k/! (mem/eql :u8 (device-name true return-index) "BlackHole 16ch"))
            (k/! (mem/eql :u8 (device-name false send-index) "BlackHole 16ch")))
    (k/return false))
  (k/= mode 3) (k/= limit-frames max-frames) (k/= live-tail tail-frames)
  (reset-meters!)
  (k/= tail-started false) (k/= dry-frames 0)
  (k/= source-channels (if (mem/eql :u8 (device-name true microphone-index) "BlackHole 16ch") 16 2))
  (k/= source-offset (if (k/== source-channels 16) 4 0))
  (k/atomicStore :u64 (k/& source-frames) 0 :.release)
  (k/atomicStore :u64 (k/& recorded-frames) 0 :.release)
  (k/atomicStore :u8 (k/& source-stop) 0 :.release)
  (k/atomicStore :u8 (k/& source-ended) 0 :.release)
  (k/atomicStore :u8 (k/& finished) 0 :.release)
  (let [config (k/var ((:ma_device_config_init api) (:ma_device_type_capture api)))]
    (a/merge! config {:sampleRate 48000 :dataCallback (k/& data-callback)})
    (a/merge! (:capture config)
               {:pDeviceID (k/& (:id (a/get capture-info return-index)))
                :format (:ma_format_f32 api)
                :channels 16})
    (k/= error-code ((:ma_device_init api) (k/& context) (k/& config) (k/& device)))
    (when (k/!= error-code 0) (k/return false))
    (k/= error-code ((:ma_device_start api) (k/& device)))
    (when (k/!= error-code 0) ((:ma_device_uninit api) (k/& device)) (k/return false)))
  (k/= running true)
  (let [config (k/var ((:ma_device_config_init api) (:ma_device_type_duplex api)))]
    (a/merge! config {:sampleRate 48000 :dataCallback (k/& source-callback)})
    (a/merge! (:capture config)
               {:pDeviceID (k/& (:id (a/get capture-info microphone-index)))
                :format (:ma_format_f32 api)
                :channels (k/intCast source-channels)})
    (a/merge! (:playback config)
               {:pDeviceID (k/& (:id (a/get playback-info send-index)))
                :format (:ma_format_f32 api)
                :channels 16})
    (k/= error-code ((:ma_device_init api) (k/& context) (k/& config) (k/& source-device)))
    (when (k/!= error-code 0) (stop!) (k/return false))
    (k/= error-code ((:ma_device_start api) (k/& source-device)))
    (when (k/!= error-code 0)
      ((:ma_device_uninit api) (k/& source-device)) (stop!) (k/return false)))
  (k/= source-running true) true)

(a/defn load-dry! :bool [[path [:slice-const :u8]]]
  (when (or running (k/! (set-path! path))) (k/return false))
  (k/= dry-frames 0)
  (let [config (k/var ((:ma_decoder_config_init api) (:ma_format_f32 api) 2 48000))
        decoder (k/var (mem/zeroes Decoder))
        length (k/var (k/u64 0))]
    (when (k/!= ((:ma_decoder_init_file api) (k/& path-buffer) (k/& config) (k/& decoder)) 0)
      (k/return false))
    (k/defer (k/= :_ ((:ma_decoder_uninit api) (k/& decoder))))
    (when (or (k/!= ((:ma_decoder_get_length_in_pcm_frames api) (k/& decoder) (k/& length)) 0)
              (k/== length 0) (k/> length (k/- max-frames 48000))) (k/return false))
    (k/= error-code ((:ma_decoder_read_pcm_frames api) (k/& decoder) (k/& dry) length (k/& dry-frames)))
    (and (k/== error-code 0) (k/== length dry-frames))))

(a/defn write-take! :bool [[path [:slice-const :u8]] [processed :bool]]
  (when (or running (k/! (set-path! path))) (k/return false))
  (let [frames (if processed (k/atomicLoad :u64 (k/& recorded-frames) :.acquire) dry-frames)
        config (k/var ((:ma_encoder_config_init api) (:ma_encoding_format_wav api)
                                                     (:ma_format_f32 api) 2 48000))
        encoder (k/var (mem/zeroes Encoder)) written (k/var (k/u64 0))]
    (when (or (k/== frames 0) (k/> frames max-frames) (and processed (k/< mode 2))) (k/return false))
    (when (k/!= ((:ma_encoder_init_file api) (k/& path-buffer) (k/& config) (k/& encoder)) 0)
      (k/return false))
    (k/= error-code ((:ma_encoder_write_pcm_frames api) (k/& encoder)
                                                        (if processed (k/& wet) (k/& dry)) frames (k/& written)))
    (k/= :_ ((:ma_encoder_uninit api) (k/& encoder)))
    (and (k/== error-code 0) (k/== written frames))))

(a/defn frames-recorded :u64 [] (k/atomicLoad :u64 (k/& recorded-frames) :.acquire))

(a/defn done? :bool [] (k/!= (k/atomicLoad :u8 (k/& finished) :.acquire) 0))

(a/defn level :u32 [] (k/atomicLoad :u32 (k/& peak-milli) :.acquire))

(a/defn available-frames :u64 [[processed :bool]]
  (if processed (frames-recorded)
      (if (k/== mode 3) (k/atomicLoad :u64 (k/& source-frames) :.acquire)
          (if (k/== mode 1) (frames-recorded) dry-frames))))

(a/defn journal! :u64
  "Worker-only durable PCM append. Read only the release-published, immutable prefix." [[path [:slice-const :u8]] [processed :bool] [from :u64]]
  (let [end (available-frames processed)]
    (when (or (k/> end max-frames) (k/> from end) (k/! (set-path! path))) (k/return 0))
    (when (k/== from end) (k/return end))
    (let [file ((:fopen file-api) (k/& path-buffer) "r+b")]
      (when (k/== file k/null) (k/return 0))
      (k/defer (k/= :_ ((:fclose file-api) file)))
      (when (k/!= ((:fseek file-api) file (k/intCast (k/* from 8)) 0) 0) (k/return 0))
      (let [written ((:fwrite file-api)
                     (k/& (a/get (if processed wet dry) (k/* from 2))) 8 (k/intCast (k/- end from)) file)]
        (when (or (k/!= written (k/- end from)) (k/!= ((:fflush file-api) file) 0)
                  (k/!= ((:fsync file-api) ((:fileno file-api) file)) 0)) (k/return 0))
        end))))

(a/defn wave-bin :f32 [[processed :bool] [bin :u32]]
  (when (k/>= bin 128) (k/return 0.0))
  (let [frames (available-frames processed)
        start (k// (k/* frames bin) 128)
        end (k// (k/* frames (k/+ bin 1)) 128)
        step (k/max 1 (k// (k/- end start) 64))
        peak (k/var (k/f32 0.0)) i (k/var start)]
    (k/while (k/< i end)
      (k/= peak (k/max peak (k/abs (a/get (if processed wet dry) (k/* i 2)))))
      (k/= i (k/+ i step)))
    (k/min peak 1.0)))

(a/defn shutdown! :void []
  (stop!)
  (when initialized (k/= :_ ((:ma_context_uninit api) (k/& context))) (k/= initialized false)))

(a/defn routing-test! :bool
  "No device/microphone: test channel isolation, tail silence and frame bounds." []
  (when running (k/return false))
  (let [input (k/var (k/as (mem/zeroes [:array 64 :f32]) [:array 64 :f32]))
        output (k/var (k/as k/undefined [:array 64 :f32]))]
    (k/= mode 2) (k/= dry-frames 2) (k/= limit-frames 3)
    (k/= (a/get dry 0) 0.1) (k/= (a/get dry 1) 0.2)
    (k/= (a/get dry 2) 0.3) (k/= (a/get dry 3) 0.4)
    (k/= (a/get input 0) 0.9) ; Send channel must never be mistaken for return.
    (k/= (a/get input 2) 0.125) (k/= (a/get input 3) 0.25)
    (k/= (a/get wet 6) 0.75) ; Sentinel immediately after the capture bound.
    (k/atomicStore :u64 (k/& recorded-frames) 0 :.release)
    (k/atomicStore :u8 (k/& finished) 0 :.release)
    (process-block! (k/& output) (k/& input) 4)
    (when (or (k/!= (a/get output 0) 0.1) (k/!= (a/get output 1) 0.2)
              (k/!= (a/get wet 0) 0.125) (k/!= (a/get wet 1) 0.25)
              (k/!= (a/get wet 6) 0.75) (k/!= (frames-recorded) 3) (k/! (done?)))
      (k/return false))
    (dotimes [i 4]
      (dotimes [channel 16]
        (when (and (or (k/>= i 2) (k/>= channel 2))
                   (k/!= (a/get output (k/+ (k/* i 16) channel)) 0.0)) (k/return false))))
    (k/= dry-frames 0) true))

(a/defn live-routing-test! :bool
  "Offline live callbacks: distinct buses, dry retention, stop silence, bounded return tail." []
  (when (or running source-running) (k/return false))
  (let [input (k/var (k/as (mem/zeroes [:array 64 :f32]) [:array 64 :f32]))
        output (k/var (k/as k/undefined [:array 64 :f32]))]
    (k/= mode 3) (k/= source-channels 16) (k/= source-offset 4)
    (k/= live-tail 2) (k/= limit-frames max-frames) (k/= tail-started false)
    (k/atomicStore :u64 (k/& source-frames) 0 :.release)
    (k/atomicStore :u64 (k/& recorded-frames) 0 :.release)
    (k/atomicStore :u8 (k/& source-stop) 0 :.release)
    (k/atomicStore :u8 (k/& source-ended) 0 :.release)
    (k/atomicStore :u8 (k/& finished) 0 :.release)
    (dotimes [i 4]
      (k/= (a/get input (k/* i 16)) 0.9)
      (k/= (a/get input (k/+ (k/* i 16) 2)) 0.125)
      (k/= (a/get input (k/+ (k/* i 16) 3)) 0.25)
      (k/= (a/get input (k/+ (k/* i 16) 4)) 0.5)
      (k/= (a/get input (k/+ (k/* i 16) 5)) 0.75))
    (process-source! (k/& output) (k/& input) 4)
    (process-block! k/null (k/& input) 4)
    (dotimes [i 4]
      (when (or (k/!= (a/get dry (k/* i 2)) 0.5)
                (k/!= (a/get dry (k/+ (k/* i 2) 1)) 0.75)
                (k/!= (a/get wet (k/* i 2)) 0.125)
                (k/!= (a/get wet (k/+ (k/* i 2) 1)) 0.25)) (k/return false))
      (dotimes [channel 16]
        (when (k/!= (a/get output (k/+ (k/* i 16) channel))
                    (k/as (if (k/== channel 0) 0.5 (if (k/== channel 1) 0.75 0.0)) :f32))
          (k/return false))))
    (finish-live!)
    (process-source! (k/& output) (k/& input) 4)
    (dotimes [i 64] (when (k/!= (a/get output i) 0.0) (k/return false)))
    (k/= (a/get wet 12) 0.875)
    (process-block! k/null (k/& input) 4)
    (when (or (k/!= (frames-recorded) 6) (k/! (done?))
              (k/!= (a/get wet 12) 0.875)
              (k/!= (k/atomicLoad :u64 (k/& source-frames) :.acquire) 4))
      (k/return false))
    ;; Source at capacity must neither overrun nor keep sending after the limit.
    (k/atomicStore :u64 (k/& source-frames) (k/- max-frames live-tail 1) :.release)
    (k/atomicStore :u8 (k/& source-stop) 0 :.release)
    (k/atomicStore :u8 (k/& source-ended) 0 :.release)
    (process-source! (k/& output) (k/& input) 4)
    (when (or (k/!= (k/atomicLoad :u64 (k/& source-frames) :.acquire) (k/- max-frames live-tail))
              (k/== (k/atomicLoad :u8 (k/& source-ended) :.acquire) 0)) (k/return false))
    (dotimes [i 48] (when (k/!= (a/get output (k/+ 16 i)) 0.0) (k/return false)))
    ;; Real microphone configuration uses stereo 1/2, not the virtual source 5/6.
    (k/= source-channels 2) (k/= source-offset 0)
    (k/atomicStore :u64 (k/& source-frames) 0 :.release)
    (process-source! (k/& output) (k/& input) 1)
    (when (or (k/!= (a/get dry 0) 0.9) (k/!= (a/get dry 1) 0.0)
              (k/!= (a/get output 0) 0.9) (k/!= (a/get output 1) 0.0)) (k/return false))
    (process-source! k/null k/null 1)
    (when (or (k/!= (a/get dry 2) 0.0) (k/!= (a/get dry 3) 0.0)) (k/return false))
    ;; Retain a tiny valid paired take for the codec test.
    (k/= dry-frames 4) true))
