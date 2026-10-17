(ns la-professeure.tools.recorder
  "Native development recorder. Audio callbacks never enter the JVM or touch disk."
  (:refer-clojure :only [])
  (:require [aguafria.c :as ac]
            [clojure.java.io :as io]
            [aguafria.std]
            [aguafria.std.mem :as mem]
            [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]
            [la-professeure.miniaudio :as audio]
            [la-professeure.build :as build]
            [clojure.core :as c]))

(a/configure! {:module-zig-args
               (c/assoc (:module-zig-args (a/configuration)) "la-professeure.tools.recorder"
                        [(c/str "-I" (clojure.java.io/file (build/root) "build/vendor/miniaudio"))])})

(a/defconst api audio/c-api)

(a/defconst file-api
  (import (a/clj! (ac/import! "la_professeure_recorder_io"
                              (io/file (io/resource "native/recorder_io.h"))
                              {:args ["-lc"]}))))

(a/defconst Context (:ma_context api))

(a/defconst Device (:ma_device api))

(a/defconst DeviceInfo (:ma_device_info api))

(a/defconst Decoder (:ma_decoder api))

(a/defconst Encoder (:ma_encoder api))

(a/defconst max-frames :usize 2880000)

(a/defvar context Context undefined)

(a/defvar device Device undefined)

(a/defvar initialized false)

(a/defvar running false)

(a/defvar capture-enabled :u8 1)

(a/defn hold-capture! :void []
  ;; Devices may open asynchronously while PREPARING remains cancellable.
  ;; Neither input samples nor effects sends belong to a take until committed.
  (atomicStore :u8 (k/& capture-enabled) 0 :release))

(a/defn release-capture! :void []
  (atomicStore :u8 (k/& capture-enabled) 1 :release))

(a/defn capture-held? :bool []
  (== (atomicLoad :u8 (k/& capture-enabled) :acquire) 0))

(a/defn- silence-preparing-output! :void
  [[output [:c-pointer :f32]] [frames :u32]]
  (when (!= output null)
    (dotimes [i (* frames 16)]
      (= (a/get output i) 0.0))))

(a/defvar playback-info [:c-pointer DeviceInfo] null)

(a/defvar capture-info [:c-pointer DeviceInfo] null)

(a/defvar playback-count :u32 0)

(a/defvar capture-count :u32 0)

(a/defn device-name [:slice-const :u8] [[capture :bool] [index :u32]]
  (when (or (! initialized) (>= index (if capture capture-count playback-count))) (return ""))
  (let [info (if capture (a/get capture-info index) (a/get playback-info index))
        length (k/var (usize 0))]
    (while (and (< length 256) (!= (a/get (:name info) length) 0))
      (= length (+ length 1)))
    ;; Return the context-owned array, not the local struct copy.
    (let [entry
          (as (ptrCast (a/unwrap (as (k/& (a/get (if capture capture-info playback-info) index)) [:c-pointer DeviceInfo]))) [:* DeviceInfo])]
      (a/slice (:name entry) 0 length))))

(a/defvar dry [:array 5760000 :f32] undefined)

(a/defvar wet [:array 5760000 :f32] undefined)

(a/defvar dry-frames :u64 0)

(a/defvar recorded-frames :u64 0)

(a/defvar limit-frames :u64 0)

(a/defvar mode :u32 0)

(a/defvar peak-milli :u32 0)

(a/defvar finished :u8 0)

(a/defvar error-code :i32 0)

(a/defvar path-buffer [:array 4096 :u8] undefined)

(a/defvar measured-frames :u64 0)

(a/defvar measured-peak :f32 0.0)

(a/defvar source-device Device undefined)

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

(a/defvar monitor-device Device undefined)

(a/defvar monitoring false)

(a/defvar monitor-gain :u32 15)

(a/defvar input-peak-ppm :u32 0)

(a/defvar return-peak-ppm :u32 0)

(a/defvar input-held-ppm :u32 0)

(a/defvar return-held-ppm :u32 0)

;; Preflight owns only a capture device and peak counters: no PCM retention or output.
(a/defvar input-check-device Device undefined)

(a/defvar input-check-running :u32 0)

(a/defvar input-check-channels :u32 2)

(a/defvar input-check-offset :u32 0)

(a/defvar input-check-peak :u32 0)

(a/defvar input-check-held :u32 0)

