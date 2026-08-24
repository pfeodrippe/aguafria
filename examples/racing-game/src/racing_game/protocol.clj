(ns racing-game.protocol
  "Single source of truth for the compact observation and action wire formats."
  (:require [aguafria.zig :as az]))

(az/defconst observation-schema-version :u8 2)

(az/defconst action-schema-version :u8 1)

(az/defconst model-fingerprint :u64 0x7adb3d5765ad12b8)

(az/defconst action-head-fingerprint :u64 0xb3cc625e61716d31)

(az/defconst action-head-training-revision :u32 1)
