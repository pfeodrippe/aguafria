(ns la-professeure.scene
  "Native stage, animation, text, 2D light and stable miniaudio resource ownership."
  (:require [aguafria.std] [aguafria.keyword :as k] [aguafria.zig :as az]
            [aguafria.std.mem :as mem] [aguafria.std.math :as math]
            [aguafria.std.debug :as debug]
            [aguafria-examples-native.bindings]
            [aguafria-examples-native.bindings.glfw :as glfw]
            [aguafria-examples-native.bindings.flecs :as flecs]
            [aguafria-examples-native.bindings.runtime :as io]
            [aguafria-examples-native.mesh :as mesh]
            [la-professeure.gpu :as gpu]
            [la-professeure.recording-tool :as story]
            [la-professeure.miniaudio :as audio]
            [la-professeure.build :as build]))

(az/defconst animation-fps :f32 8.0)

(az/defstruct Snapshot {:layout :extern}
              [[:time :f32] [:frame :u32] [:playing :bool] [:audio_ready :bool]
               [:audio_revision :u32] [:reload_failures :u32] [:rendered_frames :u64]])

(az/defvar window [:optional [:* glfw/GLFWwindow]] k/null)

(az/defvar elapsed :f32 0.0)

(az/defvar playing true)

(az/defvar light-enabled true)

(az/defvar engine-ready false)

(az/defvar audio-ready false)

(az/defvar audio-muted false)

(az/defvar studio-audio-suppressed :bool false)

(az/defvar audio-revision :u32 0)

(az/defvar reload-failures :u32 0)

(az/defvar rendered-frames :u64 0)

(az/defvar previous-time :f64 0.0)

(az/defvar file-check-time :f64 0.0)

(az/defvar track-hash :u64 0)

(az/defvar engine audio/ma_engine (mem/zeroes audio/ma_engine))

(az/defvar tracks [:array 2 audio/ma_sound] (mem/zeroes [:array 2 audio/ma_sound]))

(az/defvar decoders [:array 2 audio/ma_decoder] (mem/zeroes [:array 2 audio/ma_decoder]))

(az/defvar active-track :usize 0)

(az/defvar key-state [:array 4 :bool] (mem/zeroes [:array 4 :bool]))

(az/defvar ui-key-state [:array 4 :bool] (mem/zeroes [:array 4 :bool]))

(az/defvar glyph-advances [:array 256 :f32] (mem/zeroes [:array 256 :f32]))

(az/defvar ui-glyph-advances [:array 256 :f32] (mem/zeroes [:array 256 :f32]))

(az/defvar response :u32 0)

(az/defvar hovered-choice :u32 0)

(az/defvar mouse-was-down false)

(az/defvar vertices [:c-pointer mesh/GpuVertex] k/null)

(az/defvar vertex-count :u32 0)

(az/defconst DevelopmentView (az/type [:*const [:fn {:callconv :.c} [] :void]]))

(az/defvar development-tick [:optional DevelopmentView] k/null)

(az/defvar development-shutdown [:optional DevelopmentView] k/null)

(az/defvar development-assets [:optional DevelopmentView] k/null)

(az/defvar development-focus [:optional DevelopmentView] k/null)

(az/defvar reveal-parent :u32 4294967295)

(az/defvar reveal-page :u32 4294967295)

(az/defvar reveal-story :usize 2)

(az/defvar reveal-start :f64 0.0)

(az/defvar reveal-all :bool false)

(az/defvar reveal-remaining :usize 0)

;; One native ECS world for the game and its attached development tools.
;; Parsed text remains a contiguous asset; entities hold runtime passage state.
(az/defstruct PassageState {:layout :extern}
              [[:node :u32] [:revision :u32] [:takes :u32] [:visited :bool]])

(az/defvar world [:optional [:* flecs/ecs_world_t]] k/null)

(az/defvar passage-component :u64 0)

(az/defvar passage-entities [:array 1024 :u64] (mem/zeroes [:array 1024 :u64]))

(az/defvar passage-entity-count :u32 0)

(az/defn ensure-world! :void []
  (when (k/== world k/null)
    (k/= world (flecs/ecs_init))
    (let [entity-desc (flecs/ecs_entity_desc_t {:name "DialoguePassage"})
          component-entity (flecs/ecs_entity_init world (k/& entity-desc))
          type-info (flecs/ecs_type_info_t {:size (k/sizeOf PassageState) :alignment (k/alignOf PassageState)})
          desc (flecs/ecs_component_desc_t {:entity component-entity :type type-info})]
      (k/= passage-component (flecs/ecs_component_init world (k/& desc))))))

(az/defn shutdown-world! :void []
  (when (k/!= world k/null) (k/= :_ (flecs/ecs_fini world)) (k/= world k/null))
  (k/= passage-entity-count 0)
  (k/= passage-entities (mem/zeroes [:array 1024 :u64])))

(az/defstruct Story {:layout :extern}
              [[:count :u32] [:text_length :u32] [:nodes [:array 1024 story/Node]]
               [:text [:array 262144 :u8]]])

(az/defvar stories [:array 2 Story] (mem/zeroes [:array 2 Story]))

(az/defvar active-story :usize 0)

(az/defvar story-parent :u32 0)

(az/defvar story-ready false)