(a/defvar input-check-frames :u64 0)

(a/defn input-check-active? :bool []
  (> (atomicLoad :u32 (k/& input-check-running) :acquire) 0))

(a/defn stopped-device-mask :u32
  "Control-worker only. Owned devices that unexpectedly stopped: capture=1,
   FX source/send=2, monitor=4, input check=8. Never probes uninitialized storage." []
  (let [stopped (:ma_device_state_stopped api)
        result (k/var (u32 0))]
    (when (and running
               (== ((:ma_device_get_state api) (k/& device)) stopped))
      (= result (| result 1)))
    (when (and source-running
               (== ((:ma_device_get_state api) (k/& source-device)) stopped))
      (= result (| result 2)))
    (when (and monitoring
               (== ((:ma_device_get_state api) (k/& monitor-device)) stopped))
      (= result (| result 4)))
    (when (and (input-check-active?)
               (== ((:ma_device_get_state api) (k/& input-check-device)) stopped))
      (= result (| result 8)))
    result))

(a/defn process-input-check! :void
  [[input [:c-pointer :f32]] [frames :u32]]
  (let [peak (k/var (f32 0.0))]
    (when (!= input null)
      (dotimes [i frames]
        (dotimes [channel 2]
          (let [sample (a/get input (+ (* i input-check-channels)
                                       input-check-offset channel))]
            (when (< (abs sample) 100.0)
              (= peak (max peak (abs sample))))))))
    (let [value (as (intFromFloat (* 1000000.0 (min peak 1.0))) :u32)]
      (atomicStore :u32 (k/& input-check-peak) value :release)
      (when (> value (atomicLoad :u32 (k/& input-check-held) :acquire))
        (atomicStore :u32 (k/& input-check-held) value :release)))
    (= :_ (atomicRmw :u64 (k/& input-check-frames) :Add frames :release))))

(a/defn input-check-callback :void {:callconv :c}
  [[pointer [:c-pointer Device]] [output [:optional [:* :anyopaque]]]
   [input [:optional [:*const :anyopaque]]] [frames :u32]]
  (= :_ pointer)
  (= :_ output)
  (process-input-check! (ptrCast (alignCast (constCast input))) frames))

(a/defn stop-input-check! :void []
  ;; Lifecycle calls are serialized by the studio's control worker.
  (when (input-check-active?)
    (atomicStore :u32 (k/& input-check-running) 0 :release)
    ((:ma_device_uninit api) (k/& input-check-device))))

(a/defn start-input-check! :bool [[index :u32] [fx-source? :bool]]
  (when (or (input-check-active?) running source-running monitoring
            (! initialized) (>= index capture-count))
    (return false))
  (let [loopback? (and fx-source?
                       (mem/eql :u8 (device-name true index) "BlackHole 16ch"))]
    (= input-check-channels (if loopback? 16 2))
    (= input-check-offset (if loopback? 4 0)))
  (atomicStore :u32 (k/& input-check-peak) 0 :release)
  (atomicStore :u32 (k/& input-check-held) 0 :release)
  (atomicStore :u64 (k/& input-check-frames) 0 :release)
  (let [config (k/var ((:ma_device_config_init api)
                       (:ma_device_type_capture api)))]
    (a/merge! config {:sampleRate 48000 :dataCallback (k/& input-check-callback)})
    (a/merge! (:capture config)
              {:pDeviceID (k/& (:id (a/get capture-info index)))
               :format (:ma_format_f32 api)
               :channels input-check-channels})
    (when (!= ((:ma_device_init api)
               (k/& context) (k/& config) (k/& input-check-device)) 0)
      (return false))
    (when (!= ((:ma_device_start api) (k/& input-check-device)) 0)
      ((:ma_device_uninit api) (k/& input-check-device))
      (return false)))
  (atomicStore :u32 (k/& input-check-running) 1 :release)
  true)

(a/defn update-signal-level! :void [[input? :bool] [peak :f32]]
  (let [value (as (intFromFloat (* 1000000.0 (min 1.0 (max 0.0 peak)))) :u32)
        current (if input? (k/& input-peak-ppm) (k/& return-peak-ppm))
        held (if input? (k/& input-held-ppm) (k/& return-held-ppm))]
    (atomicStore :u32 current value :release)
    (when (> value (atomicLoad :u32 held :acquire))
      (atomicStore :u32 held value :release))))

