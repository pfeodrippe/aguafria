(ns racing-game.protocol
  "Single source of truth for the compact observation and action wire formats."
  (:require [aguafria.zig :as az]))

(az/defconst observation-schema-version :u8 3)

(az/defconst action-schema-version :u8 1)

(az/defconst observation-model-step-count :u8 2)

(az/defconst model-fingerprint :u64 0x7adb3d5765ad12b8)

(az/defconst action-head-fingerprint :u64 0x65b353acfedbeac3)

(az/defconst action-head-training-revision :u32 6)

(az/defconst tokenizer-version :u16 1)

(az/defconst quantization-version :u16 1)

(az/defconst quantization-format :u8 2)

(az/defconst training-data-fingerprint :u64 0x11bd22bd5430bb8f)

(az/defconst training-data-sha256 [:array 32 :u8]
  (az/array-init
   [:array 32 :u8]
   [0x11 0xbd 0x22 0xbd 0x54 0x30 0xbb 0x8f
    0x33 0x80 0xe8 0x1b 0x02 0xbf 0xd9 0x38
    0xc6 0x7e 0xec 0xbf 0x5e 0xa5 0x70 0xa1
    0x7b 0x41 0xd8 0x1b 0xaa 0xbf 0x16 0x08]))

(az/defconst replay-golden-ticks :u32 1200)

(az/defconst replay-golden-intent-count :u16 301)

(az/defconst replay-golden-fingerprint :u64 0xaaf601ce97cfb964)
