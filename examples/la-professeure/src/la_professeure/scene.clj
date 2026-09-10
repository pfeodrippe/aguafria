(ns la-professeure.scene
  "Native stage, animation, text, 2D light and stable miniaudio resource ownership."
  (:require [aguafria.std] [aguafria.keyword :as ak] [aguafria.zig :as az]
            [aguafria.std.mem :as mem] [aguafria.std.math :as math]
            [aguafria.std.debug :as debug]
            [aguafria-examples-native.bindings]
            [aguafria-examples-native.bindings.glfw :as glfw]
            [aguafria-examples-native.bindings.flecs :as flecs]
            [aguafria-examples-native.bindings.runtime :as io]
            [aguafria-examples-native.mesh :as mesh]
            [la-professeure.gpu :as gpu]
            [la-professeure.recording-tool :as story]
            [la-professeure.build :as build]))

(build/load-native!)
(require '[la-professeure.miniaudio :as audio])

(az/defconst animation-fps :f32 8.0)

(az/defstruct Snapshot {:layout :extern}
  [[:time :f32] [:frame :u32] [:playing :bool] [:audio_ready :bool]
   [:audio_revision :u32] [:reload_failures :u32] [:rendered_frames :u64]])

(az/defvar window [:optional [:* glfw/GLFWwindow]] ak/null)
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
(az/defvar engine audio/ma_engine (mem/zeroes (az/type audio/ma_engine)))
(az/defvar tracks [:array 2 audio/ma_sound] (mem/zeroes (az/type [:array 2 audio/ma_sound])))
(az/defvar decoders [:array 2 audio/ma_decoder] (mem/zeroes (az/type [:array 2 audio/ma_decoder])))
(az/defvar active-track :usize 0)
(az/defvar key-state [:array 4 :bool] (mem/zeroes (az/type [:array 4 :bool])))
(az/defvar ui-key-state [:array 4 :bool] (mem/zeroes (az/type [:array 4 :bool])))
(az/defvar glyph-advances [:array 256 :f32] (mem/zeroes (az/type [:array 256 :f32])))
(az/defvar response :u32 0)
(az/defvar hovered-choice :u32 0)
(az/defvar mouse-was-down false)
(az/defvar vertices [:c-pointer mesh/GpuVertex] ak/null)
(az/defvar vertex-count :u32 0)
(az/defconst DevelopmentView (az/type [:*const [:fn {:callconv :.c} [] :void]]))
(az/defvar development-tick [:optional DevelopmentView] ak/null)
(az/defvar development-shutdown [:optional DevelopmentView] ak/null)
(az/defvar development-assets [:optional DevelopmentView] ak/null)
(az/defvar development-focus [:optional DevelopmentView] ak/null)
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
(az/defvar world [:optional [:* flecs/ecs_world_t]] ak/null)
(az/defvar passage-component :u64 0)
(az/defvar passage-entities [:array 1024 :u64] (mem/zeroes (az/type [:array 1024 :u64])))
(az/defvar passage-entity-count :u32 0)

(az/defn ensure-world! :- :void []
  (when (ak/== world ak/null)
    (set! world (flecs/ecs_init))
    (let [entity-desc (flecs/ecs_entity_desc_t {:name "DialoguePassage"})
          component-entity (flecs/ecs_entity_init world (ak/& entity-desc))
          type-info (flecs/ecs_type_info_t {:size (ak/sizeOf PassageState) :alignment (ak/alignOf PassageState)})
          desc (flecs/ecs_component_desc_t {:entity component-entity :type type-info})]
      (set! passage-component (flecs/ecs_component_init world (ak/& desc))))))

(az/defn shutdown-world! :- :void []
  (when (ak/!= world ak/null) (set! _ (flecs/ecs_fini world)) (set! world ak/null))
  (set! passage-entity-count 0)
  (set! passage-entities (mem/zeroes (az/type [:array 1024 :u64]))))

(az/defstruct Story {:layout :extern}
  [[:count :u32] [:text_length :u32] [:nodes [:array 1024 story/Node]]
   [:text [:array 262144 :u8]]])

(az/defvar stories [:array 2 Story] (mem/zeroes (az/type [:array 2 Story])))
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
(az/defvar voices [:array 2 audio/ma_sound] (mem/zeroes (az/type [:array 2 audio/ma_sound])))
(az/defvar voice-decoders [:array 2 audio/ma_decoder] (mem/zeroes (az/type [:array 2 audio/ma_decoder])))
(az/defvar voice-slot :usize 0)
(az/defvar voice-ready false)
(az/defvar voice-revision :u32 0)
(az/defvar voice-parent :u32 story/no-parent)
(az/defvar voice-node :u32 story/no-parent)
(az/defvar voice-path [:array 4096 :u8] (mem/zeroes (az/type [:array 4096 :u8])))
(az/defvar voice-hash :u64 0)
(az/defvar voice-check-time :f64 0.0)
(eval `(az/defconst ~'live-voices? ~(boolean (:reloadable? (az/configuration)))))

(az/defn stop-voice! :- :void []
  (when voice-ready
    (audio/ma_sound_uninit (ak/& (az/index voices voice-slot)))
    (set! _ (audio/ma_decoder_uninit (ak/& (az/index voice-decoders voice-slot))))
    (set! voice-ready false)))

(az/defn play-voice-file!
  "Render-thread only. Validate a candidate before releasing the current voice."
  :- :bool [[path [:slice-const :u8]]]
  (when (or (ak/! engine-ready) (ak/== (az/field path len) 0)
            (>= (az/field path len) 4096)) (ak/return false))
  (let [^{:var [:array 4096 :u8]} filename (mem/zeroes (az/type [:array 4096 :u8]))
        next (mod (+ voice-slot 1) 2)
        decoder (ak/& (az/index voice-decoders next))
        candidate (ak/& (az/index voices next))]
    (dotimes [i (az/field path len)] (when (ak/== (az/index path i) 0) (ak/return false)))
    (ak/memcpy (az/slice filename 0 (az/field path len)) path)
    (when (ak/!= (audio/ma_decoder_init_file (ak/& filename) ak/null decoder) audio/MA_SUCCESS)
      (ak/return false))
    (when (ak/!= (audio/ma_sound_init_from_data_source (ak/& engine)
                    (ak/as (az/type [:* audio/ma_data_source]) (ak/ptrCast decoder))
                    0 ak/null candidate) audio/MA_SUCCESS)
      (set! _ (audio/ma_decoder_uninit decoder)) (ak/return false))
    (audio/ma_sound_set_looping candidate 0)
    (audio/ma_sound_set_volume candidate (if (or audio-muted studio-audio-suppressed) 0.0 0.8))
    (when (ak/!= (audio/ma_sound_start candidate) audio/MA_SUCCESS)
      (audio/ma_sound_uninit candidate) (set! _ (audio/ma_decoder_uninit decoder)) (ak/return false))
    (stop-voice!) (set! voice-slot next) (set! voice-ready true)
    (set! voice-revision (+ voice-revision 1)) true))

(az/defn current-voice-hash :- :u64 []
  (let [file (io/fopen (ak/& voice-path) "rb")]
    (when (ak/== file ak/null) (ak/return 0))
    (ak/defer (set! _ (io/fclose file)))
    (let [^{:var [:array 4096 :u8]} buffer ak/undefined
          ^{:var :u64} hash 14695981039346656037]
      (ak/while true
        (let [n (io/fread (ak/& buffer) 1 4096 file)]
          (when (ak/== n 0) (ak/break))
          (dotimes [i n] (set! hash (ak/*% (ak/bit-xor hash (az/index buffer i)) 1099511628211)))))
      hash)))

(az/defn load-node-voice! :- :bool [[index :u32]]
  (when (>= index (az/field (az/index stories active-story) count)) (ak/return false))
  (let [node (az/index (az/field (az/index stories active-story) nodes) index)
        length (az/field node id_len)]
    (when (or (ak/== length 0) (> length 64)) (ak/return false))
    (dotimes [i length]
      (let [c (az/index (az/field node id) i)]
        (when (ak/! (or (and (>= c 65) (<= c 90)) (and (>= c 97) (<= c 122))
                       (and (>= c 48) (<= c 57)) (ak/== c 45) (ak/== c 95))) (ak/return false))))
    (set! voice-path (mem/zeroes (az/type [:array 4096 :u8])))
    (ak/memcpy (az/slice voice-path 0 17) "resources/voices/")
    (ak/memcpy (az/slice voice-path 17 (+ 17 length)) (az/slice (az/field node id) 0 length))
    (ak/memcpy (az/slice voice-path (+ 17 length) (+ 21 length)) ".wav")
    (set! voice-node index) (set! voice-hash (current-voice-hash))
    (play-voice-file! (az/slice voice-path 0 (+ 21 length)))))

(az/defn update-voice! :- :void []
  (let [data (ak/& (az/index stories active-story))
        changed (ak/!= voice-parent story-parent)]
    (when changed
      (stop-voice!) (set! voice-parent story-parent) (set! voice-node story/no-parent))
    ;; Advance through voiced passages in their Markdown order. Missing takes
    ;; are silent, never substituted with unrelated audio.
    (when (or changed (and voice-ready (ak/!= (audio/ma_sound_at_end (ak/& (az/index voices voice-slot))) 0)))
      (let [start (if (ak/== voice-node story/no-parent) (ak/as :u32 0) (+ voice-node 1))]
        (dotimes [i (az/field data count)]
          (let [n (az/index (az/field data nodes) i)]
            (when (and (>= i start) (ak/== (az/field n parent) story-parent)
                       (ak/== (az/field n kind) 2) (> (az/field n id_len) 0))
              (when (load-node-voice! (ak/intCast i)) (ak/return)))))))
    (when (and live-voices? (ak/!= voice-node story/no-parent)
               (>= (- (glfw/glfwGetTime) voice-check-time) 0.5))
      (set! voice-check-time (glfw/glfwGetTime))
      (when (ak/!= voice-hash (current-voice-hash)) (set! _ (load-node-voice! voice-node))))))

(az/defn passage-count :- :i32 []
  (if (ak/== world ak/null) 0 (flecs/ecs_count_id world passage-component)))

(az/defn sync-passages! :- :void []
  (ensure-world!)
  (let [data (ak/& (az/index stories active-story))
        previous-entities passage-entities
        previous-count passage-entity-count]
    (dotimes [i (az/field data count)]
      (let [node (az/index (az/field data nodes) i)
            ^{:var [:array 65 :u8]} name (mem/zeroes (az/type [:array 65 :u8]))]
        (ak/memcpy (az/slice name 0 (az/field node id_len)) (az/slice (az/field node id) 0 (az/field node id_len)))
        (let [desc (flecs/ecs_entity_desc_t {:name (if (> (az/field node id_len) 0) (ak/& name) ak/null)})
              entity (flecs/ecs_entity_init world (ak/& desc))
              old (flecs/ecs_get_id world entity passage-component)
              state (PassageState {:node (ak/intCast i) :revision (az/field node revision)
                                   :takes (if (ak/!= old ak/null) (az/field (az/cast old [:*const PassageState]) takes) 0)
                                   :visited (if (ak/!= old ak/null) (az/field (az/cast old [:*const PassageState]) visited) false)})]
          (set! (az/index passage-entities i) entity)
          (set! _ (flecs/ecs_set_id world entity passage-component (ak/sizeOf PassageState) (ak/& state))))))
    (set! passage-entity-count (az/field data count))
    (dotimes [i previous-count]
      (let [entity (az/index previous-entities i) ^:var retained false]
        (dotimes [j passage-entity-count]
          (when (ak/== entity (az/index passage-entities j)) (set! retained true)))
        (when (ak/! retained) (flecs/ecs_delete world entity))))))

(az/defn reload-story!
  "Validate a complete compiled Markdown asset before changing the visible dialogue."
  :- :bool []
  (let [file (io/fopen "resources/demo/story.lpdialogue" "rb")]
    (when (ak/== file ak/null) (ak/return false))
    (ak/defer (set! _ (io/fclose file)))
    (let [^{:var [:array 8 :u8]} magic ak/undefined
          slot (mod (+ active-story 1) 2)
          candidate (ak/& (az/index stories slot))]
      (when (or (ak/!= (io/fread (ak/& magic) 1 8 file) 8)
                (ak/! (mem/eql (az/type :u8) (ak/& magic) "LPDIAG01"))) (ak/return false))
      (when (or (ak/!= (io/fread (ak/& (az/field candidate count)) 4 1 file) 1)
                (ak/!= (io/fread (ak/& (az/field candidate text_length)) 4 1 file) 1)
                (ak/== (az/field candidate count) 0) (> (az/field candidate count) 1024)
                (> (az/field candidate text_length) 262144)) (ak/return false))
      (when (or (ak/!= (io/fread (ak/& (az/field candidate nodes)) (ak/sizeOf story/Node)
                                (az/field candidate count) file) (az/field candidate count))
                (ak/!= (io/fread (ak/& (az/field candidate text)) 1 (az/field candidate text_length) file)
                       (az/field candidate text_length))) (ak/return false))
      (dotimes [i (az/field candidate count)]
        (let [n (az/index (az/field candidate nodes) i)]
          (when (or (> (az/field n kind) 2) (> (az/field n scene) i)
                    (and (ak/!= (az/field n parent) story/no-parent) (>= (az/field n parent) i))
                    (> (az/field n offset) (az/field candidate text_length))
                    (> (az/field n length) (- (az/field candidate text_length) (az/field n offset))))
            (ak/return false))))
      (when (ak/!= (az/field (az/index (az/field candidate nodes) 0) kind) 0) (ak/return false))
      (set! active-story slot) (set! story-parent 0) (set! story-page 0) (set! choice-offset 0) (set! response 0)
      (set! voice-parent story/no-parent)
      (set! story-ready true) (sync-passages!) true)))

(az/defn story-text :- [:slice-const :u8] [[index :u32]]
  (let [data (ak/& (az/index stories active-story))]
    (when (>= index (az/field data count)) (ak/return ""))
    (let [node (az/index (az/field data nodes) index)]
      (az/slice (az/field data text) (az/field node offset)
                (+ (az/field node offset) (az/field node length))))))

(az/defn story-choice-parent :- :u32 []
  ;; Keep the leaf's response visible while returning to its enclosing choices.
  ;; Climb only authored parents; never cross into another scene.
  (let [data (ak/& (az/index stories active-story)) ^{:var :u32} parent story-parent]
    (dotimes [_ (az/field data count)]
      (when (or (ak/== parent story/no-parent) (>= parent (az/field data count)))
        (ak/return story/no-parent))
      (dotimes [i (az/field data count)]
        (let [n (az/index (az/field data nodes) i)]
          (when (and (ak/== (az/field n parent) parent) (ak/== (az/field n kind) 1))
            (ak/return parent))))
      (set! parent (az/field (az/index (az/field data nodes) parent) parent)))
    story/no-parent))

(az/defn story-choice-visited? :- :bool [[index :u32]]
  (when (or (ak/== world ak/null) (>= index passage-entity-count)) (ak/return false))
  (let [state (flecs/ecs_get_id world (az/index passage-entities index) passage-component)]
    (and (ak/!= state ak/null) (az/field (az/cast state [:*const PassageState]) visited))))

(az/defn story-choice :- :u32 [[ordinal :u32]]
  (let [data (ak/& (az/index stories active-story)) parent (story-choice-parent) ^{:var :u32} found 0]
    (when (ak/== parent story/no-parent) (ak/return story/no-parent))
    (dotimes [i (az/field data count)]
      (let [n (az/index (az/field data nodes) i)]
        (when (and (ak/== (az/field n parent) parent) (ak/== (az/field n kind) 1))
          (set! found (+ found 1))
          (when (ak/== found ordinal) (ak/return (ak/intCast i))))))
    story/no-parent))

(az/defn choose-story! :- :bool [[ordinal :u32]]
  (let [next (story-choice ordinal)]
    (when (ak/== next story/no-parent) (ak/return false))
    (let [state (az/cast (flecs/ecs_get_mut_id world (az/index passage-entities next) passage-component) [:* PassageState])]
      (set! (az/field state visited) true))
    (set! story-parent next) (set! story-page 0) (set! choice-offset 0)
    (set! reveal-parent story/no-parent) (set! voice-parent story/no-parent) true))

(az/defn page-choices! :- :bool [[forward :bool]]
  (if forward
    (do (when (ak/== (story-choice (+ choice-offset 4)) story/no-parent) (ak/return false))
        (set! choice-offset (+ choice-offset 3)))
    (do (when (ak/== choice-offset 0) (ak/return false))
        (set! choice-offset (- choice-offset 3))))
  true)

(az/defn next-scene! :- :void []
  (let [data (ak/& (az/index stories active-story))
        current (az/field (az/index (az/field data nodes) story-parent) scene)]
    (dotimes [step (az/field data count)]
      (let [index (mod (+ current (ak/as :u32 (ak/intCast step)) 1) (az/field data count))]
        (when (ak/== (az/field (az/index (az/field data nodes) index) kind) 0)
          (set! story-parent index) (set! story-page 0) (set! choice-offset 0) (ak/return))))))

(az/defn animation-frame :- :u32 [[seconds :f32] [fps :f32]]
  (ak/intFromFloat (mod (ak/floor (* (ak/max seconds 0.0) (ak/max fps 0.0))) 8.0)))

(az/defn bob-height
  "Edit this declaration while the native window runs."
  :- :f32 []
  (* 8.0 (math/sin (* elapsed 2.0))))

(az/defn snapshot :- Snapshot []
  (Snapshot {:time elapsed :frame (animation-frame elapsed animation-fps)
             :playing playing :audio_ready audio-ready :audio_revision audio-revision
             :reload_failures reload-failures :rendered_frames rendered-frames}))

(az/defn file-hash :- :u64 []
  (let [file (io/fopen "resources/demo/lesson.wav" "rb")]
    (when (ak/== file ak/null) (ak/return 0))
    (ak/defer (set! _ (io/fclose file)))
    (let [^{:var [:array 4096 :u8]} buffer ak/undefined
          ^{:var :u64} hash 14695981039346656037]
      (ak/while true
        (let [n (io/fread (ak/& buffer) 1 4096 file)]
          (when (ak/== n 0) (ak/break))
          (dotimes [i n]
            (set! hash (ak/*% (ak/bit-xor hash (az/index buffer i)) 1099511628211)))))
      hash)))

(az/defn stop-background! :- :void []
  (when audio-ready
    (audio/ma_sound_uninit (ak/& (az/index tracks active-track)))
    (set! _ (audio/ma_decoder_uninit (ak/& (az/index decoders active-track))))
    (set! audio-ready false)))

(az/defconst background-music-enabled false)

(az/defn reload-track!
  "Render-thread only: initialize in a stable alternate slot before releasing old audio."
  :- :bool []
  (when (ak/! background-music-enabled) (stop-background!) (ak/return false))
  (when (ak/! engine-ready) (ak/return false))
  (let [next (mod (+ active-track 1) 2)
        candidate (ak/& (az/index tracks next))
        decoder (ak/& (az/index decoders next))
        result (audio/ma_decoder_init_file "resources/demo/lesson.wav" ak/null decoder)]
    (set! track-hash (file-hash))
    (if (ak/== result audio/MA_SUCCESS)
      (do
        ;; Own a fresh decoder, bypassing miniaudio's pathname resource cache.
        (when (ak/!= (audio/ma_sound_init_from_data_source (ak/& engine)
                        (ak/as (az/type [:* audio/ma_data_source]) (ak/ptrCast decoder))
                        0 ak/null candidate)
                      audio/MA_SUCCESS)
          (set! _ (audio/ma_decoder_uninit decoder))
          (set! reload-failures (+ reload-failures 1))
          (ak/return false))
        (audio/ma_sound_set_looping candidate 1)
        (audio/ma_sound_set_volume candidate (if (or audio-muted studio-audio-suppressed) 0.0 0.35))
        (if (ak/== (audio/ma_sound_start candidate) audio/MA_SUCCESS)
          (do
            (when audio-ready
              (audio/ma_sound_uninit (ak/& (az/index tracks active-track)))
              (set! _ (audio/ma_decoder_uninit (ak/& (az/index decoders active-track)))))
            (set! active-track next)
            (set! audio-ready true)
            (set! audio-revision (+ audio-revision 1))
            true)
          (do (audio/ma_sound_uninit candidate)
              (set! _ (audio/ma_decoder_uninit decoder))
              (set! reload-failures (+ reload-failures 1)) false)))
      (do (set! reload-failures (+ reload-failures 1)) false))))

(az/defn key-pressed? :- :bool [[key :i32] [slot :usize]]
  (let [down (ak/== (glfw/glfwGetKey window key) glfw/GLFW_PRESS)
        previous (if (< slot 4) (az/index key-state slot) (az/index ui-key-state (- slot 4)))
        pressed (and down (ak/! previous))]
    (if (< slot 4) (set! (az/index key-state slot) down)
      (set! (az/index ui-key-state (- slot 4)) down))
    pressed))

(az/defn reload-visuals!
  "Frame-boundary asset publication, after the worker has packed a complete atlas."
  :- :void []
  (gpu/renderer-wait-idle!)
  (gpu/load-atlas!)
  (when (ak/!= development-assets ak/null) ((az/unwrap development-assets)))
  (let [file (io/fopen "resources/demo/glyph-advances.bin" "rb")]
    (when (ak/== file ak/null) (debug/panic "Missing glyph metrics; run :prepare" []))
    (ak/defer (set! _ (io/fclose file)))
    (when (ak/!= (io/fread (ak/& glyph-advances) 4 256 file) 256)
      (debug/panic "Invalid glyph metrics" []))))

(az/defn initialize! :- :bool []
  ;; Use the linked loader, including in a JVM without a dylib search-path override.
  (glfw/glfwInitVulkanLoader glfw/vkGetInstanceProcAddr)
  (when (ak/!= (glfw/glfwInit) glfw/GLFW_TRUE) (ak/return false))
  (glfw/glfwWindowHint glfw/GLFW_CLIENT_API glfw/GLFW_NO_API)
  (glfw/glfwWindowHint glfw/GLFW_RESIZABLE glfw/GLFW_FALSE)
  (set! window (glfw/glfwCreateWindow 1100 760 "La Professeure | Vulkan playground" ak/null ak/null))
  (when (ak/== window ak/null) (ak/return false))
  (when (ak/! (gpu/initialize-renderer! window)) (ak/return false))
  (reload-visuals!)
  (when (ak/! (reload-story!)) (debug/panic "Missing/invalid dialogue asset; run :prepare" []))
  (set! engine-ready (ak/== (audio/ma_engine_init ak/null (ak/& engine)) audio/MA_SUCCESS))
  (set! _ (reload-track!))
  (set! previous-time (glfw/glfwGetTime))
  true)

(az/defn shutdown! :- :void []
  (when (ak/!= development-shutdown ak/null) ((az/unwrap development-shutdown)))
  (shutdown-world!)
  (stop-voice!)
  (when audio-ready
    (audio/ma_sound_uninit (ak/& (az/index tracks active-track)))
    (set! _ (audio/ma_decoder_uninit (ak/& (az/index decoders active-track))))
    (set! audio-ready false))
  (when engine-ready (audio/ma_engine_uninit (ak/& engine)) (set! engine-ready false))
  (gpu/shutdown-renderer!)
  (when (ak/!= window ak/null) (glfw/glfwDestroyWindow window) (set! window ak/null))
  (glfw/glfwTerminate))

(az/defn update! :- :void []
  (glfw/glfwPollEvents)
  (let [back (ak/== (glfw/glfwGetKey window glfw/GLFW_KEY_BACKSPACE) glfw/GLFW_PRESS)]
    (when (and back (ak/! back-was-down))
      (let [parent (az/field (az/index (az/field (az/index stories active-story) nodes) story-parent) parent)]
        (when (ak/!= parent story/no-parent)
          (set! story-parent parent) (set! story-page 0) (set! choice-offset 0))))
    (set! back-was-down back))
  (let [down (ak/== (glfw/glfwGetKey window glfw/GLFW_KEY_PAGE_DOWN) glfw/GLFW_PRESS)
        up (ak/== (glfw/glfwGetKey window glfw/GLFW_KEY_PAGE_UP) glfw/GLFW_PRESS)]
    (when (and down (ak/! page-down-was-down) story-more-pages) (set! story-page (+ story-page 1)))
    (when (and up (ak/! page-up-was-down) (> story-page 0)) (set! story-page (- story-page 1)))
    (set! page-down-was-down down) (set! page-up-was-down up))
  (let [down (ak/== (glfw/glfwGetKey window glfw/GLFW_KEY_DOWN) glfw/GLFW_PRESS)
        up (ak/== (glfw/glfwGetKey window glfw/GLFW_KEY_UP) glfw/GLFW_PRESS)
        next (ak/== (glfw/glfwGetKey window glfw/GLFW_KEY_TAB) glfw/GLFW_PRESS)]
    (when (and down (ak/! choice-down-was-down)) (set! _ (page-choices! true)))
    (when (and up (ak/! choice-up-was-down)) (set! _ (page-choices! false)))
    (when (and next (ak/! scene-next-was-down)) (next-scene!))
    (set! choice-down-was-down down) (set! choice-up-was-down up) (set! scene-next-was-down next))
  (when (key-pressed? glfw/GLFW_KEY_1 4) (set! response 1))
  (when (key-pressed? glfw/GLFW_KEY_2 5) (set! response 2))
  (when (key-pressed? glfw/GLFW_KEY_3 6) (set! response 3))
  (when (and (key-pressed? glfw/GLFW_KEY_F1 7) (ak/!= development-focus ak/null))
    ((az/unwrap development-focus)))
  (when (key-pressed? glfw/GLFW_KEY_SPACE 0) (set! reveal-all true))
  (when (key-pressed? glfw/GLFW_KEY_L 1) (set! light-enabled (ak/! light-enabled)))
  (when (key-pressed? glfw/GLFW_KEY_M 2)
    (set! audio-muted (ak/! audio-muted))
    (when voice-ready
      (audio/ma_sound_set_volume (ak/& (az/index voices voice-slot)) (if (or audio-muted studio-audio-suppressed) 0.0 0.8)))
    (when audio-ready
      (audio/ma_sound_set_volume (ak/& (az/index tracks active-track)) (if (or audio-muted studio-audio-suppressed) 0.0 0.35))))
  (let [now (glfw/glfwGetTime)
        ^{:var :f64} x 0.0 ^{:var :f64} y 0.0]
    (when playing (set! elapsed (+ elapsed (ak/as :f32 (ak/floatCast (ak/min (- now previous-time) 0.1))))))
    (set! previous-time now)
    (glfw/glfwGetCursorPos window (ak/& x) (ak/& y))
    (set! hovered-choice 0)
    (when (and (>= x 180.0) (<= x 920.0))
      (when (and (>= y 473.0) (< y 509.0)) (set! hovered-choice 1))
      (when (and (>= y 520.0) (< y 556.0)) (set! hovered-choice 2))
      (when (and (>= y 567.0) (< y 603.0)) (set! hovered-choice 3)))
    (let [down (ak/== (glfw/glfwGetMouseButton window glfw/GLFW_MOUSE_BUTTON_LEFT) glfw/GLFW_PRESS)]
      (when (and down (ak/! mouse-was-down))
        (if (> hovered-choice 0) (set! response hovered-choice) (set! reveal-all true)))
      (set! mouse-was-down down))
    (set! gpu/light-x (ak/floatCast (/ x 1100.0)))
    (set! gpu/light-y (ak/floatCast (/ y 760.0)))
    (set! gpu/lighting (if light-enabled 1.0 0.0))
    (when (or (key-pressed? glfw/GLFW_KEY_R 3) (>= (- now file-check-time) 1.0))
      (set! file-check-time now)
      (when (and background-music-enabled (ak/!= track-hash (file-hash))) (set! _ (reload-track!)))))
  (when (> response 0) (set! _ (choose-story! (+ response choice-offset))) (set! response 0))
  (update-voice!)
  (set! rendered-frames (+ rendered-frames 1)))

;; Render-thread canvas dimensions, restored by each independent window after
;; building its frame. Text positions stay in logical screen points on Retina.
(az/defvar canvas-width :f32 1100.0)
(az/defvar canvas-height :f32 760.0)

(az/defn vertex! :- :void
  [[x :f32] [y :f32] [u :f32] [v :f32] [rgb :u32] [textured :f32] [lit :f32]]
  (when (>= vertex-count gpu/frame-capacity) (debug/panic "La Professeure frame capacity exceeded" []))
  (set! (az/index vertices vertex-count)
        (mesh/GpuVertex {:x (- (/ (* x 2.0) canvas-width) 1.0) :y (- (/ (* y 2.0) canvas-height) 1.0) :z 0.0
                         :r (/ (ak/as :f32 (ak/floatFromInt (ak/& (ak/>> rgb 16) 255))) 255.0)
                         :g (/ (ak/as :f32 (ak/floatFromInt (ak/& (ak/>> rgb 8) 255))) 255.0)
                         :b (/ (ak/as :f32 (ak/floatFromInt (ak/& rgb 255))) 255.0)
                         :nx 0.0 :ny 0.0 :nz lit :wx u :wy v :wz 0.0
                         :roughness textured :vx 0.0 :vy 0.0 :vz 0.0}))
  (set! vertex-count (+ vertex-count 1)))

(az/defn quad! :- :void
  [[x :f32] [y :f32] [w :f32] [h :f32] [u :f32] [v :f32] [uw :f32] [vh :f32]
   [rgb :u32] [textured :f32] [lit :f32]]
  (vertex! x y u v rgb textured lit)
  (vertex! (+ x w) y (+ u uw) v rgb textured lit)
  (vertex! x (+ y h) u (+ v vh) rgb textured lit)
  (vertex! x (+ y h) u (+ v vh) rgb textured lit)
  (vertex! (+ x w) y (+ u uw) v rgb textured lit)
  (vertex! (+ x w) (+ y h) (+ u uw) (+ v vh) rgb textured lit))

(az/defn rect! :- :void [[x :f32] [y :f32] [w :f32] [h :f32] [rgb :u32] [lit :f32]]
  (quad! x y w h 0.0 0.0 0.0 0.0 rgb 0.0 lit))

(az/defn glyph-code!
  "Decode one UTF-8 code point into the font atlas, including French typography."
  :- :u32 [[text [:slice-const :u8]] [index [:* :usize]]]
  (let [first (az/index text (az/deref index)) ^{:var :u32} code first ^{:var :usize} extra 0]
    (set! (az/deref index) (+ (az/deref index) 1))
    (cond
      (and (>= first 194) (< first 224)) (do (set! code (ak/& first 31)) (set! extra 1))
      (and (>= first 224) (< first 240)) (do (set! code (ak/& first 15)) (set! extra 2))
      (and (>= first 240) (< first 245)) (do (set! code (ak/& first 7)) (set! extra 3))
      (>= first 128) (ak/return 63))
    (dotimes [_ extra]
      (when (>= (az/deref index) (az/field text len)) (ak/return 63))
      (let [byte (az/index text (az/deref index))]
        (when (ak/!= (ak/& byte 192) 128) (ak/return 63))
        (set! code (+ (* code 64) (ak/& byte 63)))
        (set! (az/deref index) (+ (az/deref index) 1))))
    (cond (< code 256) code
          (ak/== code 339) 128 (ak/== code 338) 129
          (ak/== code 8216) 130 (ak/== code 8217) 131
          (ak/== code 8220) 132 (ak/== code 8221) 133
          (ak/== code 8211) 134 (ak/== code 8212) 135
          (ak/== code 8230) 136 (ak/== code 8239) 137
          :else 63)))

(az/defn text!
  "UTF-8 Latin-1 plus French typography, using the loaded serif font atlas."
  :- :void [[text [:slice-const :u8]] [x :f32] [y :f32] [scale :f32] [rgb :u32]]
  (let [^{:var :usize} index 0 ^{:var :f32} cursor x]
    (ak/while (< index (az/field text len))
      (let [code (glyph-code! text (ak/& index))]
        (quad! (- cursor (* 3.0 scale)) (+ y scale) (* 62.0 scale) (* 78.0 scale)
          (+ 1.0 (ak/as :f32 (ak/floatFromInt (* (mod code 32) 64))))
          (+ 1.0 (ak/as :f32 (ak/floatFromInt (* (ak/divTrunc code 32) 80))))
          62.0 78.0 rgb 1.0 0.0)
        (set! cursor (+ cursor (* (az/index glyph-advances code) scale)))))))

(az/defn text-width :- :f32 [[text [:slice-const :u8]] [scale :f32]]
  (let [^{:var :usize} index 0 ^{:var :f32} width 0.0]
    (ak/while (< index (az/field text len))
      (let [code (glyph-code! text (ak/& index))]
        (set! width (+ width (* (az/index glyph-advances code) scale))))) width))

(az/defn revealed-prefix! :- :usize [[text [:slice-const :u8]]]
  (let [^{:var :usize} visible 0]
    (ak/while (and (< visible (az/field text len)) (> reveal-remaining 0))
      (set! _ (glyph-code! text (ak/& visible)))
      (set! reveal-remaining (- reveal-remaining 1)))
    visible))

(az/defn wrapped-text!
  "Word-wrap using actual font advances; render only the current body page."
  :- :f32 [[text [:slice-const :u8]] [x :f32] [y :f32] [width :f32] [scale :f32] [rgb :u32]]
  (let [^{:var :usize} start 0 ^{:var :f32} cursor x ^{:var :f32} row y]
    (ak/while (< start (az/field text len))
      (let [^{:var :usize} end start]
        (ak/while (and (< end (az/field text len)) (ak/!= (az/index text end) 32)) (set! end (+ end 1)))
        (let [word (az/slice text start end) word-width (text-width word scale)]
          (when (and (> cursor x) (> (+ cursor word-width) (+ x width)))
            (set! cursor x) (set! row (+ row 26.0)))
          (when (and (>= row 217.0) (< row 425.0))
            (let [visible (revealed-prefix! word)]
              (text! (az/slice word 0 visible) cursor row scale rgb)))
          (when (>= row 425.0) (set! story-more-pages true))
          (set! cursor (+ cursor word-width (* (az/index glyph-advances 32) scale))))
        (set! start (+ end 1))))
    (+ row 32.0)))

(az/defn build-frame {:attrs #{:export}} :- :u32
  [[output [:c-pointer mesh/GpuVertex]] [width :i32] [height :i32]]
  (set! _ width) (set! _ height)
  (set! vertices output) (set! vertex-count 0)
  (when (or (ak/!= reveal-parent story-parent) (ak/!= reveal-page story-page) (ak/!= reveal-story active-story))
    (set! reveal-parent story-parent) (set! reveal-page story-page) (set! reveal-story active-story)
    (set! reveal-start (glfw/glfwGetTime)) (set! reveal-all false))
  (set! reveal-remaining (if reveal-all 262144
    (ak/as :usize (ak/intFromFloat (ak/min 262144.0 (* 42.0 (ak/max 0.0 (- (glfw/glfwGetTime) reveal-start))))))))
  (rect! 0.0 0.0 1100.0 760.0 0x161b1e 0.0)
  (text! "LA PROFESSEURE" 180.0 27.0 0.60 0xe9e0c9)
  (rect! 180.0 98.0 48.0 2.0 0xbc8f5b 0.0)
  (let [data (ak/& (az/index stories active-story))
        scene (az/field (az/index (az/field data nodes) story-parent) scene)
        ^{:var :f32} row (- 217.0 (* (ak/as :f32 (ak/floatFromInt story-page)) 208.0))]
    (text! (story-text scene) 180.0 125.0 0.65 0xece3ce)
    (set! story-more-pages false)
    (dotimes [i (az/field data count)]
      (let [n (az/index (az/field data nodes) i)]
        (when (and (ak/== (az/field n parent) story-parent) (ak/== (az/field n kind) 2))
          (when (ak/!= (az/field n speaker) 0)
            (when (and (>= row 217.0) (< row 425.0))
              (text! (if (ak/== (az/field n speaker) 86) "LA VOITURE" "LA MANGUE")
                     180.0 row 0.30 0xbc8f5b))
            (set! row (+ row 26.0)))
          (set! row (wrapped-text! (story-text (ak/intCast i)) 180.0 row 740.0 0.44 0xd7d2c5))))))
  (rect! 180.0 445.0 740.0 1.0 0x414644 0.0)
  (when (and (> hovered-choice 0)
             (ak/!= (story-choice (+ hovered-choice choice-offset)) story/no-parent))
    (rect! 180.0 (+ 473.0 (* 47.0 (ak/as :f32 (ak/floatFromInt (- hovered-choice 1)))))
           740.0 36.0 0x262b2b 0.0))
  (dotimes [i 3]
    (let [choice (story-choice (+ choice-offset (ak/as :u32 (ak/intCast (+ i 1)))))
          y (+ 473.0 (* 47.0 (ak/as :f32 (ak/floatFromInt i))))
          color (if (story-choice-visited? choice) (ak/as :u32 0x969081) (ak/as :u32 0xd4b07c))]
      (when (ak/!= choice story/no-parent)
        (text! (if (ak/== i 0) "1." (if (ak/== i 1) "2." "3.")) 190.0 y 0.40 color)
        (let [label (story-text choice) scale (ak/min 0.40 (/ 700.0 (ak/max 1.0 (text-width label 1.0))))]
          (text! label 218.0 y scale color)))))
  (when (or (> choice-offset 0) (ak/!= (story-choice 4) story/no-parent))
    (text! "Haut / bas : autres choix" 180.0 617.0 0.27 0xbc8f5b))
  (text! "Espace / clic : afficher le texte     Retour arrière : revenir" 180.0 650.0 0.28 0x89918d)
  (text! "Page préc./suiv. : texte   TAB : scène" 180.0 678.0 0.27 0x89918d)
  (text! "1 - 3 Choisir     F1 Studio" 180.0 716.0 0.28 0x89918d)
  vertex-count)

(az/defn tick! :- :bool []
  (when (ak/!= (glfw/glfwWindowShouldClose window) 0) (ak/return false))
  (update!)
  (ensure-world!)
  (set! _ (flecs/ecs_progress world 0.0))
  (when (and (ak/== (glfw/glfwGetWindowAttrib window glfw/GLFW_ICONIFIED) 0)
             (ak/!= (glfw/glfwGetWindowAttrib window glfw/GLFW_VISIBLE) 0))
    (set! _ (gpu/render! (ak/& build-frame))))
  (when (ak/!= development-tick ak/null) ((az/unwrap development-tick)))
  true)

(az/defn main :- :void []
  (let [ready (initialize!)]
    (ak/defer (shutdown!))
    (when ready
      (ak/while true (when (ak/! (tick!)) (ak/break))))))