(a/defn signal-peak :f32 [[input? :bool] [held? :bool]]
  (let [value (if input?
                (if (input-check-active?)
                  (if held? (k/& input-check-held) (k/& input-check-peak))
                  (if held? (k/& input-held-ppm) (k/& input-peak-ppm)))
                (if held? (k/& return-held-ppm) (k/& return-peak-ppm)))]
    (* 0.000001 (as (floatFromInt (atomicLoad :u32 value :acquire)) :f32))))

(a/defn meter-input! :void [[value :f32]]
  (when (! (< (abs value) 1.0)) (atomicStore :u8 (k/& clipped) 1 :release)))

(a/defn reset-meters! :void []
  (atomicStore :u32 (k/& input-peak-ppm) 0 :release)
  (atomicStore :u32 (k/& return-peak-ppm) 0 :release)
  (atomicStore :u32 (k/& input-held-ppm) 0 :release)
  (atomicStore :u32 (k/& return-held-ppm) 0 :release)
  (atomicStore :u8 (k/& clipped) 0 :release)
  (atomicStore :u32 (k/& input-level) 0 :release)
  (atomicStore :u32 (k/& peak-milli) 0 :release))

(a/defn process-monitor! :void
  [[output [:c-pointer :f32]] [input [:c-pointer :f32]] [frames :u32]]
  (when (== output null) (return))
  (let [gain (* 0.01 (as (floatFromInt (min 50 (atomicLoad :u32 (k/& monitor-gain) :acquire))) :f32))
        peak (k/var (f32 0.0))]
    (dotimes [i frames]
      (dotimes [c 2]
        (let [v (if (== input null) 0.0 (a/get input (+ (* i 16) 2 c)))]
          (when (< (abs v) 100.0) (= peak (max peak (abs v))))
          (= (a/get output (+ (* i 2) c))
             (if (< (abs v) 100.0) (* gain (max -1.0 (min 1.0 v))) 0.0)))))
    ;; Monitoring also drives the return meter when no take is being captured.
    (update-signal-level! false peak)))

(a/defn monitor-callback :void {:callconv :c}
  [[pointer [:c-pointer Device]] [output [:optional [:* :anyopaque]]]
   [input [:optional [:*const :anyopaque]]] [frames :u32]]
  (= :_ pointer)
  (process-monitor! (ptrCast (alignCast output))
                    (ptrCast (alignCast (constCast input))) frames))

(a/defn stop-monitor! :void []
  (when monitoring ((:ma_device_uninit api) (k/& monitor-device)) (= monitoring false)))

(a/defn headphone-device? :bool [[index :u32]]
  (let [name (device-name false index)]
    (or (!= (mem/find :u8 name "Headphones") null)
        (!= (mem/find :u8 name "AirPods") null)
        (!= (mem/find :u8 name "Casque") null))))

(a/defn start-monitor! :bool [[return-index :u32] [headphone-index :u32]]
  (when (or monitoring (input-check-active?) (! initialized) (>= return-index capture-count)
            (>= headphone-index playback-count) (! (headphone-device? headphone-index))
            (! (mem/eql :u8 (device-name true return-index) "BlackHole 16ch")))
    (return false))
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
    (when (!= ((:ma_device_init api) (k/& context) (k/& config) (k/& monitor-device)) 0)
      (return false))
    (when (!= ((:ma_device_start api) (k/& monitor-device)) 0)
      ((:ma_device_uninit api) (k/& monitor-device)) (return false))
    (= monitoring true) true))

(a/defn process-source! :void
  "Live source callback: retain dry stereo, send only 1/2. Never read the return bus." [[output [:c-pointer :f32]] [input [:c-pointer :f32]] [frames :u32]]
  (when (capture-held?)
    (silence-preparing-output! output frames)
    (return))
  (let [start (atomicLoad :u64 (k/& source-frames) :monotonic)
        stopping (!= (atomicLoad :u8 (k/& source-stop) :acquire) 0)
        count (if stopping (as 0 :u64) (min (as frames :u64) (- max-frames live-tail start)))
        peak (k/var (f32 0.0))]
    (dotimes [i frames]
      (when (!= output null)
        (dotimes [channel 16] (= (a/get output (+ (* i 16) channel)) 0.0)))
      (when (< i count)
        (dotimes [channel 2]
          (let [value (if (== input null) 0.0
                          (a/get input (+ (* i source-channels) source-offset channel)))]
            (= (a/get dry (+ (* (+ start i) 2) channel)) value)
            (meter-input! value) (= peak (max peak (abs value)))
            (when (!= output null) (= (a/get output (+ (* i 16) channel)) value))))))
    (atomicStore :u32 (k/& input-level) (intFromFloat (* 1000.0 (sqrt (min peak 1.0)))) :release)
    (update-signal-level! true peak)
    (atomicStore :u64 (k/& source-frames) (+ start count) :release)
    (when (or stopping (>= (+ start count) (- max-frames live-tail)))
      (atomicStore :u8 (k/& source-ended) 1 :release))))

