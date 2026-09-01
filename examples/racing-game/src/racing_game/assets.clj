(ns racing-game.assets
  "One native release gate for model/head compatibility and exact checksums."
  (:require [aguafria.keyword :as ak]
            [aguafria.std]
            [aguafria.std.debug :as std-debug]
            [aguafria.zig :as az]
            [racing-game.inference :as inference]))

(az/defconst model-path
  "resources/models/granite-4.0-h-350m-Q4_0.gguf")

(az/defconst action-head-path
  "resources/models/granite-r4-driver-head.f32")

(az/defconst team-head-path
  "resources/models/granite-r4-team-head.f32")

(az/defconst expected-model-bytes :usize 216073120)

(az/defconst expected-model-sha256 [:array 32 :u8]
  (az/array-init
   [:array 32 :u8]
   [122 219 61 87 101 173 18 184 59 28 8 169 7 70 216 146
    90 247 24 84 91 74 54 40 126 141 172 143 131 183 42 182]))

(az/defconst expected-action-head-sha256 [:array 32 :u8]
  (az/array-init
   [:array 32 :u8]
   [247 52 88 87 15 128 32 65 78 65 204 204 118 111 13 239
    116 140 149 229 123 155 153 253 124 58 195 103 37 208 17 237]))

(az/defconst expected-team-head-sha256 [:array 32 :u8]
  (az/array-init
   [:array 32 :u8]
   [230 203 215 239 237 50 107 174 173 42 60 59 41 56 20 132
    55 50 151 232 203 167 134 246 21 192 134 102 9 36 236 122]))

(az/defn print-model-error
  :-
  :void
  [[error-code :u32]]
  (cond
    (ak/== error-code inference/model-file-not-found)
    (std-debug/print
     "Racing model is missing: resources/models/granite-4.0-h-350m-Q4_0.gguf\nRun from build/standalone, or fetch/package the pinned model first.\n"
     {})

    (ak/== error-code inference/model-file-empty)
    (std-debug/print "Racing model is empty; package it again.\n" {})

    (ak/== error-code inference/model-allocation-failed)
    (std-debug/print "Racing model could not be allocated in native memory.\n" {})

    (ak/== error-code inference/model-file-read-failed)
    (std-debug/print "Racing model could not be read completely.\n" {})

    :else
    (std-debug/print
     "Racing model is not a compatible Granite GGUF; package the pinned asset again.\n"
     {})))

(az/defn print-action-head-error
  :-
  :void
  [[error-code :u32]]
  (cond
    (ak/== error-code 1)
    (std-debug/print
     "Racing action head is missing: resources/models/granite-r4-driver-head.f32\nPackage the release assets again.\n"
     {})

    (ak/== error-code 2)
    (std-debug/print "Racing action head is empty; package it again.\n" {})

    (ak/== error-code 3)
    (std-debug/print
     "Racing action head is incompatible with observation schema 4/action schema 1.\n"
     {})

    :else
    (std-debug/print "Racing action head could not be read completely.\n" {})))

(az/defn print-team-head-error
  :-
  :void
  [[error-code :u32]]
  (cond
    (ak/== error-code 1)
    (std-debug/print
     "Racing team head is missing: resources/models/granite-r4-team-head.f32\nPackage the release assets again.\n"
     {})

    (ak/== error-code 2)
    (std-debug/print "Racing team head is empty; package it again.\n" {})

    (ak/== error-code 3)
    (std-debug/print
     "Racing team head is incompatible with observation schema 4/action schema 1.\n"
     {})

    :else
    (std-debug/print "Racing team head could not be read completely.\n" {})))

(az/defn load-and-verify!
  "Load the exact release assets, validate layouts, byte count, and SHA-256."
  :-
  :bool
  []
  (let [model (inference/load-model! model-path)]
    (cond
      (ak/! (az/field model valid))
      (do
        (print-model-error (az/field model error_code))
        (inference/unload-model!)
        false)

      (ak/!= (az/field model file_size) expected-model-bytes)
      (do
        (std-debug/print
         "Racing model has the wrong byte count; expected 216073120 bytes.\n"
         {})
        (inference/unload-model!)
        false)

      (ak/!
       (inference/sha256-matches?
        (inference/loaded-model-sha256)
        (ak/& expected-model-sha256)))
      (do
        (std-debug/print
         "Racing model SHA-256 does not match models.edn; package the pinned asset again.\n"
         {})
        (inference/unload-model!)
        false)

      :else
      (let [head (inference/load-action-head! action-head-path)]
        (cond
          (ak/! (az/field head valid))
          (do
            (print-action-head-error (az/field head error_code))
            (inference/unload-model!)
            false)

          (ak/!
           (inference/file-sha256-matches?
            action-head-path (ak/& expected-action-head-sha256)))
          (do
            (std-debug/print
             "Racing action-head SHA-256 does not match models.edn; package it again.\n"
             {})
            (inference/unload-model!)
            false)

          :else
          (let [team-head (inference/load-team-head! team-head-path)]
            (cond
              (ak/! (az/field team-head valid))
              (do
                (print-team-head-error (az/field team-head error_code))
                (inference/unload-model!)
                false)

              (ak/!
               (inference/file-sha256-matches?
                team-head-path (ak/& expected-team-head-sha256)))
              (do
                (std-debug/print
                 "Racing team-head SHA-256 does not match models.edn; package it again.\n"
                 {})
                (inference/unload-model!)
                false)

              :else true)))))))