(az/defvar back-was-down false)

(az/defvar story-page :u32 0)

(az/defvar story-more-pages false)

(az/defvar page-down-was-down false)

(az/defvar page-up-was-down false)

(az/defvar choice-offset :u32 0)

(az/defvar choice-down-was-down false)

(az/defvar choice-up-was-down false)

(az/defvar scene-next-was-down false)

;; Dialogue owns separate stable sound/decoder slots; replacing a voice never
;; replaces the ambience or moves a decoder still referenced by the audio thread.
(az/defvar voices [:array 2 audio/ma_sound] (mem/zeroes [:array 2 audio/ma_sound]))

(az/defvar voice-decoders [:array 2 audio/ma_decoder] (mem/zeroes [:array 2 audio/ma_decoder]))

(az/defvar voice-slot :usize 0)

(az/defvar voice-ready false)

(az/defvar voice-revision :u32 0)

(az/defvar voice-parent :u32 story/no-parent)

(az/defvar voice-node :u32 story/no-parent)

(az/defvar voice-path [:array 4096 :u8] (mem/zeroes [:array 4096 :u8]))

(az/defvar voice-hash :u64 0)

(az/defvar voice-check-time :f64 0.0)

(eval `(az/defconst ~'live-voices? ~(boolean (:reloadable? (az/configuration)))))

(az/defn stop-voice! :void []
  (when voice-ready
    (audio/ma_sound_uninit (k/& (az/get voices voice-slot)))
    (k/= :_ (audio/ma_decoder_uninit (k/& (az/get voice-decoders voice-slot))))
    (k/= voice-ready false)))

(az/defn play-voice-file! :bool
  "Render-thread only. Validate a candidate before releasing the current voice." [[path [:slice-const :u8]]]
  (when (or (k/! engine-ready) (k/== (:len path) 0)
            (k/>= (:len path) 4096)) (k/return false))
  (let [filename (k/var (k/as (mem/zeroes [:array 4096 :u8]) [:array 4096 :u8]))
        next (k/mod (k/+ voice-slot 1) 2)
        decoder (k/& (az/get voice-decoders next))
        candidate (k/& (az/get voices next))]
    (dotimes [i (:len path)] (when (k/== (az/get path i) 0) (k/return false)))
    (k/memcpy (az/slice filename 0 (:len path)) path)
    (when (k/!= (audio/ma_decoder_init_file (k/& filename) k/null decoder) audio/MA_SUCCESS)
      (k/return false))
    (when (k/!= (audio/ma_sound_init_from_data_source (k/& engine)
                                                      (k/as (k/ptrCast decoder) [:* audio/ma_data_source])
                                                      0 k/null candidate) audio/MA_SUCCESS)
      (k/= :_ (audio/ma_decoder_uninit decoder)) (k/return false))
    (audio/ma_sound_set_looping candidate 0)
    (audio/ma_sound_set_volume candidate (if (or audio-muted studio-audio-suppressed) 0.0 0.8))
    (when (k/!= (audio/ma_sound_start candidate) audio/MA_SUCCESS)
      (audio/ma_sound_uninit candidate) (k/= :_ (audio/ma_decoder_uninit decoder)) (k/return false))
    (stop-voice!) (k/= voice-slot next) (k/= voice-ready true)
    (k/= voice-revision (k/+ voice-revision 1)) true))

(az/defn current-voice-hash :u64 []
  (let [file (io/fopen (k/& voice-path) "rb")]
    (when (k/== file k/null) (k/return 0))
    (k/defer (k/= :_ (io/fclose file)))
    (let [buffer (k/var (k/as k/undefined [:array 4096 :u8]))
          hash (k/var (k/u64 14695981039346656037))]
      (k/while true
        (let [n (io/fread (k/& buffer) 1 4096 file)]
          (when (k/== n 0) (k/break))
          (dotimes [i n] (k/= hash (k/*% (k/bit-xor hash (az/get buffer i)) 1099511628211)))))
      hash)))

(az/defn load-node-voice! :bool [[index :u32]]
  (when (k/>= index (:count (az/get stories active-story))) (k/return false))
  (let [node (az/get (:nodes (az/get stories active-story)) index)
        length (:id_len node)]
    (when (or (k/== length 0) (k/> length 64)) (k/return false))
    (dotimes [i length]
      (let [c (az/get (:id node) i)]
        (when (k/! (or (and (k/>= c 65) (k/<= c 90)) (and (k/>= c 97) (k/<= c 122))
                       (and (k/>= c 48) (k/<= c 57)) (k/== c 45) (k/== c 95))) (k/return false))))
    (k/= voice-path (mem/zeroes [:array 4096 :u8]))
    (k/memcpy (az/slice voice-path 0 17) "resources/voices/")
    (k/memcpy (az/slice voice-path 17 (k/+ 17 length)) (az/slice (:id node) 0 length))
    (k/memcpy (az/slice voice-path (k/+ 17 length) (k/+ 21 length)) ".wav")
    (k/= voice-node index) (k/= voice-hash (current-voice-hash))
    (play-voice-file! (az/slice voice-path 0 (k/+ 21 length)))))

(az/defn update-voice! :void []
  (let [data (k/& (az/get stories active-story))
        changed (k/!= voice-parent story-parent)]
    (when changed
      (stop-voice!) (k/= voice-parent story-parent) (k/= voice-node story/no-parent))
    ;; Advance through voiced passages in their Markdown order. Missing takes
    ;; are silent, never substituted with unrelated audio.
    (when (or changed (and voice-ready (k/!= (audio/ma_sound_at_end (k/& (az/get voices voice-slot))) 0)))
      (let [start (if (k/== voice-node story/no-parent) (k/as 0 :u32) (k/+ voice-node 1))]
        (dotimes [i (:count data)]
          (let [n (az/get (:nodes data) i)]
            (when (and (k/>= i start) (k/== (:parent n) story-parent)
                       (k/== (:kind n) 2) (k/> (:id_len n) 0))
              (when (load-node-voice! (k/intCast i)) (k/return)))))))
    (when (and live-voices? (k/!= voice-node story/no-parent)
               (k/>= (k/- (glfw/glfwGetTime) voice-check-time) 0.5))
      (k/= voice-check-time (glfw/glfwGetTime))
      (when (k/!= voice-hash (current-voice-hash)) (k/= :_ (load-node-voice! voice-node))))))

(az/defn passage-count :i32 []
  (if (k/== world k/null) 0 (flecs/ecs_count_id world passage-component)))

(az/defn sync-passages! :void []
  (ensure-world!)
  (let [data (k/& (az/get stories active-story))
        previous-entities passage-entities
        previous-count passage-entity-count]
    (dotimes [i (:count data)]
      (let [node (az/get (:nodes data) i)
            name (k/var (k/as (mem/zeroes [:array 65 :u8]) [:array 65 :u8]))]
        (k/memcpy (az/slice name 0 (:id_len node)) (az/slice (:id node) 0 (:id_len node)))
        (let [desc (flecs/ecs_entity_desc_t {:name (if (k/> (:id_len node) 0) (k/& name) k/null)})
              entity (flecs/ecs_entity_init world (k/& desc))
              old (flecs/ecs_get_id world entity passage-component)
              state (PassageState {:node (k/intCast i) :revision (:revision node)
                                   :takes (if (k/!= old k/null) (:takes (az/cast old [:*const PassageState])) 0)
                                   :visited (if (k/!= old k/null) (:visited (az/cast old [:*const PassageState])) false)})]
          (k/= (az/get passage-entities i) entity)
          (k/= :_ (flecs/ecs_set_id world entity passage-component (k/sizeOf PassageState) (k/& state))))))
    (k/= passage-entity-count (:count data))
    (dotimes [i previous-count]
      (let [entity (az/get previous-entities i) retained (k/var false)]
        (dotimes [j passage-entity-count]
          (when (k/== entity (az/get passage-entities j)) (k/= retained true)))
        (when (k/! retained) (flecs/ecs_delete world entity))))))

(az/defn reload-story! :bool
  "Validate a complete compiled Markdown asset before changing the visible dialogue." []
  (let [file (io/fopen "resources/demo/story.lpdialogue" "rb")]
    (when (k/== file k/null) (k/return false))
    (k/defer (k/= :_ (io/fclose file)))
    (let [magic (k/var (k/as k/undefined [:array 8 :u8]))
          slot (k/mod (k/+ active-story 1) 2)
          candidate (k/& (az/get stories slot))]
      (when (or (k/!= (io/fread (k/& magic) 1 8 file) 8)
                (k/! (mem/eql :u8 (k/& magic) "LPDIAG01"))) (k/return false))
      (when (or (k/!= (io/fread (k/& (:count candidate)) 4 1 file) 1)
                (k/!= (io/fread (k/& (:text_length candidate)) 4 1 file) 1)
                (k/== (:count candidate) 0) (k/> (:count candidate) 1024)
                (k/> (:text_length candidate) 262144)) (k/return false))
      (when (or (k/!= (io/fread (k/& (:nodes candidate)) (k/sizeOf story/Node)
                                (:count candidate) file) (:count candidate))
                (k/!= (io/fread (k/& (:text candidate)) 1 (:text_length candidate) file)
                      (:text_length candidate))) (k/return false))
      (dotimes [i (:count candidate)]
        (let [n (az/get (:nodes candidate) i)]
          (when (or (k/> (:kind n) 2) (k/> (:scene n) i)
                    (and (k/!= (:parent n) story/no-parent) (k/>= (:parent n) i))
                    (k/> (:offset n) (:text_length candidate))
                    (k/> (:length n) (k/- (:text_length candidate) (:offset n))))
            (k/return false))))
      (when (k/!= (:kind (az/get (:nodes candidate) 0)) 0) (k/return false))
      (k/= active-story slot) (k/= story-parent 0) (k/= story-page 0) (k/= choice-offset 0) (k/= response 0)
      (k/= voice-parent story/no-parent)
      (k/= story-ready true) (sync-passages!) true)))

(az/defn story-text [:slice-const :u8] [[index :u32]]
  (let [data (k/& (az/get stories active-story))]
    (when (k/>= index (:count data)) (k/return ""))
    (let [node (az/get (:nodes data) index)]
      (az/slice (:text data) (:offset node)
                (k/+ (:offset node) (:length node))))))

(az/defn story-choice-parent :u32 []
  ;; Keep the leaf's response visible while returning to its enclosing choices.
  ;; Climb only authored parents; never cross into another scene.
  (let [data (k/& (az/get stories active-story)) parent (k/var (k/u32 story-parent))]
    (dotimes [_ (:count data)]
      (when (or (k/== parent story/no-parent) (k/>= parent (:count data)))
        (k/return story/no-parent))
      (dotimes [i (:count data)]
        (let [n (az/get (:nodes data) i)]
          (when (and (k/== (:parent n) parent) (k/== (:kind n) 1))
            (k/return parent))))
      (k/= parent (:parent (az/get (:nodes data) parent))))
    story/no-parent))

(az/defn story-choice-visited? :bool [[index :u32]]
  (when (or (k/== world k/null) (k/>= index passage-entity-count)) (k/return false))
  (let [state (flecs/ecs_get_id world (az/get passage-entities index) passage-component)]
    (and (k/!= state k/null) (:visited (az/cast state [:*const PassageState])))))

(az/defn story-choice :u32 [[ordinal :u32]]
  (let [data (k/& (az/get stories active-story)) parent (story-choice-parent) found (k/var (k/u32 0))]
    (when (k/== parent story/no-parent) (k/return story/no-parent))
    (dotimes [i (:count data)]
      (let [n (az/get (:nodes data) i)]
        (when (and (k/== (:parent n) parent) (k/== (:kind n) 1))
          (k/= found (k/+ found 1))
          (when (k/== found ordinal) (k/return (k/intCast i))))))
    story/no-parent))

(az/defn choose-story! :bool [[ordinal :u32]]
  (let [next (story-choice ordinal)]
    (when (k/== next story/no-parent) (k/return false))
    (let [state (az/cast (flecs/ecs_get_mut_id world (az/get passage-entities next) passage-component) [:* PassageState])]
      (k/= (:visited state) true))
    (k/= story-parent next) (k/= story-page 0) (k/= choice-offset 0)
    (k/= reveal-parent story/no-parent) (k/= voice-parent story/no-parent) true))

(az/defn page-choices! :bool [[forward :bool]]
  (if forward
    (do (when (k/== (story-choice (k/+ choice-offset 4)) story/no-parent) (k/return false))
        (k/= choice-offset (k/+ choice-offset 3)))
    (do (when (k/== choice-offset 0) (k/return false))
        (k/= choice-offset (k/- choice-offset 3))))
  true)

(az/defn next-scene! :void []
  (let [data (k/& (az/get stories active-story))
        current (:scene (az/get (:nodes data) story-parent))]
    (dotimes [step (:count data)]
      (let [index (k/mod (k/+ current (k/as (k/intCast step) :u32) 1) (:count data))]
        (when (k/== (:kind (az/get (:nodes data) index)) 0)
          (k/= story-parent index) (k/= story-page 0) (k/= choice-offset 0) (k/return))))))

(az/defn animation-frame :u32 [[seconds :f32] [fps :f32]]
  (k/intFromFloat (k/mod (k/floor (k/* (k/max seconds 0.0) (k/max fps 0.0))) 8.0)))

(az/defn bob-height :f32
  "Edit this declaration while the native window runs." []
  (k/* 8.0 (math/sin (k/* elapsed 2.0))))

(az/defn snapshot Snapshot []
  (Snapshot {:time elapsed :frame (animation-frame elapsed animation-fps)
             :playing playing :audio_ready audio-ready :audio_revision audio-revision
             :reload_failures reload-failures :rendered_frames rendered-frames}))

(az/defn file-hash :u64 []
  (let [file (io/fopen "resources/demo/lesson.wav" "rb")]
    (when (k/== file k/null) (k/return 0))
    (k/defer (k/= :_ (io/fclose file)))
    (let [buffer (k/var (k/as k/undefined [:array 4096 :u8]))
          hash (k/var (k/u64 14695981039346656037))]
      (k/while true
        (let [n (io/fread (k/& buffer) 1 4096 file)]
          (when (k/== n 0) (k/break))
          (dotimes [i n]
            (k/= hash (k/*% (k/bit-xor hash (az/get buffer i)) 1099511628211)))))
      hash)))

(az/defn stop-background! :void []
  (when audio-ready
    (audio/ma_sound_uninit (k/& (az/get tracks active-track)))
    (k/= :_ (audio/ma_decoder_uninit (k/& (az/get decoders active-track))))
    (k/= audio-ready false)))

(az/defconst background-music-enabled false)

(az/defn reload-track! :bool
  "Render-thread only: initialize in a stable alternate slot before releasing old audio." []
  (when (k/! background-music-enabled) (stop-background!) (k/return false))
  (when (k/! engine-ready) (k/return false))
  (let [next (k/mod (k/+ active-track 1) 2)
        candidate (k/& (az/get tracks next))
        decoder (k/& (az/get decoders next))
        result (audio/ma_decoder_init_file "resources/demo/lesson.wav" k/null decoder)]
    (k/= track-hash (file-hash))
    (if (k/== result audio/MA_SUCCESS)
      (do
        ;; Own a fresh decoder, bypassing miniaudio's pathname resource cache.
        (when (k/!= (audio/ma_sound_init_from_data_source (k/& engine)
                                                          (k/as (k/ptrCast decoder) [:* audio/ma_data_source])
                                                          0 k/null candidate)
                    audio/MA_SUCCESS)
          (k/= :_ (audio/ma_decoder_uninit decoder))
          (k/= reload-failures (k/+ reload-failures 1))
          (k/return false))
        (audio/ma_sound_set_looping candidate 1)
        (audio/ma_sound_set_volume candidate (if (or audio-muted studio-audio-suppressed) 0.0 0.35))
        (if (k/== (audio/ma_sound_start candidate) audio/MA_SUCCESS)
          (do
            (when audio-ready
              (audio/ma_sound_uninit (k/& (az/get tracks active-track)))
              (k/= :_ (audio/ma_decoder_uninit (k/& (az/get decoders active-track)))))
            (k/= active-track next)
            (k/= audio-ready true)
            (k/= audio-revision (k/+ audio-revision 1))
            true)
          (do (audio/ma_sound_uninit candidate)
              (k/= :_ (audio/ma_decoder_uninit decoder))
              (k/= reload-failures (k/+ reload-failures 1)) false)))
      (do (k/= reload-failures (k/+ reload-failures 1)) false))))

(az/defn key-pressed? :bool [[key :i32] [slot :usize]]
  (let [down (k/== (glfw/glfwGetKey window key) glfw/GLFW_PRESS)
        previous (if (k/< slot 4) (az/get key-state slot) (az/get ui-key-state (k/- slot 4)))
        pressed (and down (k/! previous))]
    (if (k/< slot 4) (k/= (az/get key-state slot) down)
        (k/= (az/get ui-key-state (k/- slot 4)) down))
    pressed))

(az/defn reload-visuals! :void
  "Frame-boundary asset publication, after the worker has packed a complete atlas." []
  (gpu/renderer-wait-idle!)
  (gpu/load-atlas!)
  (when (k/!= development-assets k/null) ((az/unwrap development-assets)))
  (let [file (io/fopen "resources/demo/glyph-advances.bin" "rb")]
    (when (k/== file k/null) (debug/panic "Missing glyph metrics; run :prepare" []))
    (k/defer (k/= :_ (io/fclose file)))
    (when (k/!= (io/fread (k/& glyph-advances) 4 256 file) 256)
      (debug/panic "Invalid glyph metrics" [])))
  (let [file (io/fopen "resources/demo/ui-glyph-advances.bin" "rb")]
    (when (k/== file k/null) (debug/panic "Missing UI glyph metrics; run :prepare" []))
    (k/defer (k/= :_ (io/fclose file)))
    (when (k/!= (io/fread (k/& ui-glyph-advances) 4 256 file) 256)
      (debug/panic "Invalid UI glyph metrics" []))))

(az/defn retain-key-presses! :void
  "Keep a short press until the game polls it; never change Studio's input mode." []
  (when (k/!= window k/null)
    (glfw/glfwSetInputMode window glfw/GLFW_STICKY_KEYS glfw/GLFW_TRUE)))

(az/defn initialize! :bool []
  ;; Use the linked loader, including in a JVM without a dylib search-path override.
  (glfw/glfwInitVulkanLoader glfw/vkGetInstanceProcAddr)
  (when (k/!= (glfw/glfwInit) glfw/GLFW_TRUE) (k/return false))
  (glfw/glfwWindowHint glfw/GLFW_CLIENT_API glfw/GLFW_NO_API)
  (glfw/glfwWindowHint glfw/GLFW_RESIZABLE glfw/GLFW_FALSE)
  (k/= window (glfw/glfwCreateWindow 1100 760 "La Professeure | Vulkan playground" k/null k/null))
  (when (k/== window k/null) (k/return false))
  (retain-key-presses!)
  (when (k/! (gpu/initialize-renderer! window)) (k/return false))
  (reload-visuals!)
  (when (k/! (reload-story!)) (debug/panic "Missing/invalid dialogue asset; run :prepare" []))
  (k/= engine-ready (k/== (audio/ma_engine_init k/null (k/& engine)) audio/MA_SUCCESS))
  (k/= :_ (reload-track!))
  (k/= previous-time (glfw/glfwGetTime))
  true)

(az/defn shutdown! :void []
  (when (k/!= development-shutdown k/null) ((az/unwrap development-shutdown)))
  (shutdown-world!)
  (stop-voice!)
  (when audio-ready
    (audio/ma_sound_uninit (k/& (az/get tracks active-track)))
    (k/= :_ (audio/ma_decoder_uninit (k/& (az/get decoders active-track))))
    (k/= audio-ready false))
  (when engine-ready (audio/ma_engine_uninit (k/& engine)) (k/= engine-ready false))
  (gpu/shutdown-renderer!)
  (when (k/!= window k/null) (glfw/glfwDestroyWindow window) (k/= window k/null))
  (glfw/glfwTerminate))

(az/defn update! :void []
  (glfw/glfwPollEvents)
  (let [back (k/== (glfw/glfwGetKey window glfw/GLFW_KEY_BACKSPACE) glfw/GLFW_PRESS)]
    (when (and back (k/! back-was-down))
      (let [parent (:parent (az/get (:nodes (az/get stories active-story)) story-parent))]
        (when (k/!= parent story/no-parent)
          (k/= story-parent parent) (k/= story-page 0) (k/= choice-offset 0))))
    (k/= back-was-down back))
  (let [down (k/== (glfw/glfwGetKey window glfw/GLFW_KEY_PAGE_DOWN) glfw/GLFW_PRESS)
        up (k/== (glfw/glfwGetKey window glfw/GLFW_KEY_PAGE_UP) glfw/GLFW_PRESS)]
    (when (and down (k/! page-down-was-down) story-more-pages) (k/= story-page (k/+ story-page 1)))
    (when (and up (k/! page-up-was-down) (k/> story-page 0)) (k/= story-page (k/- story-page 1)))
    (k/= page-down-was-down down) (k/= page-up-was-down up))
  (let [down (k/== (glfw/glfwGetKey window glfw/GLFW_KEY_DOWN) glfw/GLFW_PRESS)
        up (k/== (glfw/glfwGetKey window glfw/GLFW_KEY_UP) glfw/GLFW_PRESS)
        next (k/== (glfw/glfwGetKey window glfw/GLFW_KEY_TAB) glfw/GLFW_PRESS)]
    (when (and down (k/! choice-down-was-down)) (k/= :_ (page-choices! true)))
    (when (and up (k/! choice-up-was-down)) (k/= :_ (page-choices! false)))
    (when (and next (k/! scene-next-was-down)) (next-scene!))
    (k/= choice-down-was-down down) (k/= choice-up-was-down up) (k/= scene-next-was-down next))
  (when (key-pressed? glfw/GLFW_KEY_1 4) (k/= response 1))
  (when (key-pressed? glfw/GLFW_KEY_2 5) (k/= response 2))
  (when (key-pressed? glfw/GLFW_KEY_3 6) (k/= response 3))
  (when (and (key-pressed? glfw/GLFW_KEY_F1 7) (k/!= development-focus k/null))
    ((az/unwrap development-focus)))
  (when (key-pressed? glfw/GLFW_KEY_SPACE 0) (k/= reveal-all true))
  (when (key-pressed? glfw/GLFW_KEY_L 1) (k/= light-enabled (k/! light-enabled)))
  (when (key-pressed? glfw/GLFW_KEY_M 2)
    (k/= audio-muted (k/! audio-muted))
    (when voice-ready
      (audio/ma_sound_set_volume (k/& (az/get voices voice-slot)) (if (or audio-muted studio-audio-suppressed) 0.0 0.8)))
    (when audio-ready
      (audio/ma_sound_set_volume (k/& (az/get tracks active-track)) (if (or audio-muted studio-audio-suppressed) 0.0 0.35))))
  (let [now (glfw/glfwGetTime)
        x (k/var (k/f64 0.0)) y (k/var (k/f64 0.0))]
    (when playing (k/= elapsed (k/+ elapsed (k/as (k/floatCast (k/min (k/- now previous-time) 0.1)) :f32))))
    (k/= previous-time now)
    (glfw/glfwGetCursorPos window (k/& x) (k/& y))
    (k/= hovered-choice 0)
    (when (and (k/>= x 180.0) (k/<= x 920.0))
      (when (and (k/>= y 473.0) (k/< y 509.0)) (k/= hovered-choice 1))
      (when (and (k/>= y 520.0) (k/< y 556.0)) (k/= hovered-choice 2))
      (when (and (k/>= y 567.0) (k/< y 603.0)) (k/= hovered-choice 3)))
    (let [down (k/== (glfw/glfwGetMouseButton window glfw/GLFW_MOUSE_BUTTON_LEFT) glfw/GLFW_PRESS)]
      (when (and down (k/! mouse-was-down))
        (if (k/> hovered-choice 0) (k/= response hovered-choice) (k/= reveal-all true)))
      (k/= mouse-was-down down))
    (k/= gpu/light-x (k/floatCast (k// x 1100.0)))
    (k/= gpu/light-y (k/floatCast (k// y 760.0)))
    (k/= gpu/lighting (if light-enabled 1.0 0.0))
    (when (or (key-pressed? glfw/GLFW_KEY_R 3) (k/>= (k/- now file-check-time) 1.0))
      (k/= file-check-time now)
      (when (and background-music-enabled (k/!= track-hash (file-hash))) (k/= :_ (reload-track!)))))
  (when (k/> response 0) (k/= :_ (choose-story! (k/+ response choice-offset))) (k/= response 0))
  (update-voice!)
  (k/= rendered-frames (k/+ rendered-frames 1)))

;; Render-thread canvas dimensions, restored by each independent window after
;; building its frame. Text positions stay in logical screen points on Retina.
(az/defvar canvas-width :f32 1100.0)

(az/defvar canvas-height :f32 760.0)

(az/defn vertex! :void
  [[x :f32] [y :f32] [u :f32] [v :f32] [rgb :u32] [textured :f32] [lit :f32]]
  (when (k/>= vertex-count gpu/frame-capacity) (debug/panic "La Professeure frame capacity exceeded" []))
  (k/= (az/get vertices vertex-count)
       (mesh/GpuVertex {:x (k/- (k// (k/* x 2.0) canvas-width) 1.0) :y (k/- (k// (k/* y 2.0) canvas-height) 1.0) :z 0.0
                        :r (k// (k/as (k/floatFromInt (k/& (k/>> rgb 16) 255)) :f32) 255.0)
                        :g (k// (k/as (k/floatFromInt (k/& (k/>> rgb 8) 255)) :f32) 255.0)
                        :b (k// (k/as (k/floatFromInt (k/& rgb 255)) :f32) 255.0)
                        :nx 0.0 :ny 0.0 :nz lit :wx u :wy v :wz 0.0
                        :roughness textured :vx 0.0 :vy 0.0 :vz 0.0}))
  (k/= vertex-count (k/+ vertex-count 1)))

(az/defn quad! :void
  [[x :f32] [y :f32] [w :f32] [h :f32] [u :f32] [v :f32] [uw :f32] [vh :f32]
   [rgb :u32] [textured :f32] [lit :f32]]
  (vertex! x y u v rgb textured lit)
  (vertex! (k/+ x w) y (k/+ u uw) v rgb textured lit)
  (vertex! x (k/+ y h) u (k/+ v vh) rgb textured lit)
  (vertex! x (k/+ y h) u (k/+ v vh) rgb textured lit)
  (vertex! (k/+ x w) y (k/+ u uw) v rgb textured lit)
  (vertex! (k/+ x w) (k/+ y h) (k/+ u uw) (k/+ v vh) rgb textured lit))

(az/defn rect! :void [[x :f32] [y :f32] [w :f32] [h :f32] [rgb :u32] [lit :f32]]
  (quad! x y w h 0.0 0.0 0.0 0.0 rgb 0.0 lit))

(az/defn glyph-code! :u32
  "Decode one UTF-8 code point into the font atlas, including French typography." [[text [:slice-const :u8]] [index [:* :usize]]]
  (let [first (az/get text (az/deref index)) code (k/var (k/u32 first)) extra (k/var (k/usize 0))]
    (k/= (az/deref index) (k/+ (az/deref index) 1))
    (cond
      (and (k/>= first 194) (k/< first 224)) (do (k/= code (k/& first 31)) (k/= extra 1))
      (and (k/>= first 224) (k/< first 240)) (do (k/= code (k/& first 15)) (k/= extra 2))
      (and (k/>= first 240) (k/< first 245)) (do (k/= code (k/& first 7)) (k/= extra 3))
      (k/>= first 128) (k/return 63))
    (dotimes [_ extra]
      (when (k/>= (az/deref index) (:len text)) (k/return 63))
      (let [byte (az/get text (az/deref index))]
        (when (k/!= (k/& byte 192) 128) (k/return 63))
        (k/= code (k/+ (k/* code 64) (k/& byte 63)))
        (k/= (az/deref index) (k/+ (az/deref index) 1))))
    (cond (k/< code 256) code
          (k/== code 339) 128 (k/== code 338) 129
          (k/== code 8216) 130 (k/== code 8217) 131
          (k/== code 8220) 132 (k/== code 8221) 133
          (k/== code 8211) 134 (k/== code 8212) 135
          (k/== code 8230) 136 (k/== code 8239) 137
          :else 63)))

(az/defn text! :void
  "UTF-8 Latin-1 plus French typography, using the loaded serif font atlas." [[text [:slice-const :u8]] [x :f32] [y :f32] [scale :f32] [rgb :u32]]
  (let [index (k/var (k/usize 0)) cursor (k/var (k/f32 x))]
    (k/while (k/< index (:len text))
      (let [code (glyph-code! text (k/& index))]
        (quad! (k/- cursor (k/* 3.0 scale)) (k/+ y scale) (k/* 62.0 scale) (k/* 78.0 scale)
               (k/+ 1.0 (k/as (k/floatFromInt (k/* (k/mod code 32) 64)) :f32))
               (k/+ 1.0 (k/as (k/floatFromInt (k/* (k/divTrunc code 32) 80)) :f32))
               62.0 78.0 rgb 1.0 0.0)
        (k/= cursor (k/+ cursor (k/* (az/get glyph-advances code) scale)))))))

(az/defn text-width :f32 [[text [:slice-const :u8]] [scale :f32]]
  (let [index (k/var (k/usize 0)) width (k/var (k/f32 0.0))]
    (k/while (k/< index (:len text))
      (let [code (glyph-code! text (k/& index))]
        (k/= width (k/+ width (k/* (az/get glyph-advances code) scale))))) width))

(az/defn revealed-prefix! :usize [[text [:slice-const :u8]]]
  (let [visible (k/var (k/usize 0))]
    (k/while (and (k/< visible (:len text)) (k/> reveal-remaining 0))
      (k/= :_ (glyph-code! text (k/& visible)))
      (k/= reveal-remaining (k/- reveal-remaining 1)))
    visible))

(az/defn wrapped-text! :f32
  "Word-wrap using actual font advances; render only the current body page." [[text [:slice-const :u8]] [x :f32] [y :f32] [width :f32] [scale :f32] [rgb :u32]]
  (let [start (k/var (k/usize 0)) cursor (k/var (k/f32 x)) row (k/var (k/f32 y))]
    (k/while (k/< start (:len text))
      (let [end (k/var (k/usize start))]
        (k/while (and (k/< end (:len text)) (k/!= (az/get text end) 32)) (k/= end (k/+ end 1)))
        (let [word (az/slice text start end) word-width (text-width word scale)]
          (when (and (k/> cursor x) (k/> (k/+ cursor word-width) (k/+ x width)))
            (k/= cursor x) (k/= row (k/+ row 26.0)))
          (when (and (k/>= row 217.0) (k/< row 425.0))
            (let [visible (revealed-prefix! word)]
              (text! (az/slice word 0 visible) cursor row scale rgb)))
          (when (k/>= row 425.0) (k/= story-more-pages true))
          (k/= cursor (k/+ cursor word-width (k/* (az/get glyph-advances 32) scale))))
        (k/= start (k/+ end 1))))
    (k/+ row 32.0)))

(az/defn build-frame :u32 {:zig/qualifiers "callconv(.c)"}
  [[output [:c-pointer mesh/GpuVertex]] [width :i32] [height :i32]]
  (k/= :_ width) (k/= :_ height)
  (k/= vertices output) (k/= vertex-count 0)
  (when (or (k/!= reveal-parent story-parent) (k/!= reveal-page story-page) (k/!= reveal-story active-story))
    (k/= reveal-parent story-parent) (k/= reveal-page story-page) (k/= reveal-story active-story)
    (k/= reveal-start (glfw/glfwGetTime)) (k/= reveal-all false))
  (k/= reveal-remaining (if reveal-all 262144
                            (k/as (k/intFromFloat (k/min 262144.0 (k/* 42.0 (k/max 0.0 (k/- (glfw/glfwGetTime) reveal-start))))) :usize)))
  (rect! 0.0 0.0 1100.0 760.0 0x161b1e 0.0)
  (text! "LA PROFESSEURE" 180.0 27.0 0.60 0xe9e0c9)
  (rect! 180.0 98.0 48.0 2.0 0xbc8f5b 0.0)
  (let [data (k/& (az/get stories active-story))
        scene (:scene (az/get (:nodes data) story-parent))
        row (k/var (k/f32 (k/- 217.0 (k/* (k/as (k/floatFromInt story-page) :f32) 208.0))))]
    (text! (story-text scene) 180.0 125.0 0.65 0xece3ce)
    (k/= story-more-pages false)
    (dotimes [i (:count data)]
      (let [n (az/get (:nodes data) i)]
        (when (and (k/== (:parent n) story-parent) (k/== (:kind n) 2))
          (when (k/!= (:speaker n) 0)
            (when (and (k/>= row 217.0) (k/< row 425.0))
              (text! (if (k/== (:speaker n) 86) "LA VOITURE" "LA MANGUE")
                     180.0 row 0.30 0xbc8f5b))
            (k/= row (k/+ row 26.0)))
          (k/= row (wrapped-text! (story-text (k/intCast i)) 180.0 row 740.0 0.44 0xd7d2c5))))))
  (rect! 180.0 445.0 740.0 1.0 0x414644 0.0)
  (when (and (k/> hovered-choice 0)
             (k/!= (story-choice (k/+ hovered-choice choice-offset)) story/no-parent))
    (rect! 180.0 (k/+ 473.0 (k/* 47.0 (k/as (k/floatFromInt (k/- hovered-choice 1)) :f32)))
           740.0 36.0 0x262b2b 0.0))
  (dotimes [i 3]
    (let [choice (story-choice (k/+ choice-offset (k/as (k/intCast (k/+ i 1)) :u32)))
          y (k/+ 473.0 (k/* 47.0 (k/as (k/floatFromInt i) :f32)))
          color (if (story-choice-visited? choice) (k/as 0x969081 :u32) (k/as 0xd4b07c :u32))]
      (when (k/!= choice story/no-parent)
        (text! (if (k/== i 0) "1." (if (k/== i 1) "2." "3.")) 190.0 y 0.40 color)
        (let [label (story-text choice) scale (k/min 0.40 (k// 700.0 (k/max 1.0 (text-width label 1.0))))]
          (text! label 218.0 y scale color)))))
  (when (or (k/> choice-offset 0) (k/!= (story-choice 4) story/no-parent))
    (text! "Haut / bas : autres choix" 180.0 617.0 0.27 0xbc8f5b))
  (text! "Espace / clic : afficher le texte     Retour arrière : revenir" 180.0 650.0 0.28 0x89918d)
  (text! "Page préc./suiv. : texte   TAB : scène" 180.0 678.0 0.27 0x89918d)
  (text! "1 - 3 Choisir     F1 Studio" 180.0 716.0 0.28 0x89918d)
  vertex-count)

(az/defn tick! :bool []
  (when (k/!= (glfw/glfwWindowShouldClose window) 0) (k/return false))
  (update!)
  (ensure-world!)
  (k/= :_ (flecs/ecs_progress world 0.0))
  (when (and (k/== (glfw/glfwGetWindowAttrib window glfw/GLFW_ICONIFIED) 0)
             (k/!= (glfw/glfwGetWindowAttrib window glfw/GLFW_VISIBLE) 0))
    (k/= :_ (gpu/render! (k/& build-frame))))
  (when (k/!= development-tick k/null) ((az/unwrap development-tick)))
  true)

(az/defn main :void []
  (let [ready (initialize!)]
    (k/defer (shutdown!))
    (when ready
      (k/while true (when (k/! (tick!)) (k/break))))))