(a/defn source-callback :void {:callconv :c}
  [[device-pointer [:c-pointer Device]] [output [:optional [:* :anyopaque]]]
   [input [:optional [:*const :anyopaque]]] [frames :u32]]
  (= :_ device-pointer)
  (process-source! (ptrCast (alignCast output))
                   (ptrCast (alignCast (constCast input))) frames))

(a/defn finish-live! :void []
  (atomicStore :u8 (k/& source-stop) 1 :release))

(a/defn tail-active? :bool []
  (and running
       (or (and (== mode 3)
                (or (!= (atomicLoad :u8 (k/& source-stop) :acquire) 0)
                    (!= (atomicLoad :u8 (k/& source-ended) :acquire) 0)))
           (and (== mode 2) (>= (atomicLoad :u64 (k/& recorded-frames) :acquire) dry-frames)))))

(a/defn set-path! :bool [[path [:slice-const :u8]]]
  (when (or (== (:len path) 0) (>= (:len path) 4096)) (return false))
  (dotimes [i (:len path)] (when (== (a/get path i) 0) (return false)))
  (memcpy (a/slice path-buffer 0 (:len path)) path)
  (= (a/get path-buffer (:len path)) 0)
  true)

(a/defn validate-take! :bool
  "Offline bounded decode. Refuse silence, clipping and non-finite samples before publication." [[path [:slice-const :u8]]]
  (when (or running (! (set-path! path))) (return false))
  (= measured-frames 0) (= measured-peak 0.0)
  (let [config ((:ma_decoder_config_init api) (:ma_format_f32 api) 2 48000)
        decoder (k/var (mem/zeroes Decoder))
        length (k/var (u64 0))
        samples (k/var (as undefined [:array 4096 :f32]))]
    (when (!= ((:ma_decoder_init_file api) (k/& path-buffer) (k/& config) (k/& decoder)) 0)
      (return false))
    (defer (= :_ ((:ma_decoder_uninit api) (k/& decoder))))
    (when (or (!= ((:ma_decoder_get_length_in_pcm_frames api) (k/& decoder) (k/& length)) 0)
              (== length 0) (> length max-frames)) (return false))
    (while (< measured-frames length)
      (let [read (k/var (u64 0))
            wanted (min (as 2048 :u64) (- length measured-frames))]
        (= :_ ((:ma_decoder_read_pcm_frames api) (k/& decoder) (k/& samples) wanted (k/& read)))
        (when (!= read wanted) (return false))
        (dotimes [i (* read 2)]
          (let [v (abs (a/get samples i))]
            ;; NaN fails this comparison too.
            (when (! (< v 1.0)) (return false))
            (= measured-peak (max measured-peak v))))
        (= measured-frames (+ measured-frames read))))
    (> measured-peak 0.00001)))

(a/defn initialize! :bool []
  (when initialized (return true))
  (= error-code ((:ma_context_init api) null 0 null (k/& context)))
  (when (!= error-code 0) (return false))
  (= error-code ((:ma_context_get_devices api) (k/& context)
                                               (k/& playback-info) (k/& playback-count) (k/& capture-info) (k/& capture-count)))
  (when (!= error-code 0)
    (= :_ ((:ma_context_uninit api) (k/& context))) (return false))
  (= initialized true)
  true)

