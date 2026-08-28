(ns racing-game.protocol
  "Single source of truth for semantic prompts, actions, and provenance."
  (:require [aguafria.zig :as az]))

(az/defconst observation-schema-version :u8 4)

(az/defconst action-schema-version :u8 1)

(az/defconst model-fingerprint :u64 0x7adb3d5765ad12b8)

(az/defconst action-head-fingerprint :u64 0xf73458570f802041)

(az/defconst action-head-training-revision :u32 7)

(az/defconst team-head-fingerprint :u64 0xe6cbd7efed326bae)

(az/defconst team-head-training-revision :u32 2)

(az/defconst team-training-data-fingerprint :u64 0xef1a7bb27f02431c)

(az/defconst tokenizer-version :u16 2)

(az/defconst quantization-version :u16 1)

(az/defconst quantization-format :u8 2)

(az/defconst training-data-fingerprint :u64 0x4a3c38c8723716e1)

(az/defconst training-data-sha256 [:array 32 :u8]
  (az/array-init
   [:array 32 :u8]
   [0x4a 0x3c 0x38 0xc8 0x72 0x37 0x16 0xe1
    0x0a 0xcd 0xc9 0x90 0xd2 0x40 0x17 0x0a
    0xdf 0x24 0x69 0x22 0xcc 0xac 0xf9 0x05
    0xcb 0xae 0x56 0xa4 0x65 0xe3 0x7b 0x4f]))

(az/defconst replay-golden-ticks :u32 1200)

(az/defconst replay-golden-intent-count :u16 301)

(az/defconst replay-golden-fingerprint :u64 0xaaf601ce97cfb964)
