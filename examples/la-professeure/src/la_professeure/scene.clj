(ns la-professeure.scene
  "Native stage, animation, text, 2D light and stable miniaudio resource ownership."
  (:refer-clojure :only [])
  (:require [aguafria.std]
            [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]
            [aguafria.std.mem :as mem]
            [aguafria.std.math :as math]
            [aguafria.std.debug :as debug]
            [aguafria-examples-native.bindings]
            [aguafria-examples-native.bindings.glfw :as glfw]
            [aguafria-examples-native.bindings.flecs :as flecs]
            [aguafria-examples-native.bindings.runtime :as io]
            [aguafria-examples-native.mesh :as mesh]
            [la-professeure.gpu :as gpu]
            [la-professeure.recording-tool :as story]
            [la-professeure.miniaudio :as audio]
            [la-professeure.build :as build]
            [clojure.core :as c]))

(a/defconst animation-fps :f32 8.0)

(a/defstruct Snapshot {:layout :extern}
             [[:time :f32] [:frame :u32] [:playing :bool] [:audio_ready :bool]
              [:audio_revision :u32] [:reload_failures :u32] [:rendered_frames :u64]])

(a/defvar window [:optional [:* glfw/GLFWwindow]] null)

(a/defvar elapsed :f32 0.0)

(a/defvar playing true)

(a/defvar light-enabled true)

(a/defvar engine-ready false)

(a/defvar audio-ready false)

(a/defvar audio-muted false)

(a/defvar studio-audio-suppressed :bool false)

(a/defvar audio-revision :u32 0)

(a/defvar reload-failures :u32 0)

(a/defvar rendered-frames :u64 0)

(a/defvar previous-time :f64 0.0)

(a/defvar file-check-time :f64 0.0)

(a/defvar track-hash :u64 0)

(a/defvar engine audio/ma_engine (mem/zeroes audio/ma_engine))

(a/defvar tracks [:array 2 audio/ma_sound] (mem/zeroes [:array 2 audio/ma_sound]))

(a/defvar decoders [:array 2 audio/ma_decoder] (mem/zeroes [:array 2 audio/ma_decoder]))

(a/defvar active-track :usize 0)

(a/defvar key-state [:array 4 :bool] (mem/zeroes [:array 4 :bool]))

(a/defvar ui-key-state [:array 4 :bool] (mem/zeroes [:array 4 :bool]))

(a/defvar glyph-advances [:array 256 :f32] (mem/zeroes [:array 256 :f32]))

(a/defvar ui-glyph-advances [:array 256 :f32] (mem/zeroes [:array 256 :f32]))

(a/defvar response :u32 0)

(a/defvar hovered-choice :u32 0)

(a/defvar mouse-was-down false)

(a/defvar vertices [:c-pointer mesh/GpuVertex] null)

(a/defvar vertex-count :u32 0)

(a/defconst DevelopmentView (a/type [:*const [:fn {:callconv :c} [] :void]]))

(a/defvar development-tick [:optional DevelopmentView] null)

(a/defvar development-shutdown [:optional DevelopmentView] null)

(a/defvar development-assets [:optional DevelopmentView] null)

(a/defvar development-focus [:optional DevelopmentView] null)

(a/defvar reveal-parent :u32 4294967295)

(a/defvar reveal-page :u32 4294967295)

(a/defvar reveal-story :usize 2)

(a/defvar reveal-start :f64 0.0)

(a/defvar reveal-all :bool false)

(a/defvar reveal-remaining :usize 0)

;; One native ECS world for the game and its attached development tools.
;; Parsed text remains a contiguous asset; entities hold runtime passage state.
(a/defstruct PassageState {:layout :extern}
             [[:node :u32] [:revision :u32] [:takes :u32] [:visited :bool]])

(a/defvar world [:optional [:* flecs/ecs_world_t]] null)

(a/defvar passage-component :u64 0)

(a/defvar passage-entities [:array 1024 :u64] (mem/zeroes [:array 1024 :u64]))

(a/defvar passage-entity-count :u32 0)

(a/defn ensure-world! :void []
  (when (== world null)
    (= world (flecs/ecs_init))
    (let [entity-desc (flecs/ecs_entity_desc_t {:name "DialoguePassage"})
          component-entity (flecs/ecs_entity_init world (k/& entity-desc))
          type-info (flecs/ecs_type_info_t {:size (sizeOf PassageState) :alignment (alignOf PassageState)})
          desc (flecs/ecs_component_desc_t {:entity component-entity :type type-info})]
      (= passage-component (flecs/ecs_component_init world (k/& desc))))))

(a/defn shutdown-world! :void []
  (when (!= world null) (= :_ (flecs/ecs_fini world)) (= world null))
  (= passage-entity-count 0)
  (= passage-entities (mem/zeroes [:array 1024 :u64])))