(a/defn process-block! :void
  "Mode 1 captures dry. Mode 2 sends a dry take. Modes 2/3 retain only return 3/4." [[output [:c-pointer :f32]] [input [:c-pointer :f32]] [frames :u32]]
  (when (capture-held?)
    (silence-preparing-output! output frames)
    (return))
  (let [start (atomicLoad :u64 (k/& recorded-frames) :monotonic)
        peak (k/var (f32 0.0))
        sent-peak (k/var (f32 0.0))]
    (when (and (== mode 3) (! tail-started)
               (!= (atomicLoad :u8 (k/& source-ended) :acquire) 0))
      (= tail-started true) (= limit-frames (min max-frames (+ start live-tail))))
    (dotimes [i frames]
      (let [position (+ start i)]
        (when (and (== mode 2) (!= output null))
          (dotimes [channel 16] (= (a/get output (+ (* i 16) channel)) 0.0))
          (when (and (< position dry-frames) (< position limit-frames))
            (= sent-peak (max sent-peak (abs (a/get dry (* position 2)))
                              (abs (a/get dry (+ (* position 2) 1)))))
            (= (a/get output (* i 16)) (a/get dry (* position 2)))
            (= (a/get output (+ (* i 16) 1)) (a/get dry (+ (* position 2) 1)))))
        (when (< position limit-frames)
          (dotimes [channel 2]
            (let [value (if (== input null) 0.0
                            (a/get input (+ (* i (if (>= mode 2) (as 16 :usize) 2))
                                            (if (>= mode 2) (as 2 :usize) 0) channel)))]
              (when (== mode 1) (= (a/get dry (+ (* position 2) channel)) value))
              (when (>= mode 2) (= (a/get wet (+ (* position 2) channel)) value))
              (meter-input! value)
              (= peak (max peak (abs value))))))))
    ;; Square-root display scale keeps quiet returns visible; not a dB measurement.
    (when (>= mode 2) (update-signal-level! false peak))
    (when (< mode 3) (update-signal-level! true (if (== mode 1) peak sent-peak)))
    (atomicStore :u32 (k/& peak-milli) (intFromFloat (* (sqrt (min peak 1.0)) 1000.0)) :release)
    (when (< mode 3)
      (atomicStore :u32 (k/& input-level)
                   (intFromFloat (* (sqrt (min (if (== mode 1) peak sent-peak) 1.0)) 1000.0)) :release))
    (atomicStore :u64 (k/& recorded-frames) (min (+ start frames) limit-frames) :release)
    (when (>= (+ start frames) limit-frames) (atomicStore :u8 (k/& finished) 1 :release))))

(a/defn data-callback :void {:callconv :c}
  [[device-pointer [:c-pointer Device]] [output [:optional [:* :anyopaque]]]
   [input [:optional [:*const :anyopaque]]] [frames :u32]]
  (= :_ device-pointer)
  (process-block! (ptrCast (alignCast output))
                  (ptrCast (alignCast (constCast input))) frames))

(a/defn stop! :void []
  (stop-input-check!)
  (stop-monitor!)
  (when source-running
    ((:ma_device_uninit api) (k/& source-device))
    (= source-running false)
    (= dry-frames (atomicLoad :u64 (k/& source-frames) :acquire)))
  (when running
    ((:ma_device_uninit api) (k/& device))
    (= running false)
    (when (== mode 1) (= dry-frames (atomicLoad :u64 (k/& recorded-frames) :acquire))))
  (release-capture!))

(a/defn start! :bool
  "Explicit device indices only. Mode 1 microphone; mode 2 sixteen-channel effects round-trip." [[capture-index :u32] [playback-index :u32] [capture-mode :u32] [tail-frames :u32]]
  (when (or running (input-check-active?) (! initialized) (>= capture-index capture-count)
            (and (!= capture-mode 1) (!= capture-mode 2))
            (and (== capture-mode 2)
                 (or (>= playback-index playback-count) (== dry-frames 0)
                     (> (+ dry-frames tail-frames) max-frames)))) (return false))
  (when (and (== capture-mode 2)
             (or (! (mem/eql :u8 (device-name true capture-index) "BlackHole 16ch"))
                 (! (mem/eql :u8 (device-name false playback-index) "BlackHole 16ch"))))
    (return false))
  (= mode capture-mode)
  (reset-meters!)
  (= limit-frames (if (== mode 1) max-frames (+ dry-frames tail-frames)))
  (atomicStore :u64 (k/& recorded-frames) 0 :release)
  (atomicStore :u8 (k/& finished) 0 :release)
  (let [config (k/var ((:ma_device_config_init api)
                       (if (== mode 1) (:ma_device_type_capture api) (:ma_device_type_duplex api))))]
    (a/merge! config {:sampleRate 48000 :dataCallback (k/& data-callback)})
    (a/merge! (:capture config)
              {:pDeviceID (k/& (:id (a/get capture-info capture-index)))
               :format (:ma_format_f32 api)
               :channels (if (== mode 1) 2 16)})
    (when (== mode 2)
      (a/merge! (:playback config)
                {:pDeviceID (k/& (:id (a/get playback-info playback-index)))
                 :format (:ma_format_f32 api)
                 :channels 16}))
    (= error-code ((:ma_device_init api) (k/& context) (k/& config) (k/& device)))
    (when (!= error-code 0) (return false))
    (= error-code ((:ma_device_start api) (k/& device)))
    (when (!= error-code 0) ((:ma_device_uninit api) (k/& device)) (return false)))
  (= running true) true)

