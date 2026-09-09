(ns la-professeure.live-signal
  "Explicit hardware QA only: a quiet stereo signal on BlackHole 5/6. Never auto-starts."
  (:require [aguafria.zig :as az] [aguafria.keyword :as ak]
            [aguafria.std.mem :as mem]
            [la-professeure.tools.recorder :as recorder]))

(az/defvar device recorder/Device ak/undefined)
(az/defvar running false)
(az/defvar frame :u64 0)

(az/defn callback {:zig/qualifiers "callconv(.c)"} :- :void
  [[pointer [:c-pointer recorder/Device]] [output [:optional [:* :anyopaque]]]
   [input [:optional [:*const :anyopaque]]] [frames :u32]]
  (set! _ pointer) (set! _ input)
  (when (ak/== output ak/null) (ak/return))
  (let [out (az/cast output [:c-pointer :f32])]
    (dotimes [i frames]
      (dotimes [channel 16] (set! (az/index out (+ (* i 16) channel)) 0.0))
      (let [t (/ (ak/as :f32 (ak/floatFromInt (mod frame 48000))) 48000.0)
            v (* 0.015 (+ (ak/sin (* t 2764.6015)) (* 0.3 (ak/sin (* t 4580.442)))))]
        (set! (az/index out (+ (* i 16) 4)) v)
        (set! (az/index out (+ (* i 16) 5)) (* v 0.7)))
      (set! frame (+ frame 1)))))

(az/defn stop! :- :void []
  (when running ((az/field recorder/api ma_device_uninit) (ak/& device)) (set! running false)))

(az/defn start! :- :bool [[index :u32]]
  (when (or running (ak/! recorder/initialized) (>= index recorder/playback-count)
            (ak/! (mem/eql (az/type :u8) (recorder/device-name false index) "BlackHole 16ch")))
    (ak/return false))
  (let [^:var config ((az/field recorder/api ma_device_config_init) (az/field recorder/api ma_device_type_playback))]
    (set! (az/field config sampleRate) 48000)
    (set! (az/field (az/field config playback) pDeviceID) (ak/& (az/field (az/index recorder/playback-info index) id)))
    (set! (az/field (az/field config playback) format) (az/field recorder/api ma_format_f32))
    (set! (az/field (az/field config playback) channels) 16)
    (set! (az/field config dataCallback) (ak/& callback))
    (when (ak/!= ((az/field recorder/api ma_device_init) (ak/& recorder/context) (ak/& config) (ak/& device)) 0)
      (ak/return false))
    (when (ak/!= ((az/field recorder/api ma_device_start) (ak/& device)) 0)
      ((az/field recorder/api ma_device_uninit) (ak/& device)) (ak/return false))
    (set! running true) true))