(a/defstruct Story {:layout :extern}
             [[:count :u32] [:text_length :u32] [:nodes [:array 1024 story/Node]]
              [:text [:array 262144 :u8]]])

(a/defvar stories [:array 2 Story] (mem/zeroes [:array 2 Story]))

(a/defvar active-story :usize 0)

(a/defvar story-parent :u32 0)

(a/defvar story-ready false)

(a/defvar back-was-down false)

(a/defvar story-page :u32 0)

(a/defvar story-more-pages false)

(a/defvar page-down-was-down false)

(a/defvar page-up-was-down false)

(a/defvar choice-offset :u32 0)

(a/defvar choice-down-was-down false)

(a/defvar choice-up-was-down false)

(a/defvar scene-next-was-down false)

;; Dialogue owns separate stable sound/decoder slots; replacing a voice never
;; replaces the ambience or moves a decoder still referenced by the audio thread.
(a/defvar voices [:array 2 audio/ma_sound] (mem/zeroes [:array 2 audio/ma_sound]))

(a/defvar voice-decoders [:array 2 audio/ma_decoder] (mem/zeroes [:array 2 audio/ma_decoder]))

(a/defvar voice-slot :usize 0)

(a/defvar voice-ready false)

(a/defvar voice-revision :u32 0)

(a/defvar voice-parent :u32 story/no-parent)

(a/defvar voice-node :u32 story/no-parent)

(a/defvar voice-path [:array 4096 :u8] (mem/zeroes [:array 4096 :u8]))

(a/defvar voice-hash :u64 0)

(a/defvar voice-check-time :f64 0.0)