(a/defn start-live! :bool
  "Capture source and effects return concurrently. BlackHole source uses 5/6 for safe virtual input." [[microphone-index :u32] [return-index :u32] [send-index :u32] [tail-frames :u32]]
  (when (or running source-running (input-check-active?) (! initialized)
            (>= microphone-index capture-count) (>= return-index capture-count)
            (>= send-index playback-count) (> tail-frames 480000)
            (! (mem/eql :u8 (device-name true return-index) "BlackHole 16ch"))
            (! (mem/eql :u8 (device-name false send-index) "BlackHole 16ch")))
    (return false))
  (= mode 3) (= limit-frames max-frames) (= live-tail tail-frames)
  (reset-meters!)
  (= tail-started false) (= dry-frames 0)
  (= source-channels (if (mem/eql :u8 (device-name true microphone-index) "BlackHole 16ch") 16 2))
  (= source-offset (if (== source-channels 16) 4 0))
  (atomicStore :u64 (k/& source-frames) 0 :release)
  (atomicStore :u64 (k/& recorded-frames) 0 :release)
  (atomicStore :u8 (k/& source-stop) 0 :release)
  (atomicStore :u8 (k/& source-ended) 0 :release)
  (atomicStore :u8 (k/& finished) 0 :release)
  (let [config (k/var ((:ma_device_config_init api) (:ma_device_type_capture api)))]
    (a/merge! config {:sampleRate 48000 :dataCallback (k/& data-callback)})
    (a/merge! (:capture config)
              {:pDeviceID (k/& (:id (a/get capture-info return-index)))
               :format (:ma_format_f32 api)
               :channels 16})
    (= error-code ((:ma_device_init api) (k/& context) (k/& config) (k/& device)))
    (when (!= error-code 0) (return false))
    (= error-code ((:ma_device_start api) (k/& device)))
    (when (!= error-code 0) ((:ma_device_uninit api) (k/& device)) (return false)))
  (= running true)
  (let [config (k/var ((:ma_device_config_init api) (:ma_device_type_duplex api)))]
    (a/merge! config {:sampleRate 48000 :dataCallback (k/& source-callback)})
    (a/merge! (:capture config)
              {:pDeviceID (k/& (:id (a/get capture-info microphone-index)))
               :format (:ma_format_f32 api)
               :channels (intCast source-channels)})
    (a/merge! (:playback config)
              {:pDeviceID (k/& (:id (a/get playback-info send-index)))
               :format (:ma_format_f32 api)
               :channels 16})
    (= error-code ((:ma_device_init api) (k/& context) (k/& config) (k/& source-device)))
    (when (!= error-code 0) (stop!) (return false))
    (= error-code ((:ma_device_start api) (k/& source-device)))
    (when (!= error-code 0)
      ((:ma_device_uninit api) (k/& source-device)) (stop!) (return false)))
  (= source-running true) true)

(a/defn load-dry! :bool [[path [:slice-const :u8]]]
  (when (or running (! (set-path! path))) (return false))
  (= dry-frames 0)
  (let [config (k/var ((:ma_decoder_config_init api) (:ma_format_f32 api) 2 48000))
        decoder (k/var (mem/zeroes Decoder))
        length (k/var (u64 0))]
    (when (!= ((:ma_decoder_init_file api) (k/& path-buffer) (k/& config) (k/& decoder)) 0)
      (return false))
    (defer (= :_ ((:ma_decoder_uninit api) (k/& decoder))))
    (when (or (!= ((:ma_decoder_get_length_in_pcm_frames api) (k/& decoder) (k/& length)) 0)
              (== length 0) (> length (- max-frames 48000))) (return false))
    (= error-code ((:ma_decoder_read_pcm_frames api) (k/& decoder) (k/& dry) length (k/& dry-frames)))
    (and (== error-code 0) (== length dry-frames))))

