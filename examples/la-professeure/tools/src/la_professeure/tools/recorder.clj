(ns la-professeure.tools.recorder
  "Native development recorder. Audio callbacks never enter the JVM or touch disk."
  (:require [aguafria.std] [aguafria.std.mem :as mem]
            [aguafria.keyword :as ak] [aguafria.zig :as az]
            [la-professeure.build :as build]))

(build/load-native!)
(az/configure! {:module-zig-args
                (assoc (:module-zig-args (az/configuration)) "la-professeure.tools.recorder"
                       [(str "-I" (clojure.java.io/file (build/root) "build/vendor/miniaudio"))])})
(require '[la-professeure.miniaudio :as audio])

(az/defconst api (ak/cImport (ak/cInclude "miniaudio.h")))
(az/defconst file-api (ak/cImport (do (ak/cInclude "stdio.h") (ak/cInclude "unistd.h"))))
(az/defconst Context (az/field api ma_context))
(az/defconst Device (az/field api ma_device))
(az/defconst DeviceInfo (az/field api ma_device_info))
(az/defconst Decoder (az/field api ma_decoder))
(az/defconst Encoder (az/field api ma_encoder))
(az/defconst max-frames :usize 2880000)
(az/defvar context Context ak/undefined)
(az/defvar device Device ak/undefined)
(az/defvar initialized false)
(az/defvar running false)
(az/defvar playback-info [:c-pointer DeviceInfo] ak/null)
(az/defvar capture-info [:c-pointer DeviceInfo] ak/null)
(az/defvar playback-count :u32 0)
(az/defvar capture-count :u32 0)
(az/defvar dry [:array 5760000 :f32] ak/undefined)
(az/defvar wet [:array 5760000 :f32] ak/undefined)
(az/defvar dry-frames :u64 0)
(az/defvar recorded-frames :u64 0)
(az/defvar limit-frames :u64 0)
(az/defvar mode :u32 0)
(az/defvar peak-milli :u32 0)
(az/defvar finished :u8 0)
(az/defvar error-code :i32 0)
(az/defvar path-buffer [:array 4096 :u8] ak/undefined)
(az/defvar measured-frames :u64 0)
(az/defvar measured-peak :f32 0.0)
(az/defvar source-device Device ak/undefined)
(az/defvar source-running false)
(az/defvar source-channels :usize 2)
(az/defvar source-offset :usize 0)
(az/defvar source-frames :u64 0)
(az/defvar source-stop :u8 0)
(az/defvar source-ended :u8 0)
(az/defvar live-tail :u64 48000)
(az/defvar tail-started false)
(az/defvar input-level :u32 0)
(az/defvar clipped :u8 0)
(az/defvar monitor-device Device ak/undefined)
(az/defvar monitoring false)
(az/defvar monitor-gain :u32 15)
(az/defvar input-peak-ppm :u32 0)
(az/defvar return-peak-ppm :u32 0)
(az/defvar input-held-ppm :u32 0)
(az/defvar return-held-ppm :u32 0)

(az/defn update-signal-level! :- :void [[input? :bool] [peak :f32]]
  (let [value (ak/as :u32 (ak/intFromFloat (* 1000000.0 (ak/min 1.0 (ak/max 0.0 peak)))))
        current (if input? (ak/& input-peak-ppm) (ak/& return-peak-ppm))
        held (if input? (ak/& input-held-ppm) (ak/& return-held-ppm))]
    (ak/atomicStore :u32 current value :.release)
    (when (> value (ak/atomicLoad :u32 held :.acquire))
      (ak/atomicStore :u32 held value :.release))))

(az/defn signal-peak :- :f32 [[input? :bool] [held? :bool]]
  (let [value (if input? (if held? (ak/& input-held-ppm) (ak/& input-peak-ppm))
                         (if held? (ak/& return-held-ppm) (ak/& return-peak-ppm)))]
    (* 0.000001 (ak/as :f32 (ak/floatFromInt (ak/atomicLoad :u32 value :.acquire))))))

(az/defn meter-input! :- :void [[value :f32]]
  (when (ak/! (< (ak/abs value) 1.0)) (ak/atomicStore :u8 (ak/& clipped) 1 :.release)))

(az/defn reset-meters! :- :void []
  (ak/atomicStore :u32 (ak/& input-peak-ppm) 0 :.release)
  (ak/atomicStore :u32 (ak/& return-peak-ppm) 0 :.release)
  (ak/atomicStore :u32 (ak/& input-held-ppm) 0 :.release)
  (ak/atomicStore :u32 (ak/& return-held-ppm) 0 :.release)
  (ak/atomicStore :u8 (ak/& clipped) 0 :.release)
  (ak/atomicStore :u32 (ak/& input-level) 0 :.release)
  (ak/atomicStore :u32 (ak/& peak-milli) 0 :.release))

(az/defn process-monitor! :- :void
  [[output [:c-pointer :f32]] [input [:c-pointer :f32]] [frames :u32]]
  (when (ak/== output ak/null) (ak/return))
  (let [gain (* 0.01 (ak/as :f32 (ak/floatFromInt (ak/min 50 (ak/atomicLoad :u32 (ak/& monitor-gain) :.acquire)))))]
    (dotimes [i frames]
      (dotimes [c 2]
        (let [v (if (ak/== input ak/null) 0.0 (az/index input (+ (* i 16) 2 c)))]
          (set! (az/index output (+ (* i 2) c))
                (if (< (ak/abs v) 100.0) (* gain (ak/max -1.0 (ak/min 1.0 v))) 0.0)))))))

(az/defn monitor-callback {:zig/qualifiers "callconv(.c)"} :- :void
  [[pointer [:c-pointer Device]] [output [:optional [:* :anyopaque]]]
   [input [:optional [:*const :anyopaque]]] [frames :u32]]
  (set! _ pointer)
  (process-monitor! (ak/ptrCast (ak/alignCast output))
                    (ak/ptrCast (ak/alignCast (ak/constCast input))) frames))

(az/defn stop-monitor! :- :void []
  (when monitoring ((az/field api ma_device_uninit) (ak/& monitor-device)) (set! monitoring false)))

(az/defn headphone-device? :- :bool [[index :u32]]
  (let [name (device-name false index)]
    (or (ak/!= (mem/indexOf (az/type :u8) name "Headphones") ak/null)
        (ak/!= (mem/indexOf (az/type :u8) name "AirPods") ak/null)
        (ak/!= (mem/indexOf (az/type :u8) name "Casque") ak/null))))

(az/defn start-monitor! :- :bool [[return-index :u32] [headphone-index :u32]]
  (when (or monitoring (ak/! initialized) (>= return-index capture-count)
            (>= headphone-index playback-count) (ak/! (headphone-device? headphone-index))
            (ak/! (mem/eql (az/type :u8) (device-name true return-index) "BlackHole 16ch")))
    (ak/return false))
  (let [^:var config ((az/field api ma_device_config_init) (az/field api ma_device_type_duplex))]
    (set! (az/field config sampleRate) 48000)
    (set! (az/field (az/field config capture) pDeviceID) (ak/& (az/field (az/index capture-info return-index) id)))
    (set! (az/field (az/field config capture) format) (az/field api ma_format_f32))
    (set! (az/field (az/field config capture) channels) 16)
    (set! (az/field (az/field config playback) pDeviceID) (ak/& (az/field (az/index playback-info headphone-index) id)))
    (set! (az/field (az/field config playback) format) (az/field api ma_format_f32))
    (set! (az/field (az/field config playback) channels) 2)
    (set! (az/field config dataCallback) (ak/& monitor-callback))
    (when (ak/!= ((az/field api ma_device_init) (ak/& context) (ak/& config) (ak/& monitor-device)) 0)
      (ak/return false))
    (when (ak/!= ((az/field api ma_device_start) (ak/& monitor-device)) 0)
      ((az/field api ma_device_uninit) (ak/& monitor-device)) (ak/return false))
    (set! monitoring true) true))

(az/defn process-source!
  "Live source callback: retain dry stereo, send only 1/2. Never read the return bus."
  :- :void [[output [:c-pointer :f32]] [input [:c-pointer :f32]] [frames :u32]]
  (let [start (ak/atomicLoad :u64 (ak/& source-frames) :.monotonic)
        stopping (ak/!= (ak/atomicLoad :u8 (ak/& source-stop) :.acquire) 0)
        count (if stopping (ak/as :u64 0) (ak/min (ak/as :u64 frames) (- max-frames live-tail start)))
        ^{:var :f32} peak 0.0]
    (dotimes [i frames]
      (when (ak/!= output ak/null)
        (dotimes [channel 16] (set! (az/index output (+ (* i 16) channel)) 0.0)))
      (when (< i count)
        (dotimes [channel 2]
          (let [value (if (ak/== input ak/null) 0.0
                        (az/index input (+ (* i source-channels) source-offset channel)))]
            (set! (az/index dry (+ (* (+ start i) 2) channel)) value)
            (meter-input! value) (set! peak (ak/max peak (ak/abs value)))
            (when (ak/!= output ak/null) (set! (az/index output (+ (* i 16) channel)) value))))))
    (ak/atomicStore :u32 (ak/& input-level) (ak/intFromFloat (* 1000.0 (ak/sqrt (ak/min peak 1.0)))) :.release)
    (update-signal-level! true peak)
    (ak/atomicStore :u64 (ak/& source-frames) (+ start count) :.release)
    (when (or stopping (>= (+ start count) (- max-frames live-tail)))
      (ak/atomicStore :u8 (ak/& source-ended) 1 :.release))))

(az/defn source-callback {:zig/qualifiers "callconv(.c)"} :- :void
  [[device-pointer [:c-pointer Device]] [output [:optional [:* :anyopaque]]]
   [input [:optional [:*const :anyopaque]]] [frames :u32]]
  (set! _ device-pointer)
  (process-source! (ak/ptrCast (ak/alignCast output))
                   (ak/ptrCast (ak/alignCast (ak/constCast input))) frames))

(az/defn finish-live! :- :void []
  (ak/atomicStore :u8 (ak/& source-stop) 1 :.release))

(az/defn tail-active? :- :bool []
  (and running
       (or (and (ak/== mode 3)
                (or (ak/!= (ak/atomicLoad :u8 (ak/& source-stop) :.acquire) 0)
                    (ak/!= (ak/atomicLoad :u8 (ak/& source-ended) :.acquire) 0)))
           (and (ak/== mode 2) (>= (ak/atomicLoad :u64 (ak/& recorded-frames) :.acquire) dry-frames)))))

(az/defn validate-take!
  "Offline bounded decode. Refuse silence, clipping and non-finite samples before publication."
  :- :bool [[path [:slice-const :u8]]]
  (when (or running (ak/! (set-path! path))) (ak/return false))
  (set! measured-frames 0) (set! measured-peak 0.0)
  (let [config ((az/field api ma_decoder_config_init) (az/field api ma_format_f32) 2 48000)
        ^:var decoder (mem/zeroes (az/type Decoder))
        ^{:var :u64} length 0
        ^{:var [:array 4096 :f32]} samples ak/undefined]
    (when (ak/!= ((az/field api ma_decoder_init_file) (ak/& path-buffer) (ak/& config) (ak/& decoder)) 0)
      (ak/return false))
    (ak/defer (set! _ ((az/field api ma_decoder_uninit) (ak/& decoder))))
    (when (or (ak/!= ((az/field api ma_decoder_get_length_in_pcm_frames) (ak/& decoder) (ak/& length)) 0)
              (ak/== length 0) (> length max-frames)) (ak/return false))
    (ak/while (< measured-frames length)
      (let [^{:var :u64} read 0
            wanted (ak/min (ak/as :u64 2048) (- length measured-frames))]
        (set! _ ((az/field api ma_decoder_read_pcm_frames) (ak/& decoder) (ak/& samples) wanted (ak/& read)))
        (when (ak/!= read wanted) (ak/return false))
        (dotimes [i (* read 2)]
          (let [v (ak/abs (az/index samples i))]
            ;; NaN fails this comparison too.
            (when (ak/! (< v 1.0)) (ak/return false))
            (set! measured-peak (ak/max measured-peak v))))
        (set! measured-frames (+ measured-frames read))))
    (> measured-peak 0.00001)))

(az/defn set-path! :- :bool [[path [:slice-const :u8]]]
  (when (or (ak/== (az/field path len) 0) (>= (az/field path len) 4096)) (ak/return false))
  (dotimes [i (az/field path len)] (when (ak/== (az/index path i) 0) (ak/return false)))
  (ak/memcpy (az/slice path-buffer 0 (az/field path len)) path)
  (set! (az/index path-buffer (az/field path len)) 0)
  true)

(az/defn initialize! :- :bool []
  (when initialized (ak/return true))
  (set! error-code ((az/field api ma_context_init) ak/null 0 ak/null (ak/& context)))
  (when (ak/!= error-code 0) (ak/return false))
  (set! error-code ((az/field api ma_context_get_devices) (ak/& context)
                    (ak/& playback-info) (ak/& playback-count) (ak/& capture-info) (ak/& capture-count)))
  (when (ak/!= error-code 0)
    (set! _ ((az/field api ma_context_uninit) (ak/& context))) (ak/return false))
  (set! initialized true)
  true)

(az/defn device-name :- [:slice-const :u8] [[capture :bool] [index :u32]]
  (when (or (ak/! initialized) (>= index (if capture capture-count playback-count))) (ak/return ""))
  (let [info (if capture (az/index capture-info index) (az/index playback-info index))
        ^{:var :usize} length 0]
    (ak/while (and (< length 256) (ak/!= (az/index (az/field info name) length) 0))
      (set! length (+ length 1)))
    ;; Return the context-owned array, not the local struct copy.
    (let [^{:zig/type [:* DeviceInfo]} entry
          (ak/ptrCast (az/unwrap (ak/as (az/type [:c-pointer DeviceInfo]) (ak/& (az/index (if capture capture-info playback-info) index)))))]
      (az/slice (az/field entry name) 0 length))))

(az/defn process-block!
  "Mode 1 captures dry. Mode 2 sends a dry take. Modes 2/3 retain only return 3/4."
  :- :void [[output [:c-pointer :f32]] [input [:c-pointer :f32]] [frames :u32]]
  (let [start (ak/atomicLoad :u64 (ak/& recorded-frames) :.monotonic)
        ^{:var :f32} peak 0.0
        ^{:var :f32} sent-peak 0.0]
    (when (and (ak/== mode 3) (ak/! tail-started)
               (ak/!= (ak/atomicLoad :u8 (ak/& source-ended) :.acquire) 0))
      (set! tail-started true) (set! limit-frames (ak/min max-frames (+ start live-tail))))
    (dotimes [i frames]
      (let [position (+ start i)]
        (when (and (ak/== mode 2) (ak/!= output ak/null))
          (dotimes [channel 16] (set! (az/index output (+ (* i 16) channel)) 0.0))
          (when (and (< position dry-frames) (< position limit-frames))
            (set! sent-peak (ak/max sent-peak (ak/abs (az/index dry (* position 2)))
                                   (ak/abs (az/index dry (+ (* position 2) 1)))))
            (set! (az/index output (* i 16)) (az/index dry (* position 2)))
            (set! (az/index output (+ (* i 16) 1)) (az/index dry (+ (* position 2) 1)))))
        (when (< position limit-frames)
          (dotimes [channel 2]
            (let [value (if (ak/== input ak/null) 0.0
                          (az/index input (+ (* i (if (>= mode 2) (ak/as :usize 16) 2))
                                             (if (>= mode 2) (ak/as :usize 2) 0) channel)))]
              (when (ak/== mode 1) (set! (az/index dry (+ (* position 2) channel)) value))
              (when (>= mode 2) (set! (az/index wet (+ (* position 2) channel)) value))
              (meter-input! value)
              (set! peak (ak/max peak (ak/abs value))))))))
    ;; Square-root display scale keeps quiet returns visible; not a dB measurement.
    (when (>= mode 2) (update-signal-level! false peak))
    (when (< mode 3) (update-signal-level! true (if (ak/== mode 1) peak sent-peak)))
    (ak/atomicStore :u32 (ak/& peak-milli) (ak/intFromFloat (* (ak/sqrt (ak/min peak 1.0)) 1000.0)) :.release)
    (when (< mode 3)
      (ak/atomicStore :u32 (ak/& input-level)
        (ak/intFromFloat (* (ak/sqrt (ak/min (if (ak/== mode 1) peak sent-peak) 1.0)) 1000.0)) :.release))
    (ak/atomicStore :u64 (ak/& recorded-frames) (ak/min (+ start frames) limit-frames) :.release)
    (when (>= (+ start frames) limit-frames) (ak/atomicStore :u8 (ak/& finished) 1 :.release))))

(az/defn data-callback {:zig/qualifiers "callconv(.c)"} :- :void
  [[device-pointer [:c-pointer Device]] [output [:optional [:* :anyopaque]]]
   [input [:optional [:*const :anyopaque]]] [frames :u32]]
  (set! _ device-pointer)
  (process-block! (ak/ptrCast (ak/alignCast output))
                  (ak/ptrCast (ak/alignCast (ak/constCast input))) frames))

(az/defn stop! :- :void []
  (stop-monitor!)
  (when source-running
    ((az/field api ma_device_uninit) (ak/& source-device))
    (set! source-running false)
    (set! dry-frames (ak/atomicLoad :u64 (ak/& source-frames) :.acquire)))
  (when running
    ((az/field api ma_device_uninit) (ak/& device))
    (set! running false)
    (when (ak/== mode 1) (set! dry-frames (ak/atomicLoad :u64 (ak/& recorded-frames) :.acquire)))))

(az/defn start!
  "Explicit device indices only. Mode 1 microphone; mode 2 sixteen-channel effects round-trip."
  :- :bool [[capture-index :u32] [playback-index :u32] [capture-mode :u32] [tail-frames :u32]]
  (when (or running (ak/! initialized) (>= capture-index capture-count)
            (and (ak/!= capture-mode 1) (ak/!= capture-mode 2))
            (and (ak/== capture-mode 2)
                 (or (>= playback-index playback-count) (ak/== dry-frames 0)
                     (> (+ dry-frames tail-frames) max-frames)))) (ak/return false))
  (when (and (ak/== capture-mode 2)
             (or (ak/! (mem/eql (az/type :u8) (device-name true capture-index) "BlackHole 16ch"))
                 (ak/! (mem/eql (az/type :u8) (device-name false playback-index) "BlackHole 16ch"))))
    (ak/return false))
  (set! mode capture-mode)
  (reset-meters!)
  (set! limit-frames (if (ak/== mode 1) max-frames (+ dry-frames tail-frames)))
  (ak/atomicStore :u64 (ak/& recorded-frames) 0 :.release)
  (ak/atomicStore :u8 (ak/& finished) 0 :.release)
  (let [^:var config ((az/field api ma_device_config_init)
                       (if (ak/== mode 1) (az/field api ma_device_type_capture) (az/field api ma_device_type_duplex)))]
    (set! (az/field config sampleRate) 48000)
    (set! (az/field (az/field config capture) pDeviceID) (ak/& (az/field (az/index capture-info capture-index) id)))
    (set! (az/field (az/field config capture) format) (az/field api ma_format_f32))
    (set! (az/field (az/field config capture) channels) (if (ak/== mode 1) 2 16))
    (when (ak/== mode 2)
      (set! (az/field (az/field config playback) pDeviceID) (ak/& (az/field (az/index playback-info playback-index) id)))
      (set! (az/field (az/field config playback) format) (az/field api ma_format_f32))
      (set! (az/field (az/field config playback) channels) 16))
    (set! (az/field config dataCallback) (ak/& data-callback))
    (set! error-code ((az/field api ma_device_init) (ak/& context) (ak/& config) (ak/& device)))
    (when (ak/!= error-code 0) (ak/return false))
    (set! error-code ((az/field api ma_device_start) (ak/& device)))
    (when (ak/!= error-code 0) ((az/field api ma_device_uninit) (ak/& device)) (ak/return false)))
  (set! running true) true)

(az/defn start-live!
  "Capture source and effects return concurrently. BlackHole source uses 5/6 for safe virtual input."
  :- :bool [[microphone-index :u32] [return-index :u32] [send-index :u32] [tail-frames :u32]]
  (when (or running source-running (ak/! initialized)
            (>= microphone-index capture-count) (>= return-index capture-count)
            (>= send-index playback-count) (> tail-frames 480000)
            (ak/! (mem/eql (az/type :u8) (device-name true return-index) "BlackHole 16ch"))
            (ak/! (mem/eql (az/type :u8) (device-name false send-index) "BlackHole 16ch")))
    (ak/return false))
  (set! mode 3) (set! limit-frames max-frames) (set! live-tail tail-frames)
  (reset-meters!)
  (set! tail-started false) (set! dry-frames 0)
  (set! source-channels (if (mem/eql (az/type :u8) (device-name true microphone-index) "BlackHole 16ch") 16 2))
  (set! source-offset (if (ak/== source-channels 16) 4 0))
  (ak/atomicStore :u64 (ak/& source-frames) 0 :.release)
  (ak/atomicStore :u64 (ak/& recorded-frames) 0 :.release)
  (ak/atomicStore :u8 (ak/& source-stop) 0 :.release)
  (ak/atomicStore :u8 (ak/& source-ended) 0 :.release)
  (ak/atomicStore :u8 (ak/& finished) 0 :.release)
  (let [^:var config ((az/field api ma_device_config_init) (az/field api ma_device_type_capture))]
    (set! (az/field config sampleRate) 48000)
    (set! (az/field (az/field config capture) pDeviceID) (ak/& (az/field (az/index capture-info return-index) id)))
    (set! (az/field (az/field config capture) format) (az/field api ma_format_f32))
    (set! (az/field (az/field config capture) channels) 16)
    (set! (az/field config dataCallback) (ak/& data-callback))
    (set! error-code ((az/field api ma_device_init) (ak/& context) (ak/& config) (ak/& device)))
    (when (ak/!= error-code 0) (ak/return false))
    (set! error-code ((az/field api ma_device_start) (ak/& device)))
    (when (ak/!= error-code 0) ((az/field api ma_device_uninit) (ak/& device)) (ak/return false)))
  (set! running true)
  (let [^:var config ((az/field api ma_device_config_init) (az/field api ma_device_type_duplex))]
    (set! (az/field config sampleRate) 48000)
    (set! (az/field (az/field config capture) pDeviceID) (ak/& (az/field (az/index capture-info microphone-index) id)))
    (set! (az/field (az/field config capture) format) (az/field api ma_format_f32))
    (set! (az/field (az/field config capture) channels) (ak/intCast source-channels))
    (set! (az/field (az/field config playback) pDeviceID) (ak/& (az/field (az/index playback-info send-index) id)))
    (set! (az/field (az/field config playback) format) (az/field api ma_format_f32))
    (set! (az/field (az/field config playback) channels) 16)
    (set! (az/field config dataCallback) (ak/& source-callback))
    (set! error-code ((az/field api ma_device_init) (ak/& context) (ak/& config) (ak/& source-device)))
    (when (ak/!= error-code 0) (stop!) (ak/return false))
    (set! error-code ((az/field api ma_device_start) (ak/& source-device)))
    (when (ak/!= error-code 0)
      ((az/field api ma_device_uninit) (ak/& source-device)) (stop!) (ak/return false)))
  (set! source-running true) true)

(az/defn load-dry! :- :bool [[path [:slice-const :u8]]]
  (when (or running (ak/! (set-path! path))) (ak/return false))
  (set! dry-frames 0)
  (let [^:var config ((az/field api ma_decoder_config_init) (az/field api ma_format_f32) 2 48000)
        ^:var decoder (mem/zeroes (az/type Decoder))
        ^{:var :u64} length 0]
    (when (ak/!= ((az/field api ma_decoder_init_file) (ak/& path-buffer) (ak/& config) (ak/& decoder)) 0)
      (ak/return false))
    (ak/defer (set! _ ((az/field api ma_decoder_uninit) (ak/& decoder))))
    (when (or (ak/!= ((az/field api ma_decoder_get_length_in_pcm_frames) (ak/& decoder) (ak/& length)) 0)
              (ak/== length 0) (> length (- max-frames 48000))) (ak/return false))
    (set! error-code ((az/field api ma_decoder_read_pcm_frames) (ak/& decoder) (ak/& dry) length (ak/& dry-frames)))
    (and (ak/== error-code 0) (ak/== length dry-frames))))

(az/defn write-take! :- :bool [[path [:slice-const :u8]] [processed :bool]]
  (when (or running (ak/! (set-path! path))) (ak/return false))
  (let [frames (if processed (ak/atomicLoad :u64 (ak/& recorded-frames) :.acquire) dry-frames)
        ^:var config ((az/field api ma_encoder_config_init) (az/field api ma_encoding_format_wav)
                        (az/field api ma_format_f32) 2 48000)
        ^:var encoder (mem/zeroes (az/type Encoder)) ^{:var :u64} written 0]
    (when (or (ak/== frames 0) (> frames max-frames) (and processed (< mode 2))) (ak/return false))
    (when (ak/!= ((az/field api ma_encoder_init_file) (ak/& path-buffer) (ak/& config) (ak/& encoder)) 0)
      (ak/return false))
    (set! error-code ((az/field api ma_encoder_write_pcm_frames) (ak/& encoder)
                       (if processed (ak/& wet) (ak/& dry)) frames (ak/& written)))
    (set! _ ((az/field api ma_encoder_uninit) (ak/& encoder)))
    (and (ak/== error-code 0) (ak/== written frames))))

(az/defn frames-recorded :- :u64 [] (ak/atomicLoad :u64 (ak/& recorded-frames) :.acquire))
(az/defn done? :- :bool [] (ak/!= (ak/atomicLoad :u8 (ak/& finished) :.acquire) 0))
(az/defn level :- :u32 [] (ak/atomicLoad :u32 (ak/& peak-milli) :.acquire))

(az/defn available-frames :- :u64 [[processed :bool]]
  (if processed (frames-recorded)
    (if (ak/== mode 3) (ak/atomicLoad :u64 (ak/& source-frames) :.acquire)
      (if (ak/== mode 1) (frames-recorded) dry-frames))))

(az/defn journal!
  "Worker-only durable PCM append. Read only the release-published, immutable prefix."
  :- :u64 [[path [:slice-const :u8]] [processed :bool] [from :u64]]
  (let [end (available-frames processed)]
    (when (or (> end max-frames) (> from end) (ak/! (set-path! path))) (ak/return 0))
    (when (ak/== from end) (ak/return end))
    (let [file ((az/field file-api fopen) (ak/& path-buffer) "r+b")]
      (when (ak/== file ak/null) (ak/return 0))
      (ak/defer (set! _ ((az/field file-api fclose) file)))
      (when (ak/!= ((az/field file-api fseek) file (ak/intCast (* from 8)) 0) 0) (ak/return 0))
      (let [written ((az/field file-api fwrite)
                      (ak/& (az/index (if processed wet dry) (* from 2))) 8 (ak/intCast (- end from)) file)]
        (when (or (ak/!= written (- end from)) (ak/!= ((az/field file-api fflush) file) 0)
                  (ak/!= ((az/field file-api fsync) ((az/field file-api fileno) file)) 0)) (ak/return 0))
        end))))

(az/defn wave-bin :- :f32 [[processed :bool] [bin :u32]]
  (when (>= bin 128) (ak/return 0.0))
  (let [frames (available-frames processed)
        start (/ (* frames bin) 128)
        end (/ (* frames (+ bin 1)) 128)
        step (ak/max 1 (/ (- end start) 64))
        ^{:var :f32} peak 0.0 ^:var i start]
    (ak/while (< i end)
      (set! peak (ak/max peak (ak/abs (az/index (if processed wet dry) (* i 2)))))
      (set! i (+ i step)))
    (ak/min peak 1.0)))

(az/defn shutdown! :- :void []
  (stop!)
  (when initialized (set! _ ((az/field api ma_context_uninit) (ak/& context))) (set! initialized false)))

(az/defn routing-test!
  "No device/microphone: test channel isolation, tail silence and frame bounds."
  :- :bool []
  (when running (ak/return false))
  (let [^{:var [:array 64 :f32]} input (mem/zeroes (az/type [:array 64 :f32]))
        ^{:var [:array 64 :f32]} output ak/undefined]
    (set! mode 2) (set! dry-frames 2) (set! limit-frames 3)
    (set! (az/index dry 0) 0.1) (set! (az/index dry 1) 0.2)
    (set! (az/index dry 2) 0.3) (set! (az/index dry 3) 0.4)
    (set! (az/index input 0) 0.9) ; Send channel must never be mistaken for return.
    (set! (az/index input 2) 0.125) (set! (az/index input 3) 0.25)
    (set! (az/index wet 6) 0.75) ; Sentinel immediately after the capture bound.
    (ak/atomicStore :u64 (ak/& recorded-frames) 0 :.release)
    (ak/atomicStore :u8 (ak/& finished) 0 :.release)
    (process-block! (ak/& output) (ak/& input) 4)
    (when (or (ak/!= (az/index output 0) 0.1) (ak/!= (az/index output 1) 0.2)
              (ak/!= (az/index wet 0) 0.125) (ak/!= (az/index wet 1) 0.25)
              (ak/!= (az/index wet 6) 0.75) (ak/!= (frames-recorded) 3) (ak/! (done?)))
      (ak/return false))
    (dotimes [i 4]
      (dotimes [channel 16]
        (when (and (or (>= i 2) (>= channel 2))
                   (ak/!= (az/index output (+ (* i 16) channel)) 0.0)) (ak/return false))))
    (set! dry-frames 0) true))

(az/defn live-routing-test!
  "Offline live callbacks: distinct buses, dry retention, stop silence, bounded return tail."
  :- :bool []
  (when (or running source-running) (ak/return false))
  (let [^{:var [:array 64 :f32]} input (mem/zeroes (az/type [:array 64 :f32]))
        ^{:var [:array 64 :f32]} output ak/undefined]
    (set! mode 3) (set! source-channels 16) (set! source-offset 4)
    (set! live-tail 2) (set! limit-frames max-frames) (set! tail-started false)
    (ak/atomicStore :u64 (ak/& source-frames) 0 :.release)
    (ak/atomicStore :u64 (ak/& recorded-frames) 0 :.release)
    (ak/atomicStore :u8 (ak/& source-stop) 0 :.release)
    (ak/atomicStore :u8 (ak/& source-ended) 0 :.release)
    (ak/atomicStore :u8 (ak/& finished) 0 :.release)
    (dotimes [i 4]
      (set! (az/index input (* i 16)) 0.9)
      (set! (az/index input (+ (* i 16) 2)) 0.125)
      (set! (az/index input (+ (* i 16) 3)) 0.25)
      (set! (az/index input (+ (* i 16) 4)) 0.5)
      (set! (az/index input (+ (* i 16) 5)) 0.75))
    (process-source! (ak/& output) (ak/& input) 4)
    (process-block! ak/null (ak/& input) 4)
    (dotimes [i 4]
      (when (or (ak/!= (az/index dry (* i 2)) 0.5)
                (ak/!= (az/index dry (+ (* i 2) 1)) 0.75)
                (ak/!= (az/index wet (* i 2)) 0.125)
                (ak/!= (az/index wet (+ (* i 2) 1)) 0.25)) (ak/return false))
      (dotimes [channel 16]
        (when (ak/!= (az/index output (+ (* i 16) channel))
                    (ak/as :f32 (if (ak/== channel 0) 0.5 (if (ak/== channel 1) 0.75 0.0))))
          (ak/return false))))
    (finish-live!)
    (process-source! (ak/& output) (ak/& input) 4)
    (dotimes [i 64] (when (ak/!= (az/index output i) 0.0) (ak/return false)))
    (set! (az/index wet 12) 0.875)
    (process-block! ak/null (ak/& input) 4)
    (when (or (ak/!= (frames-recorded) 6) (ak/! (done?))
              (ak/!= (az/index wet 12) 0.875)
              (ak/!= (ak/atomicLoad :u64 (ak/& source-frames) :.acquire) 4))
      (ak/return false))
    ;; Source at capacity must neither overrun nor keep sending after the limit.
    (ak/atomicStore :u64 (ak/& source-frames) (- max-frames live-tail 1) :.release)
    (ak/atomicStore :u8 (ak/& source-stop) 0 :.release)
    (ak/atomicStore :u8 (ak/& source-ended) 0 :.release)
    (process-source! (ak/& output) (ak/& input) 4)
    (when (or (ak/!= (ak/atomicLoad :u64 (ak/& source-frames) :.acquire) (- max-frames live-tail))
              (ak/== (ak/atomicLoad :u8 (ak/& source-ended) :.acquire) 0)) (ak/return false))
    (dotimes [i 48] (when (ak/!= (az/index output (+ 16 i)) 0.0) (ak/return false)))
    ;; Real microphone configuration uses stereo 1/2, not the virtual source 5/6.
    (set! source-channels 2) (set! source-offset 0)
    (ak/atomicStore :u64 (ak/& source-frames) 0 :.release)
    (process-source! (ak/& output) (ak/& input) 1)
    (when (or (ak/!= (az/index dry 0) 0.9) (ak/!= (az/index dry 1) 0.0)
              (ak/!= (az/index output 0) 0.9) (ak/!= (az/index output 1) 0.0)) (ak/return false))
    (process-source! ak/null ak/null 1)
    (when (or (ak/!= (az/index dry 2) 0.0) (ak/!= (az/index dry 3) 0.0)) (ak/return false))
    ;; Retain a tiny valid paired take for the codec test.
    (set! dry-frames 4) true))