(c/eval `(a/defconst ~'live-voices? ~(clojure.core/boolean (:reloadable? (a/configuration)))))

(a/defn stop-voice! :void []
  (when voice-ready
    (audio/ma_sound_uninit (k/& (a/get voices voice-slot)))
    (= :_ (audio/ma_decoder_uninit (k/& (a/get voice-decoders voice-slot))))
    (= voice-ready false)))

(a/defn play-voice-file! :bool
  "Render-thread only. Validate a candidate before releasing the current voice." [[path [:slice-const :u8]]]
  (when (or (! engine-ready) (== (:len path) 0)
            (>= (:len path) 4096)) (return false))
  (let [filename (k/var (as (mem/zeroes [:array 4096 :u8]) [:array 4096 :u8]))
        next (mod (+ voice-slot 1) 2)
        decoder (k/& (a/get voice-decoders next))
        candidate (k/& (a/get voices next))]
    (dotimes [i (:len path)] (when (== (a/get path i) 0) (return false)))
    (memcpy (a/slice filename 0 (:len path)) path)
    (when (!= (audio/ma_decoder_init_file (k/& filename) null decoder) audio/MA_SUCCESS)
      (return false))
    (when (!= (audio/ma_sound_init_from_data_source (k/& engine)
                                                    (as (ptrCast decoder) [:* audio/ma_data_source])
                                                    0 null candidate) audio/MA_SUCCESS)
      (= :_ (audio/ma_decoder_uninit decoder)) (return false))
    (audio/ma_sound_set_looping candidate 0)
    (audio/ma_sound_set_volume candidate (if (or audio-muted studio-audio-suppressed) 0.0 0.8))
    (when (!= (audio/ma_sound_start candidate) audio/MA_SUCCESS)
      (audio/ma_sound_uninit candidate) (= :_ (audio/ma_decoder_uninit decoder)) (return false))
    (stop-voice!) (= voice-slot next) (= voice-ready true)
    (= voice-revision (+ voice-revision 1)) true))

(a/defn current-voice-hash :u64 []
  (let [file (io/fopen (k/& voice-path) "rb")]
    (when (== file null) (return 0))
    (defer (= :_ (io/fclose file)))
    (let [buffer (k/var (as undefined [:array 4096 :u8]))
          hash (k/var (u64 14695981039346656037))]
      (while true
        (let [n (io/fread (k/& buffer) 1 4096 file)]
          (when (== n 0) (break))
          (dotimes [i n] (= hash (*% (bit-xor hash (a/get buffer i)) 1099511628211)))))
      hash)))

(a/defn load-node-voice! :bool [[index :u32]]
  (when (>= index (:count (a/get stories active-story))) (return false))
  (let [node (a/get (:nodes (a/get stories active-story)) index)
        length (:id_len node)]
    (when (or (== length 0) (> length 64)) (return false))
    (dotimes [i length]
      (let [c (a/get (:id node) i)]
        (when (! (or (and (>= c 65) (<= c 90)) (and (>= c 97) (<= c 122))
                     (and (>= c 48) (<= c 57)) (== c 45) (== c 95))) (return false))))
    (= voice-path (mem/zeroes [:array 4096 :u8]))
    (memcpy (a/slice voice-path 0 17) "resources/voices/")
    (memcpy (a/slice voice-path 17 (+ 17 length)) (a/slice (:id node) 0 length))
    (memcpy (a/slice voice-path (+ 17 length) (+ 21 length)) ".wav")
    (= voice-node index) (= voice-hash (current-voice-hash))
    (play-voice-file! (a/slice voice-path 0 (+ 21 length)))))

(a/defn update-voice! :void []
  (let [data (k/& (a/get stories active-story))
        changed (!= voice-parent story-parent)]
    (when changed
      (stop-voice!) (= voice-parent story-parent) (= voice-node story/no-parent))
    ;; Advance through voiced passages in their Markdown order. Missing takes
    ;; are silent, never substituted with unrelated audio.
    (when (or changed (and voice-ready (!= (audio/ma_sound_at_end (k/& (a/get voices voice-slot))) 0)))
      (let [start (if (== voice-node story/no-parent) (as 0 :u32) (+ voice-node 1))]
        (dotimes [i (:count data)]
          (let [n (a/get (:nodes data) i)]
            (when (and (>= i start) (== (:parent n) story-parent)
                       (== (:kind n) 2) (> (:id_len n) 0))
              (when (load-node-voice! (intCast i)) (return)))))))
    (when (and live-voices? (!= voice-node story/no-parent)
               (>= (- (glfw/glfwGetTime) voice-check-time) 0.5))
      (= voice-check-time (glfw/glfwGetTime))
      (when (!= voice-hash (current-voice-hash)) (= :_ (load-node-voice! voice-node))))))

(a/defn passage-count :i32 []
  (if (== world null) 0 (flecs/ecs_count_id world passage-component)))

(a/defn sync-passages! :void []
  (ensure-world!)
  (let [data (k/& (a/get stories active-story))
        previous-entities passage-entities
        previous-count passage-entity-count]
    (dotimes [i (:count data)]
      (let [node (a/get (:nodes data) i)
            name (k/var (as (mem/zeroes [:array 65 :u8]) [:array 65 :u8]))]
        (memcpy (a/slice name 0 (:id_len node)) (a/slice (:id node) 0 (:id_len node)))
        (let [desc (flecs/ecs_entity_desc_t {:name (if (> (:id_len node) 0) (k/& name) null)})
              entity (flecs/ecs_entity_init world (k/& desc))
              old (flecs/ecs_get_id world entity passage-component)
              state (PassageState {:node (intCast i) :revision (:revision node)
                                   :takes (if (!= old null) (:takes (a/cast old [:*const PassageState])) 0)
                                   :visited (if (!= old null) (:visited (a/cast old [:*const PassageState])) false)})]
          (= (a/get passage-entities i) entity)
          (= :_ (flecs/ecs_set_id world entity passage-component (sizeOf PassageState) (k/& state))))))
    (= passage-entity-count (:count data))
    (dotimes [i previous-count]
      (let [entity (a/get previous-entities i) retained (k/var false)]
        (dotimes [j passage-entity-count]
          (when (== entity (a/get passage-entities j)) (= retained true)))
        (when (! retained) (flecs/ecs_delete world entity))))))

(a/defn reload-story! :bool
  "Validate a complete compiled Markdown asset before changing the visible dialogue." []
  (let [file (io/fopen "resources/demo/story.lpdialogue" "rb")]
    (when (== file null) (return false))
    (defer (= :_ (io/fclose file)))
    (let [magic (k/var (as undefined [:array 8 :u8]))
          slot (mod (+ active-story 1) 2)
          candidate (k/& (a/get stories slot))]
      (when (or (!= (io/fread (k/& magic) 1 8 file) 8)
                (! (mem/eql :u8 (k/& magic) "LPDIAG01"))) (return false))
      (when (or (!= (io/fread (k/& (:count candidate)) 4 1 file) 1)
                (!= (io/fread (k/& (:text_length candidate)) 4 1 file) 1)
                (== (:count candidate) 0) (> (:count candidate) 1024)
                (> (:text_length candidate) 262144)) (return false))
      (when (or (!= (io/fread (k/& (:nodes candidate)) (sizeOf story/Node)
                              (:count candidate) file) (:count candidate))
                (!= (io/fread (k/& (:text candidate)) 1 (:text_length candidate) file)
                    (:text_length candidate))) (return false))
      (dotimes [i (:count candidate)]
        (let [n (a/get (:nodes candidate) i)]
          (when (or (> (:kind n) 2) (> (:scene n) i)
                    (and (!= (:parent n) story/no-parent) (>= (:parent n) i))
                    (> (:offset n) (:text_length candidate))
                    (> (:length n) (- (:text_length candidate) (:offset n))))
            (return false))))
      (when (!= (:kind (a/get (:nodes candidate) 0)) 0) (return false))
      (= active-story slot) (= story-parent 0) (= story-page 0) (= choice-offset 0) (= response 0)
      (= voice-parent story/no-parent)
      (= story-ready true) (sync-passages!) true)))

(a/defn story-text [:slice-const :u8] [[index :u32]]
  (let [data (k/& (a/get stories active-story))]
    (when (>= index (:count data)) (return ""))
    (let [node (a/get (:nodes data) index)]
      (a/slice (:text data) (:offset node)
               (+ (:offset node) (:length node))))))

(a/defn story-choice-parent :u32 []
  ;; Keep the leaf's response visible while returning to its enclosing choices.
  ;; Climb only authored parents; never cross into another scene.
  (let [data (k/& (a/get stories active-story)) parent (k/var (u32 story-parent))]
    (dotimes [_ (:count data)]
      (when (or (== parent story/no-parent) (>= parent (:count data)))
        (return story/no-parent))
      (dotimes [i (:count data)]
        (let [n (a/get (:nodes data) i)]
          (when (and (== (:parent n) parent) (== (:kind n) 1))
            (return parent))))
      (= parent (:parent (a/get (:nodes data) parent))))
    story/no-parent))

(a/defn story-choice-visited? :bool [[index :u32]]
  (when (or (== world null) (>= index passage-entity-count)) (return false))
  (let [state (flecs/ecs_get_id world (a/get passage-entities index) passage-component)]
    (and (!= state null) (:visited (a/cast state [:*const PassageState])))))

(a/defn story-choice :u32 [[ordinal :u32]]
  (let [data (k/& (a/get stories active-story)) parent (story-choice-parent) found (k/var (u32 0))]
    (when (== parent story/no-parent) (return story/no-parent))
    (dotimes [i (:count data)]
      (let [n (a/get (:nodes data) i)]
        (when (and (== (:parent n) parent) (== (:kind n) 1))
          (= found (+ found 1))
          (when (== found ordinal) (return (intCast i))))))
    story/no-parent))

(a/defn choose-story! :bool [[ordinal :u32]]
  (let [next (story-choice ordinal)]
    (when (== next story/no-parent) (return false))
    (let [state (a/cast (flecs/ecs_get_mut_id world (a/get passage-entities next) passage-component) [:* PassageState])]
      (= (:visited state) true))
    (= story-parent next) (= story-page 0) (= choice-offset 0)
    (= reveal-parent story/no-parent) (= voice-parent story/no-parent) true))

(a/defn page-choices! :bool [[forward :bool]]
  (if forward
    (do (when (== (story-choice (+ choice-offset 4)) story/no-parent) (return false))
        (= choice-offset (+ choice-offset 3)))
    (do (when (== choice-offset 0) (return false))
        (= choice-offset (- choice-offset 3))))
  true)

(a/defn next-scene! :void []
  (let [data (k/& (a/get stories active-story))
        current (:scene (a/get (:nodes data) story-parent))]
    (dotimes [step (:count data)]
      (let [index (mod (+ current (as (intCast step) :u32) 1) (:count data))]
        (when (== (:kind (a/get (:nodes data) index)) 0)
          (= story-parent index) (= story-page 0) (= choice-offset 0) (return))))))

(a/defn animation-frame :u32 [[seconds :f32] [fps :f32]]
  (intFromFloat (mod (floor (* (max seconds 0.0) (max fps 0.0))) 8.0)))

(a/defn bob-height :f32
  "Edit this declaration while the native window runs." []
  (* 8.0 (math/sin (* elapsed 2.0))))

(a/defn snapshot Snapshot []
  (Snapshot {:time elapsed :frame (animation-frame elapsed animation-fps)
             :playing playing :audio_ready audio-ready :audio_revision audio-revision
             :reload_failures reload-failures :rendered_frames rendered-frames}))

(a/defn file-hash :u64 []
  (let [file (io/fopen "resources/demo/lesson.wav" "rb")]
    (when (== file null) (return 0))
    (defer (= :_ (io/fclose file)))
    (let [buffer (k/var (as undefined [:array 4096 :u8]))
          hash (k/var (u64 14695981039346656037))]
      (while true
        (let [n (io/fread (k/& buffer) 1 4096 file)]
          (when (== n 0) (break))
          (dotimes [i n]
            (= hash (*% (bit-xor hash (a/get buffer i)) 1099511628211)))))
      hash)))

(a/defn stop-background! :void []
  (when audio-ready
    (audio/ma_sound_uninit (k/& (a/get tracks active-track)))
    (= :_ (audio/ma_decoder_uninit (k/& (a/get decoders active-track))))
    (= audio-ready false)))

(a/defconst background-music-enabled false)

(a/defn reload-track! :bool
  "Render-thread only: initialize in a stable alternate slot before releasing old audio." []
  (when (! background-music-enabled) (stop-background!) (return false))
  (when (! engine-ready) (return false))
  (let [next (mod (+ active-track 1) 2)
        candidate (k/& (a/get tracks next))
        decoder (k/& (a/get decoders next))
        result (audio/ma_decoder_init_file "resources/demo/lesson.wav" null decoder)]
    (= track-hash (file-hash))
    (if (== result audio/MA_SUCCESS)
      (do
        ;; Own a fresh decoder, bypassing miniaudio's pathname resource cache.
        (when (!= (audio/ma_sound_init_from_data_source (k/& engine)
                                                        (as (ptrCast decoder) [:* audio/ma_data_source])
                                                        0 null candidate)
                  audio/MA_SUCCESS)
          (= :_ (audio/ma_decoder_uninit decoder))
          (= reload-failures (+ reload-failures 1))
          (return false))
        (audio/ma_sound_set_looping candidate 1)
        (audio/ma_sound_set_volume candidate (if (or audio-muted studio-audio-suppressed) 0.0 0.35))
        (if (== (audio/ma_sound_start candidate) audio/MA_SUCCESS)
          (do
            (when audio-ready
              (audio/ma_sound_uninit (k/& (a/get tracks active-track)))
              (= :_ (audio/ma_decoder_uninit (k/& (a/get decoders active-track)))))
            (= active-track next)
            (= audio-ready true)
            (= audio-revision (+ audio-revision 1))
            true)
          (do (audio/ma_sound_uninit candidate)
              (= :_ (audio/ma_decoder_uninit decoder))
              (= reload-failures (+ reload-failures 1)) false)))
      (do (= reload-failures (+ reload-failures 1)) false))))

(a/defn key-pressed? :bool [[key :i32] [slot :usize]]
  (let [down (== (glfw/glfwGetKey window key) glfw/GLFW_PRESS)
        previous (if (< slot 4) (a/get key-state slot) (a/get ui-key-state (- slot 4)))
        pressed (and down (! previous))]
    (if (< slot 4) (= (a/get key-state slot) down)
        (= (a/get ui-key-state (- slot 4)) down))
    pressed))

(a/defn reload-visuals! :void
  "Frame-boundary asset publication, after the worker has packed a complete atlas." []
  (gpu/renderer-wait-idle!)
  (gpu/load-atlas!)
  (when (!= development-assets null) ((a/unwrap development-assets)))
  (let [file (io/fopen "resources/demo/glyph-advances.bin" "rb")]
    (when (== file null) (debug/panic "Missing glyph metrics; run :prepare" []))
    (defer (= :_ (io/fclose file)))
    (when (!= (io/fread (k/& glyph-advances) 4 256 file) 256)
      (debug/panic "Invalid glyph metrics" [])))
  (let [file (io/fopen "resources/demo/ui-glyph-advances.bin" "rb")]
    (when (== file null) (debug/panic "Missing UI glyph metrics; run :prepare" []))
    (defer (= :_ (io/fclose file)))
    (when (!= (io/fread (k/& ui-glyph-advances) 4 256 file) 256)
      (debug/panic "Invalid UI glyph metrics" []))))

(a/defn retain-key-presses! :void
  "Keep a short press until the game polls it; never change Studio's input mode." []
  (when (!= window null)
    (glfw/glfwSetInputMode window glfw/GLFW_STICKY_KEYS glfw/GLFW_TRUE)))

(a/defn initialize! :bool []
  ;; Use the linked loader, including in a JVM without a dylib search-path override.
  (glfw/glfwInitVulkanLoader glfw/vkGetInstanceProcAddr)
  (when (!= (glfw/glfwInit) glfw/GLFW_TRUE) (return false))
  (glfw/glfwWindowHint glfw/GLFW_CLIENT_API glfw/GLFW_NO_API)
  (glfw/glfwWindowHint glfw/GLFW_RESIZABLE glfw/GLFW_FALSE)
  (= window (glfw/glfwCreateWindow 1100 760 "La Professeure | Vulkan playground" null null))
  (when (== window null) (return false))
  (retain-key-presses!)
  (when (! (gpu/initialize-renderer! window)) (return false))
  (reload-visuals!)
  (when (! (reload-story!)) (debug/panic "Missing/invalid dialogue asset; run :prepare" []))
  (= engine-ready (== (audio/ma_engine_init null (k/& engine)) audio/MA_SUCCESS))
  (= :_ (reload-track!))
  (= previous-time (glfw/glfwGetTime))
  true)

(a/defn shutdown! :void []
  (when (!= development-shutdown null) ((a/unwrap development-shutdown)))
  (shutdown-world!)
  (stop-voice!)
  (when audio-ready
    (audio/ma_sound_uninit (k/& (a/get tracks active-track)))
    (= :_ (audio/ma_decoder_uninit (k/& (a/get decoders active-track))))
    (= audio-ready false))
  (when engine-ready (audio/ma_engine_uninit (k/& engine)) (= engine-ready false))
  (gpu/shutdown-renderer!)
  (when (!= window null) (glfw/glfwDestroyWindow window) (= window null))
  (glfw/glfwTerminate))

(a/defn update! :void []
  (glfw/glfwPollEvents)
  (let [back (== (glfw/glfwGetKey window glfw/GLFW_KEY_BACKSPACE) glfw/GLFW_PRESS)]
    (when (and back (! back-was-down))
      (let [parent (:parent (a/get (:nodes (a/get stories active-story)) story-parent))]
        (when (!= parent story/no-parent)
          (= story-parent parent) (= story-page 0) (= choice-offset 0))))
    (= back-was-down back))
  (let [down (== (glfw/glfwGetKey window glfw/GLFW_KEY_PAGE_DOWN) glfw/GLFW_PRESS)
        up (== (glfw/glfwGetKey window glfw/GLFW_KEY_PAGE_UP) glfw/GLFW_PRESS)]
    (when (and down (! page-down-was-down) story-more-pages) (= story-page (+ story-page 1)))
    (when (and up (! page-up-was-down) (> story-page 0)) (= story-page (- story-page 1)))
    (= page-down-was-down down) (= page-up-was-down up))
  (let [down (== (glfw/glfwGetKey window glfw/GLFW_KEY_DOWN) glfw/GLFW_PRESS)
        up (== (glfw/glfwGetKey window glfw/GLFW_KEY_UP) glfw/GLFW_PRESS)
        next (== (glfw/glfwGetKey window glfw/GLFW_KEY_TAB) glfw/GLFW_PRESS)]
    (when (and down (! choice-down-was-down)) (= :_ (page-choices! true)))
    (when (and up (! choice-up-was-down)) (= :_ (page-choices! false)))
    (when (and next (! scene-next-was-down)) (next-scene!))
    (= choice-down-was-down down) (= choice-up-was-down up) (= scene-next-was-down next))
  (when (key-pressed? glfw/GLFW_KEY_1 4) (= response 1))
  (when (key-pressed? glfw/GLFW_KEY_2 5) (= response 2))
  (when (key-pressed? glfw/GLFW_KEY_3 6) (= response 3))
  (when (and (key-pressed? glfw/GLFW_KEY_F1 7) (!= development-focus null))
    ((a/unwrap development-focus)))
  (when (key-pressed? glfw/GLFW_KEY_SPACE 0) (= reveal-all true))
  (when (key-pressed? glfw/GLFW_KEY_L 1) (= light-enabled (! light-enabled)))
  (when (key-pressed? glfw/GLFW_KEY_M 2)
    (= audio-muted (! audio-muted))
    (when voice-ready
      (audio/ma_sound_set_volume (k/& (a/get voices voice-slot)) (if (or audio-muted studio-audio-suppressed) 0.0 0.8)))
    (when audio-ready
      (audio/ma_sound_set_volume (k/& (a/get tracks active-track)) (if (or audio-muted studio-audio-suppressed) 0.0 0.35))))
  (let [now (glfw/glfwGetTime)
        x (k/var (f64 0.0)) y (k/var (f64 0.0))]
    (when playing (= elapsed (+ elapsed (as (floatCast (min (- now previous-time) 0.1)) :f32))))
    (= previous-time now)
    (glfw/glfwGetCursorPos window (k/& x) (k/& y))
    (= hovered-choice 0)
    (when (and (>= x 180.0) (<= x 920.0))
      (when (and (>= y 473.0) (< y 509.0)) (= hovered-choice 1))
      (when (and (>= y 520.0) (< y 556.0)) (= hovered-choice 2))
      (when (and (>= y 567.0) (< y 603.0)) (= hovered-choice 3)))
    (let [down (== (glfw/glfwGetMouseButton window glfw/GLFW_MOUSE_BUTTON_LEFT) glfw/GLFW_PRESS)]
      (when (and down (! mouse-was-down))
        (if (> hovered-choice 0) (= response hovered-choice) (= reveal-all true)))
      (= mouse-was-down down))
    (= gpu/light-x (floatCast (/ x 1100.0)))
    (= gpu/light-y (floatCast (/ y 760.0)))
    (= gpu/lighting (if light-enabled 1.0 0.0))
    (when (or (key-pressed? glfw/GLFW_KEY_R 3) (>= (- now file-check-time) 1.0))
      (= file-check-time now)
      (when (and background-music-enabled (!= track-hash (file-hash))) (= :_ (reload-track!)))))
  (when (> response 0) (= :_ (choose-story! (+ response choice-offset))) (= response 0))
  (update-voice!)
  (= rendered-frames (+ rendered-frames 1)))

;; Render-thread canvas dimensions, restored by each independent window after
;; building its frame. Text positions stay in logical screen points on Retina.
(a/defvar canvas-width :f32 1100.0)

(a/defvar canvas-height :f32 760.0)

(a/defn vertex! :void
  [[x :f32] [y :f32] [u :f32] [v :f32] [rgb :u32] [textured :f32] [lit :f32]]
  (when (>= vertex-count gpu/frame-capacity) (debug/panic "La Professeure frame capacity exceeded" []))
  (= (a/get vertices vertex-count)
     (mesh/GpuVertex {:x (- (/ (* x 2.0) canvas-width) 1.0) :y (- (/ (* y 2.0) canvas-height) 1.0) :z 0.0
                      :r (/ (as (floatFromInt (k/& (>> rgb 16) 255)) :f32) 255.0)
                      :g (/ (as (floatFromInt (k/& (>> rgb 8) 255)) :f32) 255.0)
                      :b (/ (as (floatFromInt (k/& rgb 255)) :f32) 255.0)
                      :nx 0.0 :ny 0.0 :nz lit :wx u :wy v :wz 0.0
                      :roughness textured :vx 0.0 :vy 0.0 :vz 0.0}))
  (= vertex-count (+ vertex-count 1)))

(a/defn quad! :void
  [[x :f32] [y :f32] [w :f32] [h :f32] [u :f32] [v :f32] [uw :f32] [vh :f32]
   [rgb :u32] [textured :f32] [lit :f32]]
  (vertex! x y u v rgb textured lit)
  (vertex! (+ x w) y (+ u uw) v rgb textured lit)
  (vertex! x (+ y h) u (+ v vh) rgb textured lit)
  (vertex! x (+ y h) u (+ v vh) rgb textured lit)
  (vertex! (+ x w) y (+ u uw) v rgb textured lit)
  (vertex! (+ x w) (+ y h) (+ u uw) (+ v vh) rgb textured lit))

(a/defn rect! :void [[x :f32] [y :f32] [w :f32] [h :f32] [rgb :u32] [lit :f32]]
  (quad! x y w h 0.0 0.0 0.0 0.0 rgb 0.0 lit))

(a/defn glyph-code! :u32
  "Decode one UTF-8 code point into the font atlas, including French typography." [[text [:slice-const :u8]] [index [:* :usize]]]
  (let [first (a/get text (a/deref index)) code (k/var (u32 first)) extra (k/var (usize 0))]
    (= (a/deref index) (+ (a/deref index) 1))
    (cond
      (and (>= first 194) (< first 224)) (do (= code (k/& first 31)) (= extra 1))
      (and (>= first 224) (< first 240)) (do (= code (k/& first 15)) (= extra 2))
      (and (>= first 240) (< first 245)) (do (= code (k/& first 7)) (= extra 3))
      (>= first 128) (return 63))
    (dotimes [_ extra]
      (when (>= (a/deref index) (:len text)) (return 63))
      (let [byte (a/get text (a/deref index))]
        (when (!= (k/& byte 192) 128) (return 63))
        (= code (+ (* code 64) (k/& byte 63)))
        (= (a/deref index) (+ (a/deref index) 1))))
    (cond (< code 256) code
          (== code 339) 128 (== code 338) 129
          (== code 8216) 130 (== code 8217) 131
          (== code 8220) 132 (== code 8221) 133
          (== code 8211) 134 (== code 8212) 135
          (== code 8230) 136 (== code 8239) 137
          :else 63)))

(a/defn text! :void
  "UTF-8 Latin-1 plus French typography, using the loaded serif font atlas." [[text [:slice-const :u8]] [x :f32] [y :f32] [scale :f32] [rgb :u32]]
  (let [index (k/var (usize 0)) cursor (k/var (f32 x))]
    (while (< index (:len text))
      (let [code (glyph-code! text (k/& index))]
        (quad! (- cursor (* 3.0 scale)) (+ y scale) (* 62.0 scale) (* 78.0 scale)
               (+ 1.0 (as (floatFromInt (* (mod code 32) 64)) :f32))
               (+ 1.0 (as (floatFromInt (* (divTrunc code 32) 80)) :f32))
               62.0 78.0 rgb 1.0 0.0)
        (= cursor (+ cursor (* (a/get glyph-advances code) scale)))))))

(a/defn text-width :f32 [[text [:slice-const :u8]] [scale :f32]]
  (let [index (k/var (usize 0)) width (k/var (f32 0.0))]
    (while (< index (:len text))
      (let [code (glyph-code! text (k/& index))]
        (= width (+ width (* (a/get glyph-advances code) scale))))) width))

(a/defn revealed-prefix! :usize [[text [:slice-const :u8]]]
  (let [visible (k/var (usize 0))]
    (while (and (< visible (:len text)) (> reveal-remaining 0))
      (= :_ (glyph-code! text (k/& visible)))
      (= reveal-remaining (- reveal-remaining 1)))
    visible))

(a/defn wrapped-text! :f32
  "Word-wrap using actual font advances; render only the current body page." [[text [:slice-const :u8]] [x :f32] [y :f32] [width :f32] [scale :f32] [rgb :u32]]
  (let [start (k/var (usize 0)) cursor (k/var (f32 x)) row (k/var (f32 y))]
    (while (< start (:len text))
      (let [end (k/var (usize start))]
        (while (and (< end (:len text)) (!= (a/get text end) 32)) (= end (+ end 1)))
        (let [word (a/slice text start end) word-width (text-width word scale)]
          (when (and (> cursor x) (> (+ cursor word-width) (+ x width)))
            (= cursor x) (= row (+ row 26.0)))
          (when (and (>= row 217.0) (< row 425.0))
            (let [visible (revealed-prefix! word)]
              (text! (a/slice word 0 visible) cursor row scale rgb)))
          (when (>= row 425.0) (= story-more-pages true))
          (= cursor (+ cursor word-width (* (a/get glyph-advances 32) scale))))
        (= start (+ end 1))))
    (+ row 32.0)))

(a/defn build-frame :u32 {:callconv :c}
  [[output [:c-pointer mesh/GpuVertex]] [width :i32] [height :i32]]
  (= :_ width) (= :_ height)
  (= vertices output) (= vertex-count 0)
  (when (or (!= reveal-parent story-parent) (!= reveal-page story-page) (!= reveal-story active-story))
    (= reveal-parent story-parent) (= reveal-page story-page) (= reveal-story active-story)
    (= reveal-start (glfw/glfwGetTime)) (= reveal-all false))
  (= reveal-remaining (if reveal-all 262144
                          (as (intFromFloat (min 262144.0 (* 42.0 (max 0.0 (- (glfw/glfwGetTime) reveal-start))))) :usize)))
  (rect! 0.0 0.0 1100.0 760.0 0x161b1e 0.0)
  (text! "LA PROFESSEURE" 180.0 27.0 0.60 0xe9e0c9)
  (rect! 180.0 98.0 48.0 2.0 0xbc8f5b 0.0)
  (let [data (k/& (a/get stories active-story))
        scene (:scene (a/get (:nodes data) story-parent))
        row (k/var (f32 (- 217.0 (* (as (floatFromInt story-page) :f32) 208.0))))]
    (text! (story-text scene) 180.0 125.0 0.65 0xece3ce)
    (= story-more-pages false)
    (dotimes [i (:count data)]
      (let [n (a/get (:nodes data) i)]
        (when (and (== (:parent n) story-parent) (== (:kind n) 2))
          (when (!= (:speaker n) 0)
            (when (and (>= row 217.0) (< row 425.0))
              (text! (if (== (:speaker n) 86) "LA VOITURE" "LA MANGUE")
                     180.0 row 0.30 0xbc8f5b))
            (= row (+ row 26.0)))
          (= row (wrapped-text! (story-text (intCast i)) 180.0 row 740.0 0.44 0xd7d2c5))))))
  (rect! 180.0 445.0 740.0 1.0 0x414644 0.0)
  (when (and (> hovered-choice 0)
             (!= (story-choice (+ hovered-choice choice-offset)) story/no-parent))
    (rect! 180.0 (+ 473.0 (* 47.0 (as (floatFromInt (- hovered-choice 1)) :f32)))
           740.0 36.0 0x262b2b 0.0))
  (dotimes [i 3]
    (let [choice (story-choice (+ choice-offset (as (intCast (+ i 1)) :u32)))
          y (+ 473.0 (* 47.0 (as (floatFromInt i) :f32)))
          color (if (story-choice-visited? choice) (as 0x969081 :u32) (as 0xd4b07c :u32))]
      (when (!= choice story/no-parent)
        (text! (if (== i 0) "1." (if (== i 1) "2." "3.")) 190.0 y 0.40 color)
        (let [label (story-text choice) scale (min 0.40 (/ 700.0 (max 1.0 (text-width label 1.0))))]
          (text! label 218.0 y scale color)))))
  (when (or (> choice-offset 0) (!= (story-choice 4) story/no-parent))
    (text! "Haut / bas : autres choix" 180.0 617.0 0.27 0xbc8f5b))
  (text! "Espace / clic : afficher le texte     Retour arrière : revenir" 180.0 650.0 0.28 0x89918d)
  (text! "Page préc./suiv. : texte   TAB : scène" 180.0 678.0 0.27 0x89918d)
  (text! "1 - 3 Choisir     F1 Studio" 180.0 716.0 0.28 0x89918d)
  vertex-count)

(a/defn tick! :bool []
  (when (!= (glfw/glfwWindowShouldClose window) 0) (return false))
  (update!)
  (ensure-world!)
  (= :_ (flecs/ecs_progress world 0.0))
  (when (and (== (glfw/glfwGetWindowAttrib window glfw/GLFW_ICONIFIED) 0)
             (!= (glfw/glfwGetWindowAttrib window glfw/GLFW_VISIBLE) 0))
    (= :_ (gpu/render! (k/& build-frame))))
  (when (!= development-tick null) ((a/unwrap development-tick)))
  true)

(a/defn main :void []
  (let [ready (initialize!)]
    (defer (shutdown!))
    (when ready
      (while true (when (! (tick!)) (break))))))