(a/defn write-take! :bool [[path [:slice-const :u8]] [processed :bool]]
  (when (or running (! (set-path! path))) (return false))
  (let [frames (if processed (atomicLoad :u64 (k/& recorded-frames) :acquire) dry-frames)
        config (k/var ((:ma_encoder_config_init api) (:ma_encoding_format_wav api)
                                                     (:ma_format_f32 api) 2 48000))
        encoder (k/var (mem/zeroes Encoder)) written (k/var (u64 0))]
    (when (or (== frames 0) (> frames max-frames) (and processed (< mode 2))) (return false))
    (when (!= ((:ma_encoder_init_file api) (k/& path-buffer) (k/& config) (k/& encoder)) 0)
      (return false))
    (= error-code ((:ma_encoder_write_pcm_frames api) (k/& encoder)
                                                      (if processed (k/& wet) (k/& dry)) frames (k/& written)))
    (= :_ ((:ma_encoder_uninit api) (k/& encoder)))
    (and (== error-code 0) (== written frames))))

(a/defn frames-recorded :u64 [] (atomicLoad :u64 (k/& recorded-frames) :acquire))

(a/defn done? :bool [] (!= (atomicLoad :u8 (k/& finished) :acquire) 0))

(a/defn level :u32 [] (atomicLoad :u32 (k/& peak-milli) :acquire))

(a/defn available-frames :u64 [[processed :bool]]
  (if processed (frames-recorded)
      (if (== mode 3) (atomicLoad :u64 (k/& source-frames) :acquire)
          (if (== mode 1) (frames-recorded) dry-frames))))

(a/defn journal! :u64
  "Worker-only durable PCM append. Read only the release-published, immutable prefix." [[path [:slice-const :u8]] [processed :bool] [from :u64]]
  (let [end (available-frames processed)]
    (when (or (> end max-frames) (> from end) (! (set-path! path))) (return 0))
    (when (== from end) (return end))
    (let [file ((:fopen file-api) (k/& path-buffer) "r+b")]
      (when (== file null) (return 0))
      (defer (= :_ ((:fclose file-api) file)))
      (when (!= ((:fseek file-api) file (intCast (* from 8)) 0) 0) (return 0))
      (let [written ((:fwrite file-api)
                     (k/& (a/get (if processed wet dry) (* from 2))) 8 (intCast (- end from)) file)]
        (when (or (!= written (- end from)) (!= ((:fflush file-api) file) 0)
                  (!= ((:fsync file-api) ((:fileno file-api) file)) 0)) (return 0))
        end))))

(a/defn wave-bin :f32 [[processed :bool] [bin :u32]]
  (when (>= bin 128) (return 0.0))
  (let [frames (available-frames processed)
        start (/ (* frames bin) 128)
        end (/ (* frames (+ bin 1)) 128)
        step (max 1 (/ (- end start) 64))
        peak (k/var (f32 0.0)) i (k/var start)]
    (while (< i end)
      (= peak (max peak (abs (a/get (if processed wet dry) (* i 2)))))
      (= i (+ i step)))
    (min peak 1.0)))

(a/defn shutdown! :void []
  (stop!)
  (when initialized (= :_ ((:ma_context_uninit api) (k/& context))) (= initialized false)))

(a/defn routing-test! :bool
  "No device/microphone: test channel isolation, tail silence and frame bounds." []
  (when running (return false))
  (let [input (k/var (as (mem/zeroes [:array 64 :f32]) [:array 64 :f32]))
        output (k/var (as undefined [:array 64 :f32]))]
    (= mode 2) (= dry-frames 2) (= limit-frames 3)
    (= (a/get dry 0) 0.1) (= (a/get dry 1) 0.2)
    (= (a/get dry 2) 0.3) (= (a/get dry 3) 0.4)
    (= (a/get input 0) 0.9) ; Send channel must never be mistaken for return.
    (= (a/get input 2) 0.125) (= (a/get input 3) 0.25)
    (= (a/get wet 6) 0.75) ; Sentinel immediately after the capture bound.
    (atomicStore :u64 (k/& recorded-frames) 0 :release)
    (atomicStore :u8 (k/& finished) 0 :release)
    (process-block! (k/& output) (k/& input) 4)
    (when (or (!= (a/get output 0) 0.1) (!= (a/get output 1) 0.2)
              (!= (a/get wet 0) 0.125) (!= (a/get wet 1) 0.25)
              (!= (a/get wet 6) 0.75) (!= (frames-recorded) 3) (! (done?)))
      (return false))
    (dotimes [i 4]
      (dotimes [channel 16]
        (when (and (or (>= i 2) (>= channel 2))
                   (!= (a/get output (+ (* i 16) channel)) 0.0)) (return false))))
    (= dry-frames 0) true))

