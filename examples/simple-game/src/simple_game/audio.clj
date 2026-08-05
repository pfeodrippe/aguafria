(ns simple-game.audio
  "Native miniaudio feedback driven by coco-factory events."
  (:require [aguafria.std]
            [aguafria.keyword :as ak]
            [aguafria.std.mem :as std-mem]
            [aguafria.zig :as az]
            [simple-game.bindings]
            [simple-game.bindings.miniaudio :as miniaudio]))

(az/defstruct AudioSnapshot
  "Inspectable state for the live native audio engine."
  {:layout :extern}
  [[:initialized :bool]
   [:sample_rate :u32]
   [:play_count :u32]
   [:last_event :u8]
   [:last_frequency :f64]])

(az/defvar initialized false)

(az/defvar engine miniaudio/ma_engine
  (std-mem/zeroes (az/type miniaudio/ma_engine)))

(az/defvar waveform miniaudio/ma_waveform
  (std-mem/zeroes (az/type miniaudio/ma_waveform)))

(az/defvar sound miniaudio/ma_sound
  (std-mem/zeroes (az/type miniaudio/ma_sound)))

(az/defvar sample-rate :u32 0)

(az/defvar play-count :u32 0)

(az/defvar last-event :u8 0)

(az/defvar last-frequency :f64 0.0)

(az/defn event-frequency
  "Map one factory-domain event to a recognizable native tone."
  :-
  :f64
  [[event-kind :u8]]
  (cond
    (ak/== event-kind 1) 540.0
    (ak/== event-kind 2) 230.0
    (ak/== event-kind 3) 620.0
    (ak/== event-kind 4) 880.0
    :else 360.0))

(az/defn initialize!
  "Open miniaudio once; failure leaves gameplay usable without sound."
  :-
  :bool
  []
  (when (ak/! initialized)
    (let [engine-config (miniaudio/ma_engine_config_init)]
      (when (ak/== (miniaudio/ma_engine_init (ak/& engine-config) (ak/& engine))
                   miniaudio/MA_SUCCESS)
        (set! sample-rate (miniaudio/ma_engine_get_sample_rate (ak/& engine)))
        (let [waveform-config
              (miniaudio/ma_waveform_config_init
               miniaudio/ma_format_f32
               1
               sample-rate
               miniaudio/ma_waveform_type_sine
               0.18
               480.0)]
          (when (ak/== (miniaudio/ma_waveform_init
                        (ak/& waveform-config) (ak/& waveform))
                       miniaudio/MA_SUCCESS)
            (let [data-source
                  (-> (ak/& waveform)
                      (az/cast [:* miniaudio/ma_data_source]))]
              (when (ak/==
                     (miniaudio/ma_sound_init_from_data_source
                      (ak/& engine)
                      data-source
                      miniaudio/MA_SOUND_FLAG_NO_SPATIALIZATION
                      null
                      (ak/& sound))
                     miniaudio/MA_SUCCESS)
                (set! initialized true))))))))
  initialized)

(az/defn play-event!
  "Play a short feedback tone for a placement, removal, delivery, or house."
  :-
  :void
  [[event-kind :u8]]
  (when initialized
    (let [frequency (event-frequency event-kind)
          now (miniaudio/ma_engine_get_time_in_pcm_frames (ak/& engine))
          ^{:zig/type :u32} divisor (if (ak/== event-kind 4) 7 18)
          duration (ak/divTrunc sample-rate divisor)]
      (set! _ (miniaudio/ma_sound_stop (ak/& sound)))
      (set! _ (miniaudio/ma_sound_seek_to_pcm_frame (ak/& sound) 0))
      (set! _ (miniaudio/ma_waveform_set_frequency (ak/& waveform) frequency))
      (set! _ (miniaudio/ma_waveform_set_amplitude
               (ak/& waveform)
               (if (ak/== event-kind 4) 0.28 0.16)))
      (miniaudio/ma_sound_set_stop_time_in_pcm_frames
       (ak/& sound) (+ now duration))
      (set! _ (miniaudio/ma_sound_start (ak/& sound)))
      (set! last-event event-kind)
      (set! last-frequency frequency)
      (set! play-count (+ play-count 1)))))

(az/defn snapshot
  "Inspect native audio state from Clojure without touching the device."
  :-
  AudioSnapshot
  []
  (AudioSnapshot {:initialized initialized
                  :sample_rate sample-rate
                  :play_count play-count
                  :last_event last-event
                  :last_frequency last-frequency}))

(az/defn shutdown!
  "Release miniaudio resources in ownership order."
  :-
  :void
  []
  (when initialized
    (miniaudio/ma_sound_uninit (ak/& sound))
    (miniaudio/ma_waveform_uninit (ak/& waveform))
    (miniaudio/ma_engine_uninit (ak/& engine))
    (set! engine (std-mem/zeroes (az/type miniaudio/ma_engine)))
    (set! waveform (std-mem/zeroes (az/type miniaudio/ma_waveform)))
    (set! sound (std-mem/zeroes (az/type miniaudio/ma_sound)))
    (set! sample-rate 0)
    (set! initialized false)))
