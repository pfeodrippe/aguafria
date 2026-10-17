(ns la-professeure.live-signal
  "Explicit hardware QA only: a quiet stereo signal on BlackHole 5/6. Never auto-starts."
  (:refer-clojure :only [])
  (:require [aguafria.zig :as a]
            [aguafria.keyword :as ak :refer :all]
            [aguafria.std.mem :as mem]
            [la-professeure.tools.recorder :as recorder]))

(a/defvar device recorder/Device undefined)

(a/defvar running false)

(a/defvar frame :u64 0)

(a/defn callback :void {:zig/qualifiers "callconv(.c)"}
  [[pointer [:c-pointer recorder/Device]] [output [:optional [:* :anyopaque]]]
   [input [:optional [:*const :anyopaque]]] [frames :u32]]
  (set! _ pointer) (set! _ input)
  (when (== output null) (return))
  (let [out (a/cast output [:c-pointer :f32])]
    (dotimes [i frames]
      (dotimes [channel 16] (set! (a/index out (+ (* i 16) channel)) 0.0))
      (let [t (/ (as (floatFromInt (mod frame 48000)) :f32) 48000.0)
            v (* 0.015 (+ (sin (* t 2764.6015)) (* 0.3 (sin (* t 4580.442)))))]
        (set! (a/index out (+ (* i 16) 4)) v)
        (set! (a/index out (+ (* i 16) 5)) (* v 0.7)))
      (set! frame (+ frame 1)))))

(a/defn stop! :void []
  (when running ((a/field recorder/api ma_device_uninit) (ak/& device)) (set! running false)))

(a/defn start! :bool [[index :u32]]
  (when (or running (! recorder/initialized) (>= index recorder/playback-count)
            (! (mem/eql (a/type :u8) (recorder/device-name false index) "BlackHole 16ch")))
    (return false))
  (let [^:var config ((a/field recorder/api ma_device_config_init) (a/field recorder/api ma_device_type_playback))]
    (set! (a/field config sampleRate) 48000)
    (set! (a/field (a/field config playback) pDeviceID) (ak/& (a/field (a/index recorder/playback-info index) id)))
    (set! (a/field (a/field config playback) format) (a/field recorder/api ma_format_f32))
    (set! (a/field (a/field config playback) channels) 16)
    (set! (a/field config dataCallback) (ak/& callback))
    (when (!= ((a/field recorder/api ma_device_init) (ak/& recorder/context) (ak/& config) (ak/& device)) 0)
      (return false))
    (when (!= ((a/field recorder/api ma_device_start) (ak/& device)) 0)
      ((a/field recorder/api ma_device_uninit) (ak/& device)) (return false))
    (set! running true) true))