(a/defn live-routing-test! :bool
  "Offline live callbacks: distinct buses, dry retention, stop silence, bounded return tail." []
  (when (or running source-running) (return false))
  (let [input (k/var (as (mem/zeroes [:array 64 :f32]) [:array 64 :f32]))
        output (k/var (as undefined [:array 64 :f32]))]
    (= mode 3) (= source-channels 16) (= source-offset 4)
    (= live-tail 2) (= limit-frames max-frames) (= tail-started false)
    (atomicStore :u64 (k/& source-frames) 0 :release)
    (atomicStore :u64 (k/& recorded-frames) 0 :release)
    (atomicStore :u8 (k/& source-stop) 0 :release)
    (atomicStore :u8 (k/& source-ended) 0 :release)
    (atomicStore :u8 (k/& finished) 0 :release)
    (dotimes [i 4]
      (= (a/get input (* i 16)) 0.9)
      (= (a/get input (+ (* i 16) 2)) 0.125)
      (= (a/get input (+ (* i 16) 3)) 0.25)
      (= (a/get input (+ (* i 16) 4)) 0.5)
      (= (a/get input (+ (* i 16) 5)) 0.75))
    (process-source! (k/& output) (k/& input) 4)
    (process-block! null (k/& input) 4)
    (dotimes [i 4]
      (when (or (!= (a/get dry (* i 2)) 0.5)
                (!= (a/get dry (+ (* i 2) 1)) 0.75)
                (!= (a/get wet (* i 2)) 0.125)
                (!= (a/get wet (+ (* i 2) 1)) 0.25)) (return false))
      (dotimes [channel 16]
        (when (!= (a/get output (+ (* i 16) channel))
                  (as (if (== channel 0) 0.5 (if (== channel 1) 0.75 0.0)) :f32))
          (return false))))
    (finish-live!)
    (process-source! (k/& output) (k/& input) 4)
    (dotimes [i 64] (when (!= (a/get output i) 0.0) (return false)))
    (= (a/get wet 12) 0.875)
    (process-block! null (k/& input) 4)
    (when (or (!= (frames-recorded) 6) (! (done?))
              (!= (a/get wet 12) 0.875)
              (!= (atomicLoad :u64 (k/& source-frames) :acquire) 4))
      (return false))
    ;; Source at capacity must neither overrun nor keep sending after the limit.
    (atomicStore :u64 (k/& source-frames) (- max-frames live-tail 1) :release)
    (atomicStore :u8 (k/& source-stop) 0 :release)
    (atomicStore :u8 (k/& source-ended) 0 :release)
    (process-source! (k/& output) (k/& input) 4)
    (when (or (!= (atomicLoad :u64 (k/& source-frames) :acquire) (- max-frames live-tail))
              (== (atomicLoad :u8 (k/& source-ended) :acquire) 0)) (return false))
    (dotimes [i 48] (when (!= (a/get output (+ 16 i)) 0.0) (return false)))
    ;; Real microphone configuration uses stereo 1/2, not the virtual source 5/6.
    (= source-channels 2) (= source-offset 0)
    (atomicStore :u64 (k/& source-frames) 0 :release)
    (process-source! (k/& output) (k/& input) 1)
    (when (or (!= (a/get dry 0) 0.9) (!= (a/get dry 1) 0.0)
              (!= (a/get output 0) 0.9) (!= (a/get output 1) 0.0)) (return false))
    (process-source! null null 1)
    (when (or (!= (a/get dry 2) 0.0) (!= (a/get dry 3) 0.0)) (return false))
    ;; Retain a tiny valid paired take for the codec test.
    (= dry-frames 4) true))
