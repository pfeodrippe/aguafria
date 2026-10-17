(ns racing-game.inference
  "Narrow native Granite runtime: model ownership, strict GGUF parsing, and kernels."
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as ak :refer :all]
            [aguafria.std]
            [aguafria.std.crypto.hash.sha2 :as std-sha2]
            [aguafria.std.mem :as std-mem]
            [aguafria.std.math :as std-math]
            [aguafria.std.posix :as std-posix]
            [aguafria.zig :as a]
            [aguafria-examples-native.bindings]
            [aguafria-examples-native.bindings.runtime :as runtime]
            [racing-game.protocol :as protocol]
            [clojure.core :as c]))

(c/declare free-sequences! find-tensor tensor-rms-norm! tensor-matvec!
           tensor-element silu embedding-value-kernel empty-forward-report)

(a/defconst gguf-magic :u32 0x46554747)

(a/defconst gguf-max-version :u32 3)

(a/defconst gguf-max-tensors :u64 4096)

(a/defconst gguf-max-metadata :u64 65536)

(a/defconst gguf-max-array-elements :u64 4000000)

(a/defconst parser-ok :u32 0)

(a/defconst parser-truncated :u32 1)

(a/defconst parser-bad-magic :u32 2)

(a/defconst parser-bad-version :u32 3)

(a/defconst parser-limit :u32 4)

(a/defconst parser-unsupported-type :u32 5)

(a/defconst model-file-not-found :u32 100)

(a/defconst model-file-empty :u32 101)

(a/defconst model-allocation-failed :u32 102)

(a/defconst model-file-read-failed :u32 103)

(a/defconst model-profile-unsupported :u32 104)

(a/defconst model-storage-none :u8 0)

(a/defconst model-storage-owned :u8 1)

(a/defconst model-storage-mapped :u8 2)

(a/defconst tensor-not-found :usize 4096)

(a/defconst metadata-not-found :usize 128)

(a/defconst tokenizer-capacity :usize 160)

(a/defconst tokenizer-vocabulary-capacity :usize 100352)

(a/defconst tokenizer-hash-capacity :usize 262144)

(a/defconst tokenizer-empty-id :u32 0xffffffff)

(a/defconst tokenizer-empty-pair :u64 0xffffffffffffffff)

(a/defconst model-profile-none :u8 0)

(a/defconst model-profile-granite-h-350m :u8 1)

(a/defconst model-profile-granite-h-1b :u8 2)

(a/defconst model-profile-granite-h-micro :u8 3)

(a/defconst model-max-hidden-size :usize 2048)

(a/defconst model-max-ffn-size :usize 8192)

(a/defconst model-max-layer-count :usize 40)

(a/defconst model-max-mamba-inner-size :usize 4096)

(a/defconst model-max-mamba-projection-size :usize 8512)

(a/defconst model-max-mamba-conv-size :usize 4352)

(a/defconst model-max-mamba-head-count :usize 64)

(a/defconst model-max-attention-kv-size :usize 512)

(a/defvar model-profile-id :u8 model-profile-none)

(a/defvar model-hidden-size :usize 768)

(a/defconst model-vocabulary-size :usize 100352)

(a/defvar model-ffn-size :usize 2048)

(a/defvar model-layer-count :usize 32)

(a/defvar model-mamba-layer-count :usize 28)

(a/defvar model-mamba-inner-size :usize 1536)

(a/defvar model-mamba-conv-size :usize 1792)

(a/defvar model-mamba-projection-size :usize 3376)

(a/defvar model-mamba-head-count :usize 48)

(a/defvar model-mamba-head-size :usize 32)

(a/defvar model-mamba-state-size :usize 128)

(a/defvar model-mamba-recurrent-size :usize 196608)

(a/defvar model-mamba-conv-state-size :usize 5376)

(a/defvar model-attention-layer-count :usize 4)

(a/defvar model-attention-head-count :usize 12)

(a/defvar model-attention-kv-head-count :usize 4)

(a/defvar model-attention-head-size :usize 64)

(a/defvar model-attention-kv-size :usize 256)

(a/defvar model-attention-scale :f32 0.015625)

(a/defconst sequence-capacity :usize 160)

(a/defconst sequence-racer-count :usize protocol/actor-count)

(a/defvar sequence-mamba-floats :usize 66060288)

(a/defvar sequence-conv-floats :usize 1806336)

(a/defvar sequence-kv-floats :usize 1966080)

(a/defvar sequence-total-floats :usize 71798784)

(a/defvar sequence-total-bytes :usize 287195136)

(a/defvar model-residual-multiplier :f32 0.246)

(a/defconst model-rms-epsilon :f32 0.00001)

(a/defconst action-head-magic :u32 0x48415241)

(a/defconst action-head-version :u32 1)

(a/defconst action-head-output-count :usize 8)

(a/defconst action-head-token-count :usize 8)

(a/defconst action-head-max-input-count :usize 16384)

(a/defconst action-head-max-weight-count :usize 131072)

(a/defvar action-head-input-count :usize 6144)

(a/defvar action-head-weight-count :usize 49152)

(a/defconst action-head-header-bytes :usize 32)

(a/defconst team-head-magic :u32 0x4d414554)

(a/defconst team-head-output-count :usize 3)

(a/defconst team-head-max-weight-count :usize 49152)

(a/defvar team-head-weight-count :usize 18432)

(a/defstruct Reader
  {:layout :extern}
  [[:bytes [:c-pointer :u8]]
   [:length :usize]
   [:cursor :usize]
   [:error_code :u32]])

(a/defstruct StringView
  {:layout :extern}
  [[:start :usize]
   [:length :usize]])

(a/defstruct TensorInfo
  "Zero-copy descriptor whose name and tensor data remain inside model-bytes."
  {:layout :extern}
  [[:name_start :usize]
   [:name_length :usize]
   [:dimension_count :u8]
   [:dimensions [:array 4 :u64]]
   [:ggml_type :u32]
   [:relative_offset :u64]
   [:data_address :usize]])

(a/defstruct MetadataInfo
  "Zero-copy descriptor for one GGUF metadata value or array."
  {:layout :extern}
  [[:key_start :usize]
   [:key_length :usize]
   [:value_type :u32]
   [:element_type :u32]
   [:value_start :usize]
   [:element_count :u64]])

(a/defstruct TokenizationReport
  "Bounded exact byte-token encoding used by the first compact race prompts."
  {:layout :extern}
  [[:valid :bool]
   [:truncated :bool]
   [:byte_count :u16]
   [:token_count :u16]
   [:unsupported_index :u16]
   [:reserved :u16]
   [:tokens [:array 160 :u32]]])

(a/defstruct TokenizerMerge
  {:layout :extern}
  [[:found :bool]
   [:rank :u32]
   [:token :u32]])

(a/defstruct TokenizerSummary
  "Inspectable native vocabulary and BPE index built directly from GGUF."
  {:layout :extern}
  [[:valid :bool]
   [:token_count :u32]
   [:merge_count :u32]
   [:vocabulary_slots :u32]
   [:merge_slots :u32]])

(a/defstruct ModelProfileSummary
  "Runtime dimensions selected from a supported Granite GGUF. The same native
  kernels execute every profile; only validated tensor dimensions and state
  sizes differ."
  {:layout :extern}
  [[:valid :bool]
   [:profile :u8]
   [:hidden_size :u16]
   [:ffn_size :u16]
   [:layer_count :u8]
   [:mamba_layer_count :u8]
   [:mamba_inner_size :u16]
   [:mamba_projection_size :u16]
   [:mamba_conv_size :u16]
   [:mamba_head_count :u8]
   [:mamba_head_size :u8]
   [:attention_layer_count :u8]
   [:attention_head_count :u8]
   [:attention_kv_head_count :u8]
   [:attention_head_size :u16]
   [:sequence_state_bytes :usize]])

(a/defstruct GgufSummary
  "Inspectable proof that the native runtime owns and validates the model file."
  {:layout :extern}
  [[:loaded :bool]
   [:valid :bool]
   [:error_code :u32]
   [:version :u32]
   [:tensor_count :u64]
   [:metadata_count :u64]
   [:f32_tensors :u32]
   [:q4_0_tensors :u32]
   [:q6_k_tensors :u32]
   [:descriptor_end :usize]
   [:data_offset :usize]
   [:file_size :usize]])

(a/defstruct KernelReport
  "Small deterministic numerical probe used by JVM and standalone tests."
  {:layout :extern}
  [[:q4_dot :f32]
   [:rms_first :f32]
   [:rms_last :f32]
   [:softmax_sum :f32]])

(a/defstruct SequenceSummary
  "Inspectable ownership and token positions for all independent racer minds."
  {:layout :extern}
  [[:initialized :bool]
   [:racer_count :u8]
   [:capacity :u16]
   [:state_bytes :usize]
   [:positions [:array sequence-racer-count :u16]]])

(a/defstruct ForwardReport
  "One inspectable constrained token pass through all 32 native layers."
  {:layout :extern}
  [[:valid :bool]
   [:racer :u8]
   [:position :u16]
   [:input_token :u32]
   [:best_token :u32]
   [:best_logit :f32]
   [:hidden_first :f32]
   [:hidden_checksum :f32]
   [:candidate_count :u8]
   [:candidate_tokens [:array 8 :u32]]
   [:candidate_logits [:array 8 :f32]]])

(a/defstruct ActionHeadSummary
  "Validation and dimensions of the optional racing-specific linear head."
  {:layout :extern}
  [[:loaded :bool]
   [:valid :bool]
   [:error_code :u32]
   [:version :u32]
   [:input_count :u32]
   [:output_count :u32]
   [:observation_schema :u32]
   [:action_schema :u32]
   [:weight_count :u32]
   [:file_size :usize]])

(a/defvar model-bytes [:optional [:c-pointer :u8]] null)

(a/defvar model-byte-count :usize 0)

(a/defvar model-storage :u8 model-storage-none)

(a/defvar tensor-catalog-count :usize 0)

(a/defvar tensor-catalog [:array 4096 TensorInfo]
  (std-mem/zeroes (a/type [:array 4096 TensorInfo])))

(a/defvar metadata-catalog-count :usize 0)

(a/defvar metadata-catalog [:array 128 MetadataInfo]
  (std-mem/zeroes (a/type [:array 128 MetadataInfo])))

(a/defvar tokenizer-valid :bool false)

(a/defvar tokenizer-token-count :u32 0)

(a/defvar tokenizer-merge-count :u32 0)

(a/defvar tokenizer-token-starts [:array 100352 :u32]
  (std-mem/zeroes (a/type [:array 100352 :u32])))

(a/defvar tokenizer-token-lengths [:array 100352 :u16]
  (std-mem/zeroes (a/type [:array 100352 :u16])))

(a/defvar tokenizer-vocabulary-slots [:array 262144 :u32]
  (std-mem/zeroes (a/type [:array 262144 :u32])))

(a/defvar tokenizer-merge-pairs [:array 262144 :u64]
  (std-mem/zeroes (a/type [:array 262144 :u64])))

(a/defvar tokenizer-merge-ranks [:array 262144 :u32]
  (std-mem/zeroes (a/type [:array 262144 :u32])))

(a/defvar tokenizer-merge-tokens [:array 262144 :u32]
  (std-mem/zeroes (a/type [:array 262144 :u32])))

(a/defvar model-summary GgufSummary
  (GgufSummary {:loaded false :valid false :error_code parser-truncated
                :version 0 :tensor_count 0 :metadata_count 0
                :f32_tensors 0 :q4_0_tensors 0 :q6_k_tensors 0
                :descriptor_end 0 :data_offset 0 :file_size 0}))

(a/defvar sequence-memory [:optional [:c-pointer :f32]] null)

(a/defvar sequence-memory-floats :usize 0)

(a/defvar sequence-positions [:array sequence-racer-count :u16]
  (std-mem/zeroes (a/type [:array sequence-racer-count :u16])))

(a/defvar action-head-inputs [:array 196608 :f32]
  (std-mem/zeroes (a/type [:array 196608 :f32])))

(a/defvar fused-observation-inputs [:array 24576 :f32]
  (std-mem/zeroes (a/type [:array 24576 :f32])))

(a/defvar action-head-weights [:array 131072 :f32]
  (std-mem/zeroes (a/type [:array 131072 :f32])))

(a/defvar action-head-biases [:array 8 :f32]
  (std-mem/zeroes (a/type [:array 8 :f32])))

(a/defvar action-head-summary ActionHeadSummary
  (ActionHeadSummary
   {:loaded false :valid false :error_code 1 :version 0
    :input_count 0 :output_count 0 :observation_schema 0 :action_schema 0
    :weight_count 0 :file_size 0}))

(a/defvar team-head-weights [:array 49152 :f32]
  (std-mem/zeroes (a/type [:array 49152 :f32])))

(a/defvar team-head-biases [:array 3 :f32]
  (std-mem/zeroes (a/type [:array 3 :f32])))

(a/defvar team-head-summary ActionHeadSummary
  (ActionHeadSummary
   {:loaded false :valid false :error_code 1 :version 0
    :input_count 0 :output_count 0 :observation_schema 0 :action_schema 0
    :weight_count 0 :file_size 0}))

(a/defn can-read :bool
  [[reader [:* Reader]]
   [count :usize]]
  (and (== (a/field (a/deref reader) error_code) parser-ok)
       (<= count (- (a/field (a/deref reader) length)
                    (a/field (a/deref reader) cursor)))))

(a/defn read-u8! :u8
  [[reader [:* Reader]]]
  (if (can-read reader 1)
    (let [value (a/index (a/field (a/deref reader) bytes)
                         (a/field (a/deref reader) cursor))]
      (= (a/field (a/deref reader) cursor)
         (+ (a/field (a/deref reader) cursor) 1))
      value)
    (do
      (when (== (a/field (a/deref reader) error_code) parser-ok)
        (= (a/field (a/deref reader) error_code) parser-truncated))
      0)))

(a/defn read-u32! :u32
  [[reader [:* Reader]]]
  (let [b0 (as (read-u8! reader) :u32)
        b1 (as (read-u8! reader) :u32)
        b2 (as (read-u8! reader) :u32)
        b3 (as (read-u8! reader) :u32)]
    (+ b0 (* b1 256) (* b2 65536) (* b3 16777216))))

(a/defn read-u64! :u64
  [[reader [:* Reader]]]
  (let [low (as (read-u32! reader) :u64)
        high (as (read-u32! reader) :u64)]
    (+ low (* high 4294967296))))

(a/defn skip-bytes! :void
  [[reader [:* Reader]]
   [count :u64]]
  (if (and (<= count (as (a/field (a/deref reader) length) :u64))
           (can-read reader (intCast count)))
    (= (a/field (a/deref reader) cursor)
       (+ (a/field (a/deref reader) cursor) (as (intCast count) :usize)))
    (= (a/field (a/deref reader) error_code) parser-truncated)))

(a/defn read-string-view! StringView
  [[reader [:* Reader]]]
  (let [length (read-u64! reader)
        start (a/field (a/deref reader) cursor)]
    (skip-bytes! reader length)
    (StringView {:start start :length (intCast length)})))

(a/defn skip-string! :void
  [[reader [:* Reader]]]
  (= :_ (read-string-view! reader)))

(a/defn scalar-byte-size :u8
  [[value-type :u32]]
  (cond
    (or (== value-type 0) (== value-type 1) (== value-type 7)) 1
    (or (== value-type 2) (== value-type 3)) 2
    (or (== value-type 4) (== value-type 5) (== value-type 6)) 4
    (or (== value-type 10) (== value-type 11) (== value-type 12)) 8
    :else 0))

(a/defn skip-value! :void
  [[reader [:* Reader]]
   [value-type :u32]
   [depth :u8]]
  (cond
    (== value-type 8)
    (skip-string! reader)

    (== value-type 9)
    (if (>= depth 4)
      (= (a/field (a/deref reader) error_code) parser-limit)
      (let [element-type (read-u32! reader)
            count (read-u64! reader)]
        (if (> count gguf-max-array-elements)
          (= (a/field (a/deref reader) error_code) parser-limit)
          (dotimes [_ count]
            (skip-value! reader element-type (+ depth 1))))))

    :else
    (let [width (scalar-byte-size value-type)]
      (if (== width 0)
        (= (a/field (a/deref reader) error_code) parser-unsupported-type)
        (skip-bytes! reader width)))))

(a/defn parse-gguf GgufSummary
  [[bytes [:c-pointer :u8]]
   [length :usize]]
  (let [^:var reader (as (Reader {:bytes bytes :length length :cursor 0 :error_code parser-ok}) Reader)
        magic (read-u32! (ak/& reader))
        version (read-u32! (ak/& reader))
        tensor-count (read-u64! (ak/& reader))
        metadata-count (read-u64! (ak/& reader))
        ^:var f32-count (u32 0)
        ^:var q4-count (u32 0)
        ^:var q6-count (u32 0)]
    (= tensor-catalog-count 0)
    (= metadata-catalog-count 0)
    (when (!= magic gguf-magic)
      (= (a/field reader error_code) parser-bad-magic))
    (when (and (== (a/field reader error_code) parser-ok)
               (or (== version 0) (> version gguf-max-version)))
      (= (a/field reader error_code) parser-bad-version))
    (when (and (== (a/field reader error_code) parser-ok)
               (or (> tensor-count gguf-max-tensors)
                   (> metadata-count gguf-max-metadata)))
      (= (a/field reader error_code) parser-limit))
    (when (== (a/field reader error_code) parser-ok)
      (dotimes [metadata-index metadata-count]
        (let [key (read-string-view! (ak/& reader))
              value-type (read-u32! (ak/& reader))]
          (if (== value-type 9)
            (let [element-type (read-u32! (ak/& reader))
                  element-count (read-u64! (ak/& reader))
                  value-start (a/field reader cursor)]
              (when (< metadata-index metadata-not-found)
                (= (a/index metadata-catalog (intCast metadata-index))
                   (MetadataInfo
                    {:key_start (a/field key start)
                     :key_length (a/field key length)
                     :value_type value-type
                     :element_type element-type
                     :value_start value-start
                     :element_count element-count})))
              (if (> element-count gguf-max-array-elements)
                (= (a/field reader error_code) parser-limit)
                (dotimes [_ element-count]
                  (skip-value! (ak/& reader) element-type 1))))
            (let [value-start (a/field reader cursor)]
              (when (< metadata-index metadata-not-found)
                (= (a/index metadata-catalog (intCast metadata-index))
                   (MetadataInfo
                    {:key_start (a/field key start)
                     :key_length (a/field key length)
                     :value_type value-type
                     :element_type 0
                     :value_start value-start
                     :element_count 1})))
              (skip-value! (ak/& reader) value-type 0))))))
    (when (== (a/field reader error_code) parser-ok)
      (dotimes [tensor-index tensor-count]
        (let [name (read-string-view! (ak/& reader))
              dimensions (read-u32! (ak/& reader))
              ^:var shape (as (a/init [0 0 0 0] [:array 4 :u64]) [:array 4 :u64])]
          (if (> dimensions 4)
            (= (a/field reader error_code) parser-limit)
            (dotimes [dimension dimensions]
              (= (a/index shape dimension) (read-u64! (ak/& reader)))))
          (let [ggml-type (read-u32! (ak/& reader))
                relative-offset (read-u64! (ak/& reader))]
            (when (== ggml-type 0) (= f32-count (+ f32-count 1)))
            (when (== ggml-type 2) (= q4-count (+ q4-count 1)))
            (when (== ggml-type 14) (= q6-count (+ q6-count 1)))
            (when (and (!= ggml-type 0)
                       (!= ggml-type 2)
                       (!= ggml-type 14))
              (= (a/field reader error_code) parser-unsupported-type))
            (when (< tensor-index gguf-max-tensors)
              (= (a/index tensor-catalog (intCast tensor-index))
                 (TensorInfo {:name_start (a/field name start)
                              :name_length (a/field name length)
                              :dimension_count (intCast dimensions)
                              :dimensions shape
                              :ggml_type ggml-type
                              :relative_offset relative-offset
                              :data_address 0})))))))
    (let [descriptor-end (a/field reader cursor)
          data-offset (* (/ (+ descriptor-end 31) 32) 32)
          valid (and (== (a/field reader error_code) parser-ok)
                     (<= data-offset length))]
      (when valid
        (= metadata-catalog-count (intCast metadata-count))
        (= tensor-catalog-count (intCast tensor-count))
        (dotimes [tensor-index tensor-catalog-count]
          (= (a/field (a/index tensor-catalog tensor-index) data_address)
             (+ (intFromPtr bytes)
                data-offset
                (as (intCast
                     (a/field (a/index tensor-catalog tensor-index)
                              relative_offset))
                    :usize)))))
      (GgufSummary {:loaded true :valid valid
                    :error_code (a/field reader error_code)
                    :version version :tensor_count tensor-count
                    :metadata_count metadata-count
                    :f32_tensors f32-count :q4_0_tensors q4-count
                    :q6_k_tensors q6-count
                    :descriptor_end descriptor-end
                    :data_offset data-offset :file_size length}))))

(a/defn action-head-header-u32 :u32
  [[header [:* {:size :c :const? true} :u8]]
   [offset :usize]]
  (+ (as (a/index header offset) :u32)
     (* (as (a/index header (+ offset 1)) :u32) 256)
     (* (as (a/index header (+ offset 2)) :u32) 65536)
     (* (as (a/index header (+ offset 3)) :u32) 16777216)))

(a/defn unload-action-head! :void
  "Disable the racing-specific head without touching the shared base model."
  []
  (= action-head-summary
     (ActionHeadSummary
      {:loaded false :valid false :error_code 1 :version 0
       :input_count 0 :output_count 0
       :observation_schema 0 :action_schema 0
       :weight_count 0 :file_size 0})))

(a/defn unload-team-head! :void
  "Disable the independent three-choice team strategy head."
  []
  (= team-head-summary
     (ActionHeadSummary
      {:loaded false :valid false :error_code 1 :version 0
       :input_count 0 :output_count 0
       :observation_schema 0 :action_schema 0
       :weight_count 0 :file_size 0})))

(a/defn load-action-head! ActionHeadSummary
  "Load the verified fixed-layout A-H action head. The file contains a
  32-byte little-endian header, 8x6144 row-major f32 weights, and 8 f32 biases.
  The current two-step feature extractor fills the first 1536 values and keeps
  the remaining compatibility slots zero."
  [[path [:* {:size :c :const? true} :u8]]]
  (do
    (unload-action-head!)
    (let [file (runtime/fopen path "rb")]
      (if (== file null)
        action-head-summary
        (do
          (= :_ (runtime/fseek file 0 2))
          (let [signed-size (runtime/ftell file)]
            (= :_ (runtime/fseek file 0 0))
            (if (<= signed-size 0)
              (= action-head-summary
                 (ActionHeadSummary
                  {:loaded true :valid false :error_code 2 :version 0
                   :input_count 0 :output_count 0
                   :observation_schema 0 :action_schema 0
                   :weight_count 0 :file_size 0}))
              (let [size (as (intCast signed-size) :usize)
                    ^:var header
                    (std-mem/zeroes (a/type [:array 32 :u8]))
                    header-read
                    (runtime/fread (ak/& (a/index header 0)) 1
                                   action-head-header-bytes file)
                    magic (action-head-header-u32
                           (ak/& (a/index header 0)) 0)
                    version (action-head-header-u32
                             (ak/& (a/index header 0)) 4)
                    input-count (action-head-header-u32
                                 (ak/& (a/index header 0)) 8)
                    output-count (action-head-header-u32
                                  (ak/& (a/index header 0)) 12)
                    observation-schema (action-head-header-u32
                                        (ak/& (a/index header 0)) 16)
                    action-schema (action-head-header-u32
                                   (ak/& (a/index header 0)) 20)
                    weight-count (action-head-header-u32
                                  (ak/& (a/index header 0)) 24)
                    expected-size
                    (+ action-head-header-bytes
                       (* (+ action-head-weight-count action-head-output-count)
                          (sizeOf :f32)))
                    compatible
                    (and (== header-read action-head-header-bytes)
                         (== size expected-size)
                         (== magic action-head-magic)
                         (== version action-head-version)
                         (== input-count action-head-input-count)
                         (== output-count action-head-output-count)
                         (== observation-schema
                             protocol/observation-schema-version)
                         (== action-schema protocol/action-schema-version)
                         (== weight-count action-head-weight-count))]
                (if (! compatible)
                  (= action-head-summary
                     (ActionHeadSummary
                      {:loaded true :valid false :error_code 3
                       :version version :input_count input-count
                       :output_count output-count
                       :observation_schema observation-schema
                       :action_schema action-schema
                       :weight_count weight-count :file_size size}))
                  (let [weights-read
                        (runtime/fread
                         (ak/& (a/index action-head-weights 0))
                         (sizeOf :f32) action-head-weight-count file)
                        biases-read
                        (runtime/fread
                         (ak/& (a/index action-head-biases 0))
                         (sizeOf :f32) action-head-output-count file)
                        valid
                        (and (== weights-read action-head-weight-count)
                             (== biases-read action-head-output-count))]
                    (= action-head-summary
                       (ActionHeadSummary
                        {:loaded true :valid valid
                         :error_code (if valid 0 4)
                         :version version :input_count input-count
                         :output_count output-count
                         :observation_schema observation-schema
                         :action_schema action-schema
                         :weight_count weight-count :file_size size})))))))
          (= :_ (runtime/fclose file))
          action-head-summary)))))

(a/defn action-head-status ActionHeadSummary
  []
  action-head-summary)

(a/defn load-team-head! ActionHeadSummary
  "Load the verified stay-out/pit-A/pit-B team strategy head."
  [[path [:* {:size :c :const? true} :u8]]]
  (do
    (unload-team-head!)
    (let [file (runtime/fopen path "rb")]
      (if (== file null)
        team-head-summary
        (do
          (= :_ (runtime/fseek file 0 2))
          (let [signed-size (runtime/ftell file)]
            (= :_ (runtime/fseek file 0 0))
            (if (<= signed-size 0)
              (= team-head-summary
                 (ActionHeadSummary
                  {:loaded true :valid false :error_code 2 :version 0
                   :input_count 0 :output_count 0
                   :observation_schema 0 :action_schema 0
                   :weight_count 0 :file_size 0}))
              (let [size (as (intCast signed-size) :usize)
                    ^:var header
                    (std-mem/zeroes (a/type [:array 32 :u8]))
                    header-read
                    (runtime/fread (ak/& (a/index header 0)) 1
                                   action-head-header-bytes file)
                    magic (action-head-header-u32
                           (ak/& (a/index header 0)) 0)
                    version (action-head-header-u32
                             (ak/& (a/index header 0)) 4)
                    input-count (action-head-header-u32
                                 (ak/& (a/index header 0)) 8)
                    output-count (action-head-header-u32
                                  (ak/& (a/index header 0)) 12)
                    observation-schema (action-head-header-u32
                                        (ak/& (a/index header 0)) 16)
                    action-schema (action-head-header-u32
                                   (ak/& (a/index header 0)) 20)
                    weight-count (action-head-header-u32
                                  (ak/& (a/index header 0)) 24)
                    expected-size
                    (+ action-head-header-bytes
                       (* (+ team-head-weight-count team-head-output-count)
                          (sizeOf :f32)))
                    compatible
                    (and (== header-read action-head-header-bytes)
                         (== size expected-size)
                         (== magic team-head-magic)
                         (== version action-head-version)
                         (== input-count action-head-input-count)
                         (== output-count team-head-output-count)
                         (== observation-schema
                             protocol/observation-schema-version)
                         (== action-schema protocol/action-schema-version)
                         (== weight-count team-head-weight-count))]
                (if (! compatible)
                  (= team-head-summary
                     (ActionHeadSummary
                      {:loaded true :valid false :error_code 3
                       :version version :input_count input-count
                       :output_count output-count
                       :observation_schema observation-schema
                       :action_schema action-schema
                       :weight_count weight-count :file_size size}))
                  (let [weights-read
                        (runtime/fread
                         (ak/& (a/index team-head-weights 0))
                         (sizeOf :f32) team-head-weight-count file)
                        biases-read
                        (runtime/fread
                         (ak/& (a/index team-head-biases 0))
                         (sizeOf :f32) team-head-output-count file)
                        valid
                        (and (== weights-read team-head-weight-count)
                             (== biases-read team-head-output-count))]
                    (= team-head-summary
                       (ActionHeadSummary
                        {:loaded true :valid valid
                         :error_code (if valid 0 4)
                         :version version :input_count input-count
                         :output_count output-count
                         :observation_schema observation-schema
                         :action_schema action-schema
                         :weight_count weight-count :file_size size})))))))
          (= :_ (runtime/fclose file))
          team-head-summary)))))

(a/defn team-head-status ActionHeadSummary
  []
  team-head-summary)

(a/defn unload-model! :void
  []
  (free-sequences!)
  (unload-action-head!)
  (unload-team-head!)
  (when (!= model-bytes null)
    (if (== model-storage model-storage-mapped)
      (= :_
         (runtime/munmap
          (a/cast (a/unwrap model-bytes) [:* :anyopaque])
          model-byte-count))
      (runtime/free (a/cast (a/unwrap model-bytes) [:* :anyopaque]))))
  (= model-bytes null)
  (= model-byte-count 0)
  (= model-storage model-storage-none)
  (= model-profile-id model-profile-none)
  (= tokenizer-valid false)
  (= tokenizer-token-count 0)
  (= tokenizer-merge-count 0)
  (= tensor-catalog-count 0)
  (= metadata-catalog-count 0)
  (= model-summary
     (GgufSummary {:loaded false :valid false :error_code parser-truncated
                   :version 0 :tensor_count 0 :metadata_count 0
                   :f32_tensors 0 :q4_0_tensors 0 :q6_k_tensors 0
                   :descriptor_end 0 :data_offset 0 :file_size 0})))

(a/defn model-storage-kind :u8
  "Inspect whether weights are absent, heap-owned, or read-only mapped."
  []
  model-storage)

(a/defn loaded-model-sha256 [:array 32 :u8]
  "Compute the exact SHA-256 digest of the currently owned model bytes."
  []
  (let [^:var digest (as (std-mem/zeroes (a/type [:array 32 :u8])) [:array 32 :u8])]
    (when (!= model-bytes null)
      ((a/field std-sha2/Sha256 hash)
       (a/slice (a/unwrap model-bytes) 0 model-byte-count)
       (ak/& digest)
       {}))
    digest))

(a/defn sha256-matches? :bool
  "Compare one digest value with an expected 32-byte digest."
  [[actual [:array 32 :u8]]
   [expected [:* {:const? true} [:array 32 :u8]]]]
  (let [^:var equal (bool true)]
    (dotimes [index 32]
      (when (!= (a/index actual index)
                (a/index (a/deref expected) index))
        (= equal false)))
    equal))

(a/defn file-sha256-matches? :bool
  "Verify a native file without retaining its bytes after the check."
  [[path [:* {:size :c :const? true} :u8]]
   [expected [:* {:const? true} [:array 32 :u8]]]]
  (let [file (runtime/fopen path "rb")]
    (if (== file null)
      false
      (let [^:var valid (bool false)]
        (= :_ (runtime/fseek file 0 2))
        (let [signed-size (runtime/ftell file)]
          (= :_ (runtime/fseek file 0 0))
          (when (> signed-size 0)
            (let [size (as (intCast signed-size) :usize)
                  allocation (runtime/malloc size)]
              (when (!= allocation null)
                (let [bytes (a/cast allocation [:c-pointer :u8])
                      count (runtime/fread bytes 1 size file)
                      ^:var actual (as (std-mem/zeroes (a/type [:array 32 :u8])) [:array 32 :u8])]
                  (when (== count size)
                    ((a/field std-sha2/Sha256 hash)
                     (a/slice bytes 0 size)
                     (ak/& actual)
                     {})
                    (= valid (sha256-matches? actual expected)))
                  (runtime/free allocation))))))
        (= :_ (runtime/fclose file))
        valid))))

(a/defn tensor-info TensorInfo
  "Inspect one parsed tensor descriptor by stable GGUF order."
  [[index :usize]]
  (if (< index tensor-catalog-count)
    (a/index tensor-catalog index)
    (TensorInfo {:name_start 0 :name_length 0 :dimension_count 0
                 :dimensions (a/init [0 0 0 0] [:array 4 :u64])
                 :ggml_type 0 :relative_offset 0 :data_address 0})))

(a/defn metadata-info MetadataInfo
  "Inspect one parsed GGUF metadata descriptor by stable file order."
  [[index :usize]]
  (if (< index metadata-catalog-count)
    (a/index metadata-catalog index)
    (MetadataInfo {:key_start 0 :key_length 0 :value_type 0
                   :element_type 0 :value_start 0 :element_count 0})))

(a/defn metadata-name-byte :u8
  "Return one UTF-8 byte from a metadata key."
  [[metadata-index :usize]
   [byte-index :usize]]
  (if (or (== model-bytes null)
          (>= metadata-index metadata-catalog-count)
          (>= byte-index
              (a/field (a/index metadata-catalog metadata-index) key_length)))
    0
    (a/index (a/unwrap model-bytes)
             (+ (a/field (a/index metadata-catalog metadata-index) key_start)
                byte-index))))

(a/defn metadata-name-equals :bool
  [[metadata-index :usize]
   [expected [:* {:size :c :const? true} :u8]]]
  (if (or (== model-bytes null)
          (>= metadata-index metadata-catalog-count))
    false
    (let [length (a/field (a/index metadata-catalog metadata-index) key_length)
          ^:var equal (bool true)]
      (dotimes [byte-index length]
        (when (!= (metadata-name-byte metadata-index byte-index)
                  (a/index expected byte-index))
          (= equal false)))
      (and equal (== (a/index expected length) 0)))))

(a/defn find-metadata :usize
  "Resolve a GGUF metadata entry by its exact zero-terminated key."
  [[expected [:* {:size :c :const? true} :u8]]]
  (let [^:var found (usize metadata-not-found)]
    (dotimes [metadata-index metadata-catalog-count]
      (when (and (== found metadata-not-found)
                 (metadata-name-equals metadata-index expected))
        (= found metadata-index)))
    found))

(a/defn model-u32-at :u32
  [[offset :usize]]
  (if (or (== model-bytes null) (> (+ offset 4) model-byte-count))
    0
    (+ (as (a/index (a/unwrap model-bytes) offset) :u32)
       (* (as (a/index (a/unwrap model-bytes) (+ offset 1)) :u32) 256)
       (* (as (a/index (a/unwrap model-bytes) (+ offset 2)) :u32) 65536)
       (* (as (a/index (a/unwrap model-bytes) (+ offset 3)) :u32) 16777216))))

(a/defn model-u64-at :u64
  [[offset :usize]]
  (if (or (== model-bytes null) (> (+ offset 8) model-byte-count))
    0
    (+ (as (model-u32-at offset) :u64)
       (<< (as (model-u32-at (+ offset 4)) :u64) 32))))

(a/defn metadata-u32 :u32
  "Read a scalar u32 metadata value, returning `fallback` on type mismatch."
  [[metadata-index :usize]
   [fallback :u32]]
  (if (and (< metadata-index metadata-catalog-count)
           (== (a/field (a/index metadata-catalog metadata-index) value_type) 4))
    (model-u32-at
     (a/field (a/index metadata-catalog metadata-index) value_start))
    fallback))

(a/defn metadata-f32 :f32
  "Read a scalar f32 metadata value, returning `fallback` on type mismatch."
  [[metadata-index :usize]
   [fallback :f32]]
  (if (and (< metadata-index metadata-catalog-count)
           (== (a/field (a/index metadata-catalog metadata-index) value_type) 6))
    (as (bitCast
         (model-u32-at
          (a/field (a/index metadata-catalog metadata-index) value_start)))
        :f32)
    fallback))

(a/defn tokenizer-hash-range :u64
  [[start :usize]
   [length :usize]]
  (let [^:var hash (u64 1469598103934665603)]
    (when (!= model-bytes null)
      (dotimes [index length]
        (= hash
           (*% (bit-xor
                hash
                (as (a/index (a/unwrap model-bytes)
                             (+ start index))
                    :u64))
               1099511628211))))
    hash))

(a/defn tokenizer-hash-concat :u64
  [[left-start :usize]
   [left-length :usize]
   [right-start :usize]
   [right-length :usize]]
  (let [^:var hash (u64 1469598103934665603)]
    (when (!= model-bytes null)
      (dotimes [index left-length]
        (= hash
           (*% (bit-xor
                hash
                (as (a/index (a/unwrap model-bytes)
                             (+ left-start index))
                    :u64))
               1099511628211)))
      (dotimes [index right-length]
        (= hash
           (*% (bit-xor
                hash
                (as (a/index (a/unwrap model-bytes)
                             (+ right-start index))
                    :u64))
               1099511628211))))
    hash))

(a/defn tokenizer-token-equals-range :bool
  [[token :u32]
   [start :usize]
   [length :usize]]
  (if (or (>= token tokenizer-token-count)
          (!= (as (a/index tokenizer-token-lengths token) :usize)
              length))
    false
    (let [token-start (as (a/index tokenizer-token-starts token) :usize)
          ^:var equal (bool true)]
      (dotimes [index length]
        (when (!= (a/index (a/unwrap model-bytes) (+ token-start index))
                  (a/index (a/unwrap model-bytes) (+ start index)))
          (= equal false)))
      equal)))

(a/defn tokenizer-token-equals-concat :bool
  [[token :u32]
   [left-start :usize]
   [left-length :usize]
   [right-start :usize]
   [right-length :usize]]
  (if (or (>= token tokenizer-token-count)
          (!= (as (a/index tokenizer-token-lengths token) :usize)
              (+ left-length right-length)))
    false
    (let [token-start (as (a/index tokenizer-token-starts token) :usize)
          ^:var equal (bool true)]
      (dotimes [index left-length]
        (when (!= (a/index (a/unwrap model-bytes) (+ token-start index))
                  (a/index (a/unwrap model-bytes) (+ left-start index)))
          (= equal false)))
      (dotimes [index right-length]
        (when (!=
               (a/index (a/unwrap model-bytes)
                        (+ token-start left-length index))
               (a/index (a/unwrap model-bytes) (+ right-start index)))
          (= equal false)))
      equal)))

(a/defn tokenizer-insert-vocabulary! :bool
  [[token :u32]]
  (let [start (as (a/index tokenizer-token-starts token) :usize)
        length (as (a/index tokenizer-token-lengths token) :usize)
        base (as (intCast
                  (mod (tokenizer-hash-range start length)
                       tokenizer-hash-capacity))
                 :usize)
        ^:var inserted (bool false)]
    (dotimes [probe tokenizer-hash-capacity]
      (when (! inserted)
        (let [slot (mod (+ base probe) tokenizer-hash-capacity)]
          (when (== (a/index tokenizer-vocabulary-slots slot)
                    tokenizer-empty-id)
            (= (a/index tokenizer-vocabulary-slots slot) token)
            (= inserted true)
            (break)))))
    inserted))

(a/defn tokenizer-find-range :u32
  [[start :usize]
   [length :usize]]
  (let [base (as (intCast
                  (mod (tokenizer-hash-range start length)
                       tokenizer-hash-capacity))
                 :usize)
        ^:var result (u32 tokenizer-empty-id)
        ^:var searching (bool true)]
    (dotimes [probe tokenizer-hash-capacity]
      (when searching
        (let [slot (mod (+ base probe) tokenizer-hash-capacity)
              token (a/index tokenizer-vocabulary-slots slot)]
          (cond
            (== token tokenizer-empty-id) (= searching false)
            (tokenizer-token-equals-range token start length)
            (do (= result token) (= searching false)))
          (when (! searching) (break)))))
    result))

(a/defn tokenizer-find-concat :u32
  [[left-start :usize]
   [left-length :usize]
   [right-start :usize]
   [right-length :usize]]
  (let [base
        (as (intCast
             (mod (tokenizer-hash-concat
                   left-start left-length right-start right-length)
                  tokenizer-hash-capacity))
            :usize)
        ^:var result (u32 tokenizer-empty-id)
        ^:var searching (bool true)]
    (dotimes [probe tokenizer-hash-capacity]
      (when searching
        (let [slot (mod (+ base probe) tokenizer-hash-capacity)
              token (a/index tokenizer-vocabulary-slots slot)]
          (cond
            (== token tokenizer-empty-id) (= searching false)
            (tokenizer-token-equals-concat
             token left-start left-length right-start right-length)
            (do (= result token) (= searching false)))
          (when (! searching) (break)))))
    result))

(a/defn tokenizer-pair-key :u64
  [[left :u32]
   [right :u32]]
  (+ (<< (as left :u64) 32) (as right :u64)))

(a/defn tokenizer-pair-slot :usize
  [[pair :u64]
   [probe :usize]]
  (as (intCast
       (mod (+ (*% pair 11400714819323198485)
               (as probe :u64))
            tokenizer-hash-capacity))
      :usize))

(a/defn tokenizer-insert-merge! :bool
  [[left :u32]
   [right :u32]
   [rank :u32]
   [token :u32]]
  (let [pair (tokenizer-pair-key left right)
        ^:var inserted (bool false)]
    (dotimes [probe tokenizer-hash-capacity]
      (when (! inserted)
        (let [slot (tokenizer-pair-slot pair probe)]
          (when (== (a/index tokenizer-merge-pairs slot)
                    tokenizer-empty-pair)
            (= (a/index tokenizer-merge-pairs slot) pair)
            (= (a/index tokenizer-merge-ranks slot) rank)
            (= (a/index tokenizer-merge-tokens slot) token)
            (= inserted true)
            (break)))))
    inserted))

(a/defn tokenizer-find-merge TokenizerMerge
  [[left :u32]
   [right :u32]]
  (let [pair (tokenizer-pair-key left right)
        ^:var found (bool false)
        ^:var searching (bool true)
        ^:var rank (u32 0xffffffff)
        ^:var token (u32 0)]
    (dotimes [probe tokenizer-hash-capacity]
      (when searching
        (let [slot (tokenizer-pair-slot pair probe)
              candidate (a/index tokenizer-merge-pairs slot)]
          (cond
            (== candidate tokenizer-empty-pair) (= searching false)
            (== candidate pair)
            (do
              (= found true)
              (= searching false)
              (= rank (a/index tokenizer-merge-ranks slot))
              (= token (a/index tokenizer-merge-tokens slot))))
          (when (! searching) (break)))))
    (TokenizerMerge {:found found :rank rank :token token})))

(a/defn initialize-tokenizer! :bool
  "Build exact GPT-2 BPE vocabulary and merge indexes directly from the mapped
  GGUF metadata. This is the tokenizer used by both the game and bake-off."
  []
  (let [tokens-index (find-metadata "tokenizer.ggml.tokens")
        merges-index (find-metadata "tokenizer.ggml.merges")
        tokens-info
        (a/index metadata-catalog
                 (if (< tokens-index metadata-catalog-count) tokens-index 0))
        merges-info
        (a/index metadata-catalog
                 (if (< merges-index metadata-catalog-count) merges-index 0))
        ^:var valid (bool (and (< tokens-index metadata-catalog-count)
                               (< merges-index metadata-catalog-count)
                               (== (a/field tokens-info value_type) 9)
                               (== (a/field tokens-info element_type) 8)
                               (== (a/field tokens-info element_count)
                                   tokenizer-vocabulary-capacity)
                               (== (a/field merges-info value_type) 9)
                               (== (a/field merges-info element_type) 8)
                               (<= (a/field merges-info element_count)
                                   tokenizer-vocabulary-capacity)))
        ^:var cursor (usize (if valid (a/field tokens-info value_start) 0))]
    (= tokenizer-valid false)
    (= tokenizer-token-count 0)
    (= tokenizer-merge-count 0)
    (dotimes [slot tokenizer-hash-capacity]
      (= (a/index tokenizer-vocabulary-slots slot) tokenizer-empty-id)
      (= (a/index tokenizer-merge-pairs slot) tokenizer-empty-pair))
    (when valid
      (dotimes [token tokenizer-vocabulary-capacity]
        (when valid
          (let [length64 (model-u64-at cursor)
                start (+ cursor 8)]
            (if (or (> length64 65535)
                    (> (+ start (as (intCast length64) :usize))
                       model-byte-count))
              (= valid false)
              (do
                (= (a/index tokenizer-token-starts token)
                   (intCast start))
                (= (a/index tokenizer-token-lengths token)
                   (intCast length64))
                (= tokenizer-token-count (intCast (+ token 1)))
                (= cursor (+ start (as (intCast length64) :usize))))))))
      (dotimes [token tokenizer-vocabulary-capacity]
        (when valid
          (= valid (tokenizer-insert-vocabulary! (intCast token)))))
      (= cursor (a/field merges-info value_start))
      (dotimes [merge-index (a/field merges-info element_count)]
        (when valid
          (let [length64 (model-u64-at cursor)
                length (as (intCast length64) :usize)
                start (+ cursor 8)
                ^:var separator (usize length)]
            (when (> (+ start length) model-byte-count)
              (= valid false))
            (when valid
              (dotimes [index length]
                (when (and (== separator length)
                           (== (a/index (a/unwrap model-bytes)
                                        (+ start index))
                               32))
                  (= separator index)))
              (if (or (== separator 0) (>= separator (- length 1)))
                (= valid false)
                (let [right-start (+ start separator 1)
                      right-length (- length separator 1)
                      left (tokenizer-find-range start separator)
                      right (tokenizer-find-range right-start right-length)
                      merged (tokenizer-find-concat
                              start separator right-start right-length)]
                  (if (or (== left tokenizer-empty-id)
                          (== right tokenizer-empty-id)
                          (== merged tokenizer-empty-id)
                          (! (tokenizer-insert-merge!
                              left right (intCast merge-index) merged)))
                    (= valid false)
                    (= tokenizer-merge-count
                       (intCast (+ merge-index 1)))))))
            (= cursor (+ start length))))))
    (= tokenizer-valid valid)
    valid))

(a/defn tokenizer-summary TokenizerSummary
  []
  (TokenizerSummary
   {:valid tokenizer-valid
    :token_count tokenizer-token-count
    :merge_count tokenizer-merge-count
    :vocabulary_slots (intCast tokenizer-hash-capacity)
    :merge_slots (intCast tokenizer-hash-capacity)}))

(a/defn configure-model-profile! :bool
  "Select one validated Granite hybrid profile from GGUF metadata and derive
  every recurrent, convolution, attention, and classifier-state size used by
  the shared native kernels. Unsupported shapes are rejected before inference."
  []
  (let [hidden
        (metadata-u32 (find-metadata "granitehybrid.embedding_length") 0)
        ffn
        (metadata-u32 (find-metadata "granitehybrid.feed_forward_length") 0)
        layers
        (metadata-u32 (find-metadata "granitehybrid.block_count") 0)
        inner
        (metadata-u32 (find-metadata "granitehybrid.ssm.inner_size") 0)
        state
        (metadata-u32 (find-metadata "granitehybrid.ssm.state_size") 0)
        groups
        (metadata-u32 (find-metadata "granitehybrid.ssm.group_count") 0)
        heads
        (metadata-u32 (find-metadata "granitehybrid.ssm.time_step_rank") 0)
        attention-heads
        (metadata-u32 (find-metadata "granitehybrid.attention.head_count") 0)
        ;; Hybrid metadata can use a per-layer KV-head array. Derive the
        ;; actual KV projection width from the first attention tensor instead
        ;; of silently assuming every model uses four KV heads.
        kv-index (find-tensor (if (== hidden 768)
                                "blk.10.attn_k.weight" "blk.5.attn_k.weight"))
        kv-heads
        (if (and (> attention-heads 0) (> hidden 0)
                 (== (mod hidden (max attention-heads 1)) 0)
                 (< kv-index tensor-catalog-count)
                 (== (a/field (a/index tensor-catalog kv-index) dimension_count) 2)
                 (== (a/index (a/field (a/index tensor-catalog kv-index) dimensions) 0) hidden)
                 (== (mod (a/index (a/field (a/index tensor-catalog kv-index) dimensions) 1)
                          (/ hidden attention-heads)) 0))
          (as (intCast
               (/ (a/index (a/field (a/index tensor-catalog kv-index) dimensions) 1)
                  (/ hidden attention-heads))) :u32)
          (as 0 :u32))
        profile
        (cond
          (and (== hidden 768) (== ffn 2048) (== layers 32)
               (== inner 1536) (== state 128) (== groups 1)
               (== heads 48) (== attention-heads 12)
               (== kv-heads 4))
          model-profile-granite-h-350m

          (and (== hidden 1536) (== ffn 4096) (== layers 40)
               (== inner 3072) (== state 128) (== groups 1)
               (== heads 48) (== attention-heads 12)
               (== kv-heads 4))
          model-profile-granite-h-1b

          (and (== hidden 2048) (== ffn 8192) (== layers 40)
               (== inner 4096) (== state 128) (== groups 1)
               (== heads 64) (== attention-heads 32)
               (== kv-heads 8))
          model-profile-granite-h-micro

          :else model-profile-none)
        ^:var valid (bool (!= profile model-profile-none))]
    (= model-profile-id profile)
    (when valid
      (= model-hidden-size (intCast hidden))
      (= model-ffn-size (intCast ffn))
      (= model-layer-count (intCast layers))
      (= model-mamba-layer-count (- model-layer-count 4))
      (= model-mamba-inner-size (intCast inner))
      (= model-mamba-conv-size
         (+ model-mamba-inner-size (* 2 (as state :usize))))
      (= model-mamba-projection-size
         (+ (* 2 model-mamba-inner-size)
            (* 2 (as state :usize))
            (as heads :usize)))
      (= model-mamba-head-count (intCast heads))
      (= model-mamba-head-size
         (/ model-mamba-inner-size model-mamba-head-count))
      (= model-mamba-state-size (intCast state))
      (= model-mamba-recurrent-size
         (* model-mamba-head-count model-mamba-head-size
            model-mamba-state-size))
      (= model-mamba-conv-state-size (* model-mamba-conv-size 3))
      (= model-attention-layer-count 4)
      (= model-attention-head-count (intCast attention-heads))
      (= model-attention-kv-head-count (intCast kv-heads))
      (= model-attention-head-size
         (/ model-hidden-size model-attention-head-count))
      (= model-attention-kv-size
         (* model-attention-kv-head-count model-attention-head-size))
      (= model-attention-scale
         (metadata-f32
          (find-metadata "granitehybrid.attention.scale")
          (if (== profile model-profile-granite-h-1b)
            0.0078125
            0.015625)))
      (= model-residual-multiplier
         (metadata-f32
          (find-metadata "granitehybrid.residual_scale")
          (if (== profile model-profile-granite-h-350m) 0.246 0.22)))
      (= action-head-input-count
         (* action-head-token-count model-hidden-size))
      (= action-head-weight-count
         (* action-head-output-count action-head-input-count))
      (= team-head-weight-count
         (* team-head-output-count action-head-input-count))
      (= sequence-mamba-floats
         (* sequence-racer-count model-mamba-layer-count
            model-mamba-recurrent-size))
      (= sequence-conv-floats
         (* sequence-racer-count model-mamba-layer-count
            model-mamba-conv-state-size))
      (= sequence-kv-floats
         (* sequence-racer-count model-attention-layer-count
            sequence-capacity model-attention-kv-size))
      (= sequence-total-floats
         (+ sequence-mamba-floats sequence-conv-floats
            (* 2 sequence-kv-floats)))
      (= sequence-total-bytes
         (* sequence-total-floats (sizeOf :f32))))
    (when valid
      (= valid (initialize-tokenizer!)))
    (when (! valid)
      (= model-profile-id model-profile-none))
    valid))

(a/defn model-profile-summary ModelProfileSummary
  "Expose the active dimensions used by the native game and bake-off."
  []
  (ModelProfileSummary
   {:valid (!= model-profile-id model-profile-none)
    :profile model-profile-id
    :hidden_size (intCast model-hidden-size)
    :ffn_size (intCast model-ffn-size)
    :layer_count (intCast model-layer-count)
    :mamba_layer_count (intCast model-mamba-layer-count)
    :mamba_inner_size (intCast model-mamba-inner-size)
    :mamba_projection_size (intCast model-mamba-projection-size)
    :mamba_conv_size (intCast model-mamba-conv-size)
    :mamba_head_count (intCast model-mamba-head-count)
    :mamba_head_size (intCast model-mamba-head-size)
    :attention_layer_count (intCast model-attention-layer-count)
    :attention_head_count (intCast model-attention-head-count)
    :attention_kv_head_count (intCast model-attention-kv-head-count)
    :attention_head_size (intCast model-attention-head-size)
    :sequence_state_bytes sequence-total-bytes}))

(a/defn ascii-byte-token :u32
  "Map one ASCII byte to the model's exact GPT-2 base-byte token id."
  [[byte :u8]]
  (cond
    (and (>= byte 33) (<= byte 126)) (- (as byte :u32) 33)
    (== byte 32) 220
    (== byte 10) 198
    (== byte 9) 197
    (== byte 13) 201
    :else 100269))

(a/defn tokenize-compact-ascii TokenizationReport
  "Encode bounded readable ASCII with Granite's exact GGUF GPT-2 BPE merges.
  Before a model is loaded the base-byte result remains available for parser
  probes; live game and bake-off paths require the initialized native index."
  [[bytes [:* {:size :c :const? true} :u8]]
   [length :usize]]
  (let [count (min length tokenizer-capacity)
        ^:var token-count (usize count)
        ^:var valid (bool true)
        ^:var unsupported (u16 (intCast length))
        ^:var tokens (as (std-mem/zeroes (a/type [:array 160 :u32])) [:array 160 :u32])]
    (dotimes [index count]
      (let [byte (a/index bytes index)
            token (ascii-byte-token byte)]
        (= (a/index tokens index) token)
        (when (and valid (== token 100269))
          (= valid false)
          (= unsupported (intCast index)))))
    (dotimes [_ count]
      (when (and valid tokenizer-valid (> token-count 1))
        (let [^:var found (bool false)
              ^:var best-rank (u32 0xffffffff)
              ^:var best-left (u32 0)
              ^:var best-right (u32 0)
              ^:var best-token (u32 0)]
          (dotimes [index (- token-count 1)]
            (let [left (a/index tokens index)
                  right (a/index tokens (+ index 1))
                  merge (tokenizer-find-merge left right)]
              (when (and (a/field merge found)
                         (< (a/field merge rank) best-rank))
                (= found true)
                (= best-rank (a/field merge rank))
                (= best-left left)
                (= best-right right)
                (= best-token (a/field merge token)))))
          (when found
            (let [^:var input-index (usize 0)
                  ^:var output-index (usize 0)]
              (while (< input-index token-count)
                (if (and (< (+ input-index 1) token-count)
                         (== (a/index tokens input-index) best-left)
                         (== (a/index tokens (+ input-index 1)) best-right))
                  (do
                    (= (a/index tokens output-index) best-token)
                    (= input-index (+ input-index 2)))
                  (do
                    (= (a/index tokens output-index)
                       (a/index tokens input-index))
                    (= input-index (+ input-index 1))))
                (= output-index (+ output-index 1)))
              (= token-count output-index))))))
    (TokenizationReport
     {:valid valid
      :truncated (> length tokenizer-capacity)
      :byte_count (intCast length)
      :token_count (intCast token-count)
      :unsupported_index unsupported
      :reserved 0
      :tokens tokens})))

(a/defn tensor-name-byte :u8
  "Return one UTF-8 byte from a tensor name for JVM/native inspection."
  [[tensor-index :usize]
   [byte-index :usize]]
  (if (or (== model-bytes null)
          (>= tensor-index tensor-catalog-count)
          (>= byte-index
              (a/field (a/index tensor-catalog tensor-index) name_length)))
    0
    (a/index (a/unwrap model-bytes)
             (+ (a/field (a/index tensor-catalog tensor-index) name_start)
                byte-index))))

(a/defn tensor-name-equals :bool
  [[tensor-index :usize]
   [expected [:* {:size :c :const? true} :u8]]]
  (if (or (== model-bytes null)
          (>= tensor-index tensor-catalog-count))
    false
    (let [length (a/field (a/index tensor-catalog tensor-index) name_length)
          ^:var equal (bool true)]
      (dotimes [byte-index length]
        (when (!= (tensor-name-byte tensor-index byte-index)
                  (a/index expected byte-index))
          (= equal false)))
      (and equal (== (a/index expected length) 0)))))

(a/defn find-tensor :usize
  "Resolve a GGUF tensor by its exact zero-terminated name."
  [[expected [:* {:size :c :const? true} :u8]]]
  (let [^:var found (usize tensor-not-found)]
    (dotimes [tensor-index tensor-catalog-count]
      (when (and (== found tensor-not-found)
                 (tensor-name-equals tensor-index expected))
        (= found tensor-index)))
    found))

(a/defn load-model! GgufSummary
  "Map and validate the pinned GGUF, falling back to an owned native read."
  [[path [:* {:size :c :const? true} :u8]]]
  (unload-model!)
  (let [file (runtime/fopen path "rb")]
    (if (== file null)
      (do
        (= model-summary
           (GgufSummary
            {:loaded false :valid false :error_code model-file-not-found
             :version 0 :tensor_count 0 :metadata_count 0
             :f32_tensors 0 :q4_0_tensors 0 :q6_k_tensors 0
             :descriptor_end 0 :data_offset 0 :file_size 0}))
        model-summary)
      (do
        (= :_ (runtime/fseek file 0 2))
        (let [signed-size (runtime/ftell file)]
          (= :_ (runtime/fseek file 0 0))
          (if (<= signed-size 0)
            (= model-summary
               (GgufSummary
                {:loaded true :valid false :error_code model-file-empty
                 :version 0 :tensor_count 0 :metadata_count 0
                 :f32_tensors 0 :q4_0_tensors 0 :q6_k_tensors 0
                 :descriptor_end 0 :data_offset 0 :file_size 0}))
            (let [size (as (intCast signed-size) :usize)
                  mapping
                  (ak/catch
                   (std-posix/mmap
                    null size
                    {:READ true}
                    {:TYPE :PRIVATE}
                    (runtime/fileno file)
                    0)
                   null)]
              (if (!= mapping null)
                (let [bytes
                      (as (ptrCast
                           (a/field (a/unwrap mapping) ptr))
                          (a/type [:c-pointer :u8]))]
                  (= model-bytes bytes)
                  (= model-byte-count size)
                  (= model-storage model-storage-mapped)
                  (= model-summary (parse-gguf bytes size)))
                (let [allocation (runtime/malloc size)]
                  (if (== allocation null)
                    (= model-summary
                       (GgufSummary
                        {:loaded true :valid false
                         :error_code model-allocation-failed
                         :version 0 :tensor_count 0 :metadata_count 0
                         :f32_tensors 0 :q4_0_tensors 0 :q6_k_tensors 0
                         :descriptor_end 0 :data_offset 0 :file_size size}))
                    (let [bytes (a/cast allocation [:c-pointer :u8])
                          count (runtime/fread bytes 1 size file)]
                      (if (== count size)
                        (do
                          (= model-bytes bytes)
                          (= model-byte-count size)
                          (= model-storage model-storage-owned)
                          (= model-summary (parse-gguf bytes size)))
                        (do
                          (runtime/free allocation)
                          (= model-summary
                             (GgufSummary
                              {:loaded true :valid false
                               :error_code model-file-read-failed
                               :version 0 :tensor_count 0 :metadata_count 0
                               :f32_tensors 0 :q4_0_tensors 0
                               :q6_k_tensors 0 :descriptor_end 0
                               :data_offset 0 :file_size size})))))))))))
        (when (and (a/field model-summary valid)
                   (! (configure-model-profile!)))
          (= (a/field model-summary valid) false)
          (= (a/field model-summary error_code) model-profile-unsupported))
        (= :_ (runtime/fclose file))
        model-summary))))

(a/defn inference-summary GgufSummary
  []
  model-summary)

(a/defn- q4-0-dot :f32
  "SIMD Q4_0 dot product for one GGML block of 32 values. The packed low
  and high nibbles become two 16-lane vectors, preserving GGML's layout while
  allowing Zig to use the host's native vector instructions."
  [[block [:* {:size :c :const? true} :u8]]
   [input [:* {:size :c :const? true} :f32]]]
  (let [scale-bits (+ (as (a/index block 0) :u16)
                      (* (as (a/index block 1) :u16) 256))
        scale (as (floatCast (as (bitCast scale-bits) :f16)) :f32)
        packed-bytes (as (bitCast
                          (a/deref
                           (a/cast (+ block 2)
                                   [:* {:const? true}
                                    [:array 16 :u8]]))) [:vector 16 :u8])
        low-unsigned
        (as (ak/& packed-bytes
                  (as (splat 15) (a/type [:vector 16 :u8]))) [:vector 16 :u8])
        high-unsigned
        (as (>> packed-bytes
                (as (splat 4) [:vector 16 :u3])) [:vector 16 :u8])
        low-signed
        (as (- (as (intCast low-unsigned)
                   (a/type [:vector 16 :i16]))
               (as (splat 8) (a/type [:vector 16 :i16]))) [:vector 16 :i16])
        high-signed
        (as (- (as (intCast high-unsigned)
                   (a/type [:vector 16 :i16]))
               (as (splat 8) (a/type [:vector 16 :i16]))) [:vector 16 :i16])
        low-values
        (as (as (floatFromInt low-signed)
                (a/type [:vector 16 :f32])) [:vector 16 :f32])
        high-values
        (as (as (floatFromInt high-signed)
                (a/type [:vector 16 :f32])) [:vector 16 :f32])
        low-input (as (bitCast
                       (a/deref
                        (a/cast input
                                [:* {:const? true}
                                 [:array 16 :f32]]))) [:vector 16 :f32])
        high-input (as (bitCast
                        (a/deref
                         (a/cast (+ input 16)
                                 [:* {:const? true}
                                  [:array 16 :f32]]))) [:vector 16 :f32])]
    (* scale
       (reduce :Add
               (+ (* low-values low-input)
                  (* high-values high-input))))))

(a/defn- q4-0-value :f32
  "Decode one value from a GGML Q4_0 block without allocating."
  [[block [:* {:size :c :const? true} :u8]]
   [index :usize]]
  (if (>= index 32)
    0.0
    (let [scale-bits (+ (as (a/index block 0) :u16)
                        (* (as (a/index block 1) :u16) 256))
          scale (as (floatCast (as (bitCast scale-bits) :f16))
                    :f32)
          packed-byte (a/index block (+ 2 (mod index 16)))
          quantized (if (< index 16)
                      (mod packed-byte 16)
                      (/ packed-byte 16))]
      (* scale
         (as (floatFromInt (- (as quantized :i16) 8))
             :f32)))))

(a/defn rms-norm! :void
  "Allocation-free RMSNorm over one dense activation vector."
  [[output [:c-pointer :f32]]
   [input [:* {:size :c :const? true} :f32]]
   [weights [:* {:size :c :const? true} :f32]]
   [length :usize]
   [epsilon :f32]]
  (let [^:var square-sum (f32 0.0)]
    (dotimes [index length]
      (= square-sum (+ square-sum (* (a/index input index)
                                     (a/index input index)))))
    (let [inverse-rms (/ 1.0 (std-math/sqrt (+ (/ square-sum
                                                  (as (floatFromInt length) :f32))
                                               epsilon)))]
      (dotimes [index length]
        (= (a/index output index)
           (* (a/index input index) inverse-rms (a/index weights index)))))))

(a/defn softmax! :void
  "Stable in-place softmax used by the attention reference path."
  [[values [:c-pointer :f32]]
   [length :usize]]
  (when (> length 0)
    (let [^:var maximum (f32 (a/index values 0))
          ^:var total (f32 0.0)]
      (dotimes [index length]
        (= maximum (max maximum (a/index values index))))
      (dotimes [index length]
        (let [value (std-math/exp (- (a/index values index) maximum))]
          (= (a/index values index) value)
          (= total (+ total value))))
      (dotimes [index length]
        (= (a/index values index) (/ (a/index values index) total))))))

(a/defn- softplus :f32
  [[value :f32]]
  (if (> value 20.0)
    value
    (std-math/log1p (std-math/exp value))))

(a/defn mamba-selective-step! :void
  "One exact recurrent Mamba-2 selective-state update for the model's
  single-group layout. State is `[head][head-component][ssm-component]`."
  [[output [:c-pointer :f32]]
   [state [:c-pointer :f32]]
   [hidden [:* {:size :c :const? true} :f32]]
   [dt [:* {:size :c :const? true} :f32]]
   [a [:* {:size :c :const? true} :f32]]
   [b [:* {:size :c :const? true} :f32]]
   [c [:* {:size :c :const? true} :f32]]
   [d [:* {:size :c :const? true} :f32]]
   [dt-bias [:* {:size :c :const? true} :f32]]
   [head-count :usize]
   [head-dimension :usize]
   [state-size :usize]]
  (dotimes [head head-count]
    (let [delta (softplus (+ (a/index dt head) (a/index dt-bias head)))
          decay (std-math/exp (* delta (a/index a head)))]
      (dotimes [component head-dimension]
        (let [hidden-index (+ (* head head-dimension) component)
              hidden-value (a/index hidden hidden-index)
              ^:var total (f32 0.0)]
          (dotimes [state-component state-size]
            (let [state-index (+ (* hidden-index state-size) state-component)
                  next-state (+ (* (a/index state state-index) decay)
                                (* delta (a/index b state-component)
                                   hidden-value))]
              (= (a/index state state-index) next-state)
              (= total (+ total
                          (* next-state (a/index c state-component))))))
          (= (a/index output hidden-index)
             (+ total (* hidden-value (a/index d head)))))))))

(a/defn tensor-rms-norm-gated! :bool
  "Apply Granite's SiLU gate before weighted RMS normalization."
  [[output [:c-pointer :f32]]
   [hidden [:* {:size :c :const? true} :f32]]
   [gate [:* {:size :c :const? true} :f32]]
   [weights-index :usize]
   [length :usize]
   [epsilon :f32]]
  (if (or (>= weights-index tensor-catalog-count)
          (!= (a/field (a/index tensor-catalog weights-index) ggml_type) 0))
    false
    (let [^:var square-sum (f32 0.0)]
      (dotimes [index length]
        (let [value (* (a/index hidden index)
                       (silu (a/index gate index)))]
          (= (a/index output index) value)
          (= square-sum (+ square-sum (* value value)))))
      (let [inverse-rms (/ 1.0
                           (std-math/sqrt
                            (+ (/ square-sum
                                  (as (floatFromInt length) :f32))
                               epsilon)))]
        (dotimes [index length]
          (= (a/index output index)
             (* (a/index output index) inverse-rms
                (tensor-element weights-index index)))))
      true)))

(a/defn attention-layer? :bool
  "Whether one Granite hybrid layer uses causal GQA instead of Mamba-2."
  [[layer :usize]]
  (if (or (== model-profile-id model-profile-granite-h-1b)
          (== model-profile-id model-profile-granite-h-micro))
    (or (== layer 5)
        (== layer 15)
        (== layer 25)
        (== layer 35))
    (or (== layer 10)
        (== layer 13)
        (== layer 17)
        (== layer 27))))

(a/defn attention-layer-slot :usize
  "Map one supported profile's attention layer to compact KV slot 0..3."
  [[layer :usize]]
  (if (! (attention-layer? layer))
    model-attention-layer-count
    (let [^:var slot (usize 0)]
      (dotimes [candidate layer]
        (when (attention-layer? candidate)
          (= slot (+ slot 1))))
      slot)))

(a/defn attention-layers-before :usize
  [[layer :usize]]
  (let [^:var count (usize 0)]
    (dotimes [candidate layer]
      (when (attention-layer? candidate)
        (= count (+ count 1))))
    count))

(a/defn layer-base-index :usize
  "Resolve the first tensor in a pinned Granite layer without name scans."
  [[layer :usize]]
  (if (>= layer model-layer-count)
    tensor-not-found
    (- (+ 2 (* layer 13)) (* (attention-layers-before layer) 4))))

(a/defn layer-attention-norm-index :usize
  [[layer :usize]]
  (let [base (layer-base-index layer)]
    (if (== base tensor-not-found)
      tensor-not-found
      (+ base (if (attention-layer? layer)
                (as 1 :usize)
                (as 0 :usize))))))

(a/defn layer-ffn-down-index :usize
  [[layer :usize]]
  (let [base (layer-base-index layer)]
    (if (== base tensor-not-found)
      tensor-not-found
      (+ base (if (attention-layer? layer)
                (as 5 :usize)
                (as 1 :usize))))))

(a/defn layer-ffn-gate-index :usize
  [[layer :usize]]
  (let [base (layer-base-index layer)]
    (if (== base tensor-not-found)
      tensor-not-found
      (+ base (if (attention-layer? layer)
                (as 6 :usize)
                (as 2 :usize))))))

(a/defn layer-ffn-norm-index :usize
  [[layer :usize]]
  (let [base (layer-base-index layer)]
    (if (== base tensor-not-found)
      tensor-not-found
      (+ base (if (attention-layer? layer)
                (as 7 :usize)
                (as 3 :usize))))))

(a/defn layer-ffn-up-index :usize
  [[layer :usize]]
  (let [base (layer-base-index layer)]
    (if (== base tensor-not-found)
      tensor-not-found
      (+ base (if (attention-layer? layer)
                (as 8 :usize)
                (as 4 :usize))))))

(a/defn free-sequences! :void
  "Release all native racer cognition state while leaving shared weights loaded."
  []
  (when (!= sequence-memory null)
    (runtime/free
     (a/cast (a/unwrap sequence-memory) [:* :anyopaque])))
  (= sequence-memory null)
  (= sequence-memory-floats 0)
  (dotimes [racer sequence-racer-count]
    (= (a/index sequence-positions racer) 0)))

(a/defn reset-all-sequences! :bool
  "Clear every recurrent, convolution, and KV state in-place."
  []
  (let [initialized (!= sequence-memory null)]
    (when initialized
      (let [memory (a/unwrap sequence-memory)]
        (memset (a/slice memory 0 sequence-memory-floats) 0.0))
      (dotimes [racer sequence-racer-count]
        (= (a/index sequence-positions racer) 0))
      (memset (a/slice action-head-inputs 0 (* sequence-racer-count action-head-input-count)) 0.0))
    initialized))

(a/defn initialize-sequences! :bool
  "Own one shared allocation containing thirty isolated model sequence states:
  twenty drivers followed by ten team strategists."
  []
  (free-sequences!)
  (let [allocation (runtime/malloc sequence-total-bytes)
        ^:var initialized (bool false)]
    (when (!= allocation null)
      (= sequence-memory (a/cast allocation [:c-pointer :f32]))
      (= sequence-memory-floats sequence-total-floats)
      (= initialized (reset-all-sequences!)))
    initialized))

(a/defn reset-sequence! :bool
  "Clear one racer's independent recurrent and KV history."
  [[racer :usize]]
  (let [valid (and (< racer sequence-racer-count)
                   (!= sequence-memory null))]
    (when valid
      (let [memory (a/unwrap sequence-memory)
            mamba-count (* model-mamba-layer-count
                           model-mamba-recurrent-size)
            conv-count (* model-mamba-layer-count
                          model-mamba-conv-state-size)
            kv-count (* model-attention-layer-count
                        sequence-capacity model-attention-kv-size)
            mamba-start (* racer mamba-count)
            conv-start (+ sequence-mamba-floats (* racer conv-count))
            key-start (+ sequence-mamba-floats sequence-conv-floats
                         (* racer kv-count))
            value-start (+ sequence-mamba-floats sequence-conv-floats
                           sequence-kv-floats (* racer kv-count))]
        (memset (a/slice memory mamba-start (+ mamba-start mamba-count)) 0.0)
        (memset (a/slice memory conv-start (+ conv-start conv-count)) 0.0)
        (memset (a/slice memory key-start (+ key-start kv-count)) 0.0)
        (memset (a/slice memory value-start (+ value-start kv-count)) 0.0)
        (memset (a/slice action-head-inputs (* racer action-head-input-count)
                         (* (+ racer 1) action-head-input-count)) 0.0)
        (= (a/index sequence-positions racer) 0)))
    valid))

(a/defn copy-last-hidden! :bool
  "Copy one racer's most recent final normalized hidden state for offline
  training or inspection. The caller owns `output` and at least
  model-hidden-size floats (768, 1536 or 2048 for supported profiles)."
  [[racer :usize]
   [output [:c-pointer :f32]]]
  (let [valid (and (< racer sequence-racer-count)
                   (> (a/index sequence-positions racer) 0))]
    (when valid
      (dotimes [index model-hidden-size]
        (= (a/index output index)
           (a/index action-head-inputs
                    (+ (* racer action-head-input-count)
                       (* (as (min
                               (- (a/index sequence-positions racer) 1)
                               (as 7 :u16))
                              :usize)
                          model-hidden-size)
                       index)))))
    valid))

(a/defn copy-action-features! :bool
  "Copy the fixed action-head feature buffer. Sequential extraction fills all
  eight slots; fused extraction fills the first slot and clears the rest."
  [[racer :usize]
   [output [:c-pointer :f32]]]
  (let [valid (and (< racer sequence-racer-count)
                   (> (a/index sequence-positions racer) 0))]
    (when valid
      (dotimes [index action-head-input-count]
        (= (a/index output index)
           (a/index action-head-inputs
                    (+ (* racer action-head-input-count) index)))))
    valid))

(a/defn sequence-summary SequenceSummary
  "Return allocation size and each racer's independent token position."
  []
  (SequenceSummary
   {:initialized (!= sequence-memory null)
    :racer_count (intCast sequence-racer-count)
    :capacity (intCast sequence-capacity)
    :state_bytes (if (== sequence-memory null) 0 sequence-total-bytes)
    :positions sequence-positions}))

(a/defn sequence-mamba-state [:optional [:c-pointer :f32]]
  [[racer :usize]
   [layer :usize]]
  (if (or (== sequence-memory null)
          (>= racer sequence-racer-count)
          (>= layer model-layer-count)
          (attention-layer? layer))
    null
    (+ (a/unwrap sequence-memory)
       (* racer model-mamba-layer-count model-mamba-recurrent-size)
       (* (- layer (attention-layers-before layer))
          model-mamba-recurrent-size))))

(a/defn sequence-conv-state [:optional [:c-pointer :f32]]
  [[racer :usize]
   [layer :usize]]
  (if (or (== sequence-memory null)
          (>= racer sequence-racer-count)
          (>= layer model-layer-count)
          (attention-layer? layer))
    null
    (+ (a/unwrap sequence-memory)
       sequence-mamba-floats
       (* racer model-mamba-layer-count model-mamba-conv-state-size)
       (* (- layer (attention-layers-before layer))
          model-mamba-conv-state-size))))

(a/defn sequence-key-cache [:optional [:c-pointer :f32]]
  [[racer :usize]
   [layer :usize]]
  (let [slot (attention-layer-slot layer)]
    (if (or (== sequence-memory null)
            (>= racer sequence-racer-count)
            (>= slot model-attention-layer-count))
      null
      (+ (a/unwrap sequence-memory)
         sequence-mamba-floats sequence-conv-floats
         (* racer model-attention-layer-count sequence-capacity
            model-attention-kv-size)
         (* slot sequence-capacity model-attention-kv-size)))))

(a/defn sequence-value-cache [:optional [:c-pointer :f32]]
  [[racer :usize]
   [layer :usize]]
  (let [slot (attention-layer-slot layer)]
    (if (or (== sequence-memory null)
            (>= racer sequence-racer-count)
            (>= slot model-attention-layer-count))
      null
      (+ (a/unwrap sequence-memory)
         sequence-mamba-floats sequence-conv-floats sequence-kv-floats
         (* racer model-attention-layer-count sequence-capacity
            model-attention-kv-size)
         (* slot sequence-capacity model-attention-kv-size)))))

(a/defn layer-ffn! :bool
  "Execute one Granite SwiGLU FFN and install its scaled residual."
  [[layer :usize]
   [hidden [:c-pointer :f32]]
   [normalized [:c-pointer :f32]]
   [gate [:c-pointer :f32]]
   [up [:c-pointer :f32]]
   [activated [:c-pointer :f32]]
   [output [:c-pointer :f32]]]
  (let [norm-index (layer-ffn-norm-index layer)
        gate-index (layer-ffn-gate-index layer)
        up-index (layer-ffn-up-index layer)
        down-index (layer-ffn-down-index layer)
        ^:var valid (bool (! (or (== norm-index tensor-not-found)
                                 (== gate-index tensor-not-found)
                                 (== up-index tensor-not-found)
                                 (== down-index tensor-not-found))))]
    (when valid
      (= valid
         (tensor-rms-norm! normalized hidden norm-index
                           model-hidden-size model-rms-epsilon)))
    (when valid
      (= valid (tensor-matvec! gate model-ffn-size gate-index normalized)))
    (when valid
      (= valid (tensor-matvec! up model-ffn-size up-index normalized)))
    (when valid
      (dotimes [index model-ffn-size]
        (= (a/index activated index)
           (* (silu (a/index gate index)) (a/index up index))))
      (= valid
         (tensor-matvec! output model-hidden-size down-index activated)))
    (when valid
      (dotimes [index model-hidden-size]
        (= (a/index hidden index)
           (+ (a/index hidden index)
              (* model-residual-multiplier (a/index output index))))))
    valid))

(a/defn mamba-layer-step! :bool
  "Execute one streaming Granite Mamba-2 layer and install its scaled residual.
  Recurrent and convolution state belong to exactly one racer and one layer."
  [[layer :usize]
   [hidden [:c-pointer :f32]]
   [recurrent-state [:c-pointer :f32]]
   [conv-state [:c-pointer :f32]]
   [normalized [:c-pointer :f32]]
   [projected [:c-pointer :f32]]
   [convolved [:c-pointer :f32]]
   [scan-output [:c-pointer :f32]]
   [gated-output [:c-pointer :f32]]
   [branch-output [:c-pointer :f32]]]
  (let [base (layer-base-index layer)
        norm-index base
        a-index (+ base 5)
        conv-bias-index (+ base 6)
        conv-weight-index (+ base 7)
        d-index (+ base 8)
        dt-bias-index (+ base 9)
        in-index (+ base 10)
        mamba-norm-index (+ base 11)
        out-index (+ base 12)
        ^:var a (as (std-mem/zeroes (a/type [:array model-max-mamba-head-count :f32])) [:array model-max-mamba-head-count :f32])
        ^:var d (as (std-mem/zeroes (a/type [:array model-max-mamba-head-count :f32])) [:array model-max-mamba-head-count :f32])
        ^:var dt-bias (as (std-mem/zeroes (a/type [:array model-max-mamba-head-count :f32])) [:array model-max-mamba-head-count :f32])
        ^:var valid (bool (and (< layer model-layer-count)
                               (! (attention-layer? layer))
                               (!= base tensor-not-found)))]
    (when valid
      (= valid
         (tensor-rms-norm! normalized hidden norm-index
                           model-hidden-size model-rms-epsilon)))
    (when valid
      (= valid
         (tensor-matvec! projected model-mamba-projection-size
                         in-index normalized)))
    (when valid
      (dotimes [channel model-mamba-conv-size]
        (let [state-start (* channel 3)
              weight-start (* channel 4)
              current (a/index projected (+ model-mamba-inner-size channel))
              ^:var total (f32 (tensor-element conv-bias-index channel))]
          (dotimes [tap 3]
            (= total
               (+ total
                  (* (a/index conv-state (+ state-start tap))
                     (tensor-element conv-weight-index
                                     (+ weight-start tap))))))
          (= total
             (+ total
                (* current
                   (tensor-element conv-weight-index (+ weight-start 3)))))
          (= (a/index conv-state state-start)
             (a/index conv-state (+ state-start 1)))
          (= (a/index conv-state (+ state-start 1))
             (a/index conv-state (+ state-start 2)))
          (= (a/index conv-state (+ state-start 2)) current)
          (= (a/index convolved channel) (silu total))))
      (dotimes [head model-mamba-head-count]
        ;; GGUF conversion already transforms HF A_log into -exp(A_log).
        ;; Applying that transform twice changes the recurrent dynamics.
        (= (a/index a head)
           (tensor-element a-index head))
        (= (a/index d head) (tensor-element d-index head))
        (= (a/index dt-bias head) (tensor-element dt-bias-index head)))
      (mamba-selective-step!
       scan-output recurrent-state convolved
       (+ projected
          (* 2 model-mamba-inner-size)
          (* 2 model-mamba-state-size))
       (ak/& (a/index a 0))
       (+ convolved model-mamba-inner-size)
       (+ convolved model-mamba-inner-size model-mamba-state-size)
       (ak/& (a/index d 0))
       (ak/& (a/index dt-bias 0))
       model-mamba-head-count model-mamba-head-size
       model-mamba-state-size)
      (= valid
         (tensor-rms-norm-gated!
          gated-output scan-output projected mamba-norm-index
          model-mamba-inner-size model-rms-epsilon)))
    (when valid
      (= valid
         (tensor-matvec! branch-output model-hidden-size out-index
                         gated-output)))
    (when valid
      (dotimes [index model-hidden-size]
        (= (a/index hidden index)
           (+ (a/index hidden index)
              (* model-residual-multiplier
                 (a/index branch-output index))))))
    valid))

(a/defn mamba-ffn-layer! :bool
  "Execute a complete recurrent Granite block for one token."
  [[layer :usize]
   [hidden [:c-pointer :f32]]
   [recurrent-state [:c-pointer :f32]]
   [conv-state [:c-pointer :f32]]
   [normalized [:c-pointer :f32]]
   [projected [:c-pointer :f32]]
   [convolved [:c-pointer :f32]]
   [scan-output [:c-pointer :f32]]
   [gated-output [:c-pointer :f32]]
   [branch-output [:c-pointer :f32]]
   [ffn-gate [:c-pointer :f32]]
   [ffn-up [:c-pointer :f32]]
   [ffn-activated [:c-pointer :f32]]
   [ffn-output [:c-pointer :f32]]]
  (and (mamba-layer-step! layer hidden recurrent-state conv-state normalized
                          projected convolved scan-output gated-output
                          branch-output)
       (layer-ffn! layer hidden normalized ffn-gate ffn-up ffn-activated
                   ffn-output)))

(a/defn attention-layer-step! :bool
  "Execute one causal NoPE grouped-query-attention layer for a single token."
  [[layer :usize]
   [position :usize]
   [hidden [:c-pointer :f32]]
   [key-cache [:c-pointer :f32]]
   [value-cache [:c-pointer :f32]]
   [normalized [:c-pointer :f32]]
   [query [:c-pointer :f32]]
   [key [:c-pointer :f32]]
   [value [:c-pointer :f32]]
   [scores [:c-pointer :f32]]
   [attention-output [:c-pointer :f32]]
   [branch-output [:c-pointer :f32]]]
  (let [base (layer-base-index layer)
        key-index base
        norm-index (+ base 1)
        out-index (+ base 2)
        query-index (+ base 3)
        value-index (+ base 4)
        ^:var valid (bool (and (< position sequence-capacity)
                               (attention-layer? layer)
                               (!= base tensor-not-found)))]
    (when valid
      (= valid
         (tensor-rms-norm! normalized hidden norm-index
                           model-hidden-size model-rms-epsilon)))
    (when valid
      (= valid
         (tensor-matvec! query model-hidden-size query-index normalized)))
    (when valid
      (= valid
         (tensor-matvec! key model-attention-kv-size key-index normalized)))
    (when valid
      (= valid
         (tensor-matvec! value model-attention-kv-size value-index normalized)))
    (when valid
      (dotimes [component model-attention-kv-size]
        (= (a/index key-cache
                    (+ (* position model-attention-kv-size) component))
           (a/index key component))
        (= (a/index value-cache
                    (+ (* position model-attention-kv-size) component))
           (a/index value component)))
      (dotimes [query-head model-attention-head-count]
        (let [kv-head
              (/ query-head
                 (/ model-attention-head-count
                    model-attention-kv-head-count))
              query-start (* query-head model-attention-head-size)
              kv-start (* kv-head model-attention-head-size)]
          (dotimes [token (+ position 1)]
            (let [cache-start (+ (* token model-attention-kv-size) kv-start)
                  ^:var score (f32 0.0)]
              (dotimes [component model-attention-head-size]
                (= score
                   (+ score
                      (* (a/index query (+ query-start component))
                         (a/index key-cache
                                  (+ cache-start component))))))
              (= (a/index scores token) (* score model-attention-scale))))
          (softmax! scores (+ position 1))
          (dotimes [component model-attention-head-size]
            (let [^:var total (f32 0.0)]
              (dotimes [token (+ position 1)]
                (= total
                   (+ total
                      (* (a/index scores token)
                         (a/index value-cache
                                  (+ (* token model-attention-kv-size)
                                     kv-start component))))))
              (= (a/index attention-output (+ query-start component))
                 total)))))
      (= valid
         (tensor-matvec! branch-output model-hidden-size out-index
                         attention-output)))
    (when valid
      (dotimes [index model-hidden-size]
        (= (a/index hidden index)
           (+ (a/index hidden index)
              (* model-residual-multiplier
                 (a/index branch-output index))))))
    valid))

(a/defn attention-ffn-layer! :bool
  "Execute a complete causal attention Granite block for one token."
  [[layer :usize]
   [position :usize]
   [hidden [:c-pointer :f32]]
   [key-cache [:c-pointer :f32]]
   [value-cache [:c-pointer :f32]]
   [normalized [:c-pointer :f32]]
   [query [:c-pointer :f32]]
   [key [:c-pointer :f32]]
   [value [:c-pointer :f32]]
   [scores [:c-pointer :f32]]
   [attention-output [:c-pointer :f32]]
   [branch-output [:c-pointer :f32]]
   [ffn-gate [:c-pointer :f32]]
   [ffn-up [:c-pointer :f32]]
   [ffn-activated [:c-pointer :f32]]
   [ffn-output [:c-pointer :f32]]]
  (and (attention-layer-step!
        layer position hidden key-cache value-cache normalized query key value
        scores attention-output branch-output)
       (layer-ffn! layer hidden normalized ffn-gate ffn-up ffn-activated
                   ffn-output)))

(a/defn attention-layer-probe :f32
  "350M-only diagnostic: isolated attention+FFN with a single-token cache.
  Unsupported profiles/tokens return NaN before accessing fixed-size buffers."
  [[layer :usize]
   [token :usize]
   [component :usize]]
  (when (or (!= model-profile-id model-profile-granite-h-350m)
            (>= token model-vocabulary-size))
    (return (std-math/nan :f32)))
  (if (or (! (attention-layer? layer))
          (>= component model-hidden-size))
    0.0
    (let [^:var hidden (as (std-mem/zeroes (a/type [:array 768 :f32])) [:array 768 :f32])
          ^:var normalized (as (std-mem/zeroes (a/type [:array 768 :f32])) [:array 768 :f32])
          ^:var query (as (std-mem/zeroes (a/type [:array 768 :f32])) [:array 768 :f32])
          ^:var key (as (std-mem/zeroes (a/type [:array 256 :f32])) [:array 256 :f32])
          ^:var value (as (std-mem/zeroes (a/type [:array 256 :f32])) [:array 256 :f32])
          ^:var scores (as (std-mem/zeroes (a/type [:array 160 :f32])) [:array 160 :f32])
          ^:var attention-output (as (std-mem/zeroes (a/type [:array 768 :f32])) [:array 768 :f32])
          ^:var branch-output (as (std-mem/zeroes (a/type [:array 768 :f32])) [:array 768 :f32])
          ^:var ffn-gate (as (std-mem/zeroes (a/type [:array 2048 :f32])) [:array 2048 :f32])
          ^:var ffn-up (as (std-mem/zeroes (a/type [:array 2048 :f32])) [:array 2048 :f32])
          ^:var ffn-activated (as (std-mem/zeroes (a/type [:array 2048 :f32])) [:array 2048 :f32])
          ^:var ffn-output (as (std-mem/zeroes (a/type [:array 768 :f32])) [:array 768 :f32])
          cache-elements (* sequence-capacity model-attention-kv-size)
          cache-allocation
          (runtime/malloc (* 2 cache-elements (sizeOf :f32)))]
      (if (== cache-allocation null)
        0.0
        (do
          (defer (runtime/free cache-allocation))
          (let [key-cache (a/cast cache-allocation [:c-pointer :f32])
                value-cache (+ key-cache cache-elements)]
            (dotimes [index (* 2 cache-elements)]
              (= (a/index key-cache index) 0.0))
            (dotimes [index model-hidden-size]
              (= (a/index hidden index)
                 (* 12.0 (embedding-value-kernel token index))))
            (if (attention-ffn-layer!
                 layer 0
                 (ak/& (a/index hidden 0)) key-cache value-cache
                 (ak/& (a/index normalized 0))
                 (ak/& (a/index query 0))
                 (ak/& (a/index key 0))
                 (ak/& (a/index value 0))
                 (ak/& (a/index scores 0))
                 (ak/& (a/index attention-output 0))
                 (ak/& (a/index branch-output 0))
                 (ak/& (a/index ffn-gate 0))
                 (ak/& (a/index ffn-up 0))
                 (ak/& (a/index ffn-activated 0))
                 (ak/& (a/index ffn-output 0)))
              (a/index hidden component)
              0.0)))))))

(a/defn mamba-layer-zero-probe :f32
  "350M-only diagnostic: first-token Mamba branch with zero initial state.
  Unsupported profiles/tokens return NaN before accessing fixed-size buffers."
  [[token :usize]
   [component :usize]]
  (when (or (!= model-profile-id model-profile-granite-h-350m)
            (>= token model-vocabulary-size))
    (return (std-math/nan :f32)))
  (let [^:var residual (as (std-mem/zeroes (a/type [:array 768 :f32])) [:array 768 :f32])
        ^:var normalized (as (std-mem/zeroes (a/type [:array 768 :f32])) [:array 768 :f32])
        ^:var projected (as (std-mem/zeroes (a/type [:array 3376 :f32])) [:array 3376 :f32])
        ^:var convolved (as (std-mem/zeroes (a/type [:array 1792 :f32])) [:array 1792 :f32])
        ^:var scan-output (as (std-mem/zeroes (a/type [:array 1536 :f32])) [:array 1536 :f32])
        ^:var gated-output (as (std-mem/zeroes (a/type [:array 1536 :f32])) [:array 1536 :f32])
        ^:var branch-output (as (std-mem/zeroes (a/type [:array 768 :f32])) [:array 768 :f32])
        ^:var a (as (std-mem/zeroes (a/type [:array 48 :f32])) [:array 48 :f32])
        ^:var d (as (std-mem/zeroes (a/type [:array 48 :f32])) [:array 48 :f32])
        ^:var dt-bias (as (std-mem/zeroes (a/type [:array 48 :f32])) [:array 48 :f32])
        norm-index (find-tensor "blk.0.attn_norm.weight")
        in-index (find-tensor "blk.0.ssm_in.weight")
        conv-weight-index (find-tensor "blk.0.ssm_conv1d.weight")
        conv-bias-index (find-tensor "blk.0.ssm_conv1d.bias")
        a-index (find-tensor "blk.0.ssm_a")
        d-index (find-tensor "blk.0.ssm_d")
        dt-bias-index (find-tensor "blk.0.ssm_dt.bias")
        mamba-norm-index (find-tensor "blk.0.ssm_norm.weight")
        out-index (find-tensor "blk.0.ssm_out.weight")]
    (if (or (>= component 768)
            (== norm-index tensor-not-found)
            (== in-index tensor-not-found)
            (== conv-weight-index tensor-not-found)
            (== conv-bias-index tensor-not-found)
            (== a-index tensor-not-found)
            (== d-index tensor-not-found)
            (== dt-bias-index tensor-not-found)
            (== mamba-norm-index tensor-not-found)
            (== out-index tensor-not-found))
      0.0
      (let [state-allocation (runtime/malloc (* 196608 (sizeOf :f32)))]
        (if (== state-allocation null)
          0.0
          (do
            (defer (runtime/free state-allocation))
            (let [recurrent-state (a/cast state-allocation [:c-pointer :f32])]
              (dotimes [index 196608]
                (= (a/index recurrent-state index) 0.0))
              (dotimes [index 768]
                (= (a/index residual index)
                   (* 12.0 (embedding-value-kernel token index))))
              (= :_ (tensor-rms-norm!
                     (ak/& (a/index normalized 0))
                     (ak/& (a/index residual 0)) norm-index 768 0.00001))
              (= :_ (tensor-matvec!
                     (ak/& (a/index projected 0)) 3376 in-index
                     (ak/& (a/index normalized 0))))
              (dotimes [channel 1792]
                (= (a/index convolved channel)
                   (silu (+ (* (a/index projected (+ 1536 channel))
                               (tensor-element conv-weight-index
                                               (+ (* channel 4) 3)))
                            (tensor-element conv-bias-index channel)))))
              (dotimes [head 48]
                (= (a/index a head)
                   (tensor-element a-index head))
                (= (a/index d head) (tensor-element d-index head))
                (= (a/index dt-bias head)
                   (tensor-element dt-bias-index head)))
              (mamba-selective-step!
               (ak/& (a/index scan-output 0))
               recurrent-state
               (ak/& (a/index convolved 0))
               (ak/& (a/index projected 3328))
               (ak/& (a/index a 0))
               (ak/& (a/index convolved 1536))
               (ak/& (a/index convolved 1664))
               (ak/& (a/index d 0))
               (ak/& (a/index dt-bias 0))
               48 32 128)
              (= :_ (tensor-rms-norm-gated!
                     (ak/& (a/index gated-output 0))
                     (ak/& (a/index scan-output 0))
                     (ak/& (a/index projected 0))
                     mamba-norm-index 1536 0.00001))
              (= :_ (tensor-matvec!
                     (ak/& (a/index branch-output 0)) 768 out-index
                     (ak/& (a/index gated-output 0))))
              (+ (a/index residual component)
                 (* 0.246 (a/index branch-output component))))))))))

(a/defn mamba-layer-zero-full-probe :f32
  "350M-only diagnostic: first-token complete Mamba/FFN block.
  Unsupported profiles/tokens return NaN before accessing fixed-size buffers."
  [[token :usize]
   [component :usize]]
  (when (or (!= model-profile-id model-profile-granite-h-350m)
            (>= token model-vocabulary-size))
    (return (std-math/nan :f32)))
  (if (>= component model-hidden-size)
    0.0
    (let [^:var hidden (as (std-mem/zeroes (a/type [:array 768 :f32])) [:array 768 :f32])
          ^:var normalized (as (std-mem/zeroes (a/type [:array 768 :f32])) [:array 768 :f32])
          ^:var projected (as (std-mem/zeroes (a/type [:array 3376 :f32])) [:array 3376 :f32])
          ^:var convolved (as (std-mem/zeroes (a/type [:array 1792 :f32])) [:array 1792 :f32])
          ^:var scan-output (as (std-mem/zeroes (a/type [:array 1536 :f32])) [:array 1536 :f32])
          ^:var gated-output (as (std-mem/zeroes (a/type [:array 1536 :f32])) [:array 1536 :f32])
          ^:var branch-output (as (std-mem/zeroes (a/type [:array 768 :f32])) [:array 768 :f32])
          ^:var ffn-gate (as (std-mem/zeroes (a/type [:array 2048 :f32])) [:array 2048 :f32])
          ^:var ffn-up (as (std-mem/zeroes (a/type [:array 2048 :f32])) [:array 2048 :f32])
          ^:var ffn-activated (as (std-mem/zeroes (a/type [:array 2048 :f32])) [:array 2048 :f32])
          ^:var ffn-output (as (std-mem/zeroes (a/type [:array 768 :f32])) [:array 768 :f32])
          ^:var conv-state (as (std-mem/zeroes (a/type [:array 5376 :f32])) [:array 5376 :f32])
          state-allocation
          (runtime/malloc (* model-mamba-recurrent-size (sizeOf :f32)))]
      (if (== state-allocation null)
        0.0
        (do
          (defer (runtime/free state-allocation))
          (let [recurrent-state (a/cast state-allocation [:c-pointer :f32])]
            (dotimes [index model-mamba-recurrent-size]
              (= (a/index recurrent-state index) 0.0))
            (dotimes [index model-hidden-size]
              (= (a/index hidden index)
                 (* 12.0 (embedding-value-kernel token index))))
            (if (mamba-ffn-layer!
                 0
                 (ak/& (a/index hidden 0))
                 recurrent-state
                 (ak/& (a/index conv-state 0))
                 (ak/& (a/index normalized 0))
                 (ak/& (a/index projected 0))
                 (ak/& (a/index convolved 0))
                 (ak/& (a/index scan-output 0))
                 (ak/& (a/index gated-output 0))
                 (ak/& (a/index branch-output 0))
                 (ak/& (a/index ffn-gate 0))
                 (ak/& (a/index ffn-up 0))
                 (ak/& (a/index ffn-activated 0))
                 (ak/& (a/index ffn-output 0)))
              (a/index hidden component)
              0.0)))))))

(a/defn- q6-k-value :f32
  "Decode one value from a GGML Q6_K block without allocating."
  [[block [:* {:size :c :const? true} :u8]]
   [index :usize]]
  (if (>= index 256)
    0.0
    (let [sub-block (/ index 32)
          within (mod index 32)
          low-index (+ (* (/ sub-block 4) 64)
                       (* (mod sub-block 2) 32)
                       within)
          low-shift (u3 (if (>= (mod sub-block 4) 2) 4 0))
          high-index (+ 128 (* (/ sub-block 4) 32) within)
          high-shift (u3 (intCast (* (mod sub-block 4) 2)))
          low (ak/& (>> (a/index block low-index) low-shift) 15)
          high (ak/& (>> (a/index block high-index) high-shift) 3)
          quantized (- (as (+ low (* high 16)) :i16) 32)
          scale-byte (a/index block (+ 192 (/ index 16)))
          scale (as (bitCast scale-byte) :i8)
          delta-bits (+ (as (a/index block 208) :u16)
                        (* (as (a/index block 209) :u16) 256))
          delta (as (floatCast (as (bitCast delta-bits) :f16))
                    :f32)]
      (* delta
         (as (floatFromInt scale) :f32)
         (as (floatFromInt quantized) :f32)))))

(a/defn- q6-k-dot :f32
  "SIMD dot product for one GGML Q6_K block, sixteen values per signed
  subscale. Decode each packed plane once; preserve q6-k-value's weight layout."
  [[block [:* {:size :c :const? true} :u8]]
   [input [:* {:size :c :const? true} :f32]]]
  (let [delta-bits (+ (as (a/index block 208) :u16)
                      (* (as (a/index block 209) :u16) 256))
        delta (as (floatCast (as (bitCast delta-bits) :f16)) :f32)
        ^:var total (as (splat (as 0.0 :f32)) [:vector 16 :f32])]
    (dotimes [group 16]
      (let [sub-block (/ group 2)
            within (* (mod group 2) 16)
            low-index (+ (* (/ sub-block 4) 64) (* (mod sub-block 2) 32) within)
            high-index (+ 128 (* (/ sub-block 4) 32) within)
            low-shift (u3 (if (>= (mod sub-block 4) 2) 4 0))
            high-shift (u3 (intCast (* (mod sub-block 4) 2)))
            low-packed (as (bitCast (a/deref (a/cast (+ block low-index)
                                                     [:* {:const? true} [:array 16 :u8]]))) [:vector 16 :u8])
            high-packed (as (bitCast (a/deref (a/cast (+ block high-index)
                                                      [:* {:const? true} [:array 16 :u8]]))) [:vector 16 :u8])
            low (ak/& (>> low-packed (as (splat low-shift) (a/type [:vector 16 :u3])))
                      (as (splat 15) (a/type [:vector 16 :u8])))
            high (ak/& (>> high-packed (as (splat high-shift) (a/type [:vector 16 :u3])))
                       (as (splat 3) (a/type [:vector 16 :u8])))
            quantized (- (as (intCast (+ low (* high (as (splat 16) (a/type [:vector 16 :u8])))))
                             (a/type [:vector 16 :i16]))
                         (as (splat 32) (a/type [:vector 16 :i16])))
            scale (as (bitCast (a/index block (+ 192 group))) :i8)
            scaled-delta (as (splat (* delta (as (floatFromInt scale) :f32)))
                             (a/type [:vector 16 :f32]))
            values (* scaled-delta (as (floatFromInt quantized) (a/type [:vector 16 :f32])))
            inputs (as (bitCast (a/deref (a/cast (+ input (* group 16))
                                                 [:* {:const? true} [:array 16 :f32]]))) [:vector 16 :f32])]
        (= total (+ total (* values inputs)))))
    (reduce :Add total)))

(a/defn- embedding-value-kernel :f32
  "Read the active profile's embedding width and quantization, not a fixed
  350M row stride. The supported GGUF catalog places embeddings at index1."
  [[token :usize]
   [component :usize]]
  (if (or (== (a/field model-summary valid) false)
          (< tensor-catalog-count 2)
          (>= token model-vocabulary-size)
          (>= component model-hidden-size))
    0.0
    (tensor-element 1 (+ (* token model-hidden-size) component))))

(a/defn embedding-value :f32
  "Inspectable wrapper around the private hot-loop embedding decoder."
  [[token :usize]
   [component :usize]]
  (embedding-value-kernel token component))

(a/defn embedding-row-dot :f32
  "Dot one tied Q6_K token-embedding row with a normalized hidden vector."
  [[token :usize]
   [input [:* {:size :c :const? true} :f32]]]
  (if (or (== (a/field model-summary valid) false)
          (< tensor-catalog-count 2)
          (>= token 100352))
    0.0
    (let [tensor (a/index tensor-catalog 1)
          bytes (as (ptrFromInt (a/field tensor data_address)) [:c-pointer :u8])
          row-offset (* token 630)
          ^:var total (f32 0.0)]
      (dotimes [block 3]
        (= total
           (+ total
              (q6-k-dot (+ bytes row-offset (* block 210))
                        (+ input (* block 256))))))
      total)))

(a/defn- tensor-element :f32
  "Decode one logical flat tensor element for every supported model layout."
  [[tensor-index :usize]
   [element-index :usize]]
  (if (or (>= tensor-index tensor-catalog-count)
          (== (a/field model-summary valid) false))
    0.0
    (let [tensor (a/index tensor-catalog tensor-index)
          bytes (as (ptrFromInt (a/field tensor data_address)) [:c-pointer :u8])]
      (cond
        (== (a/field tensor ggml_type) 0)
        (let [values (as (ptrFromInt (a/field tensor data_address)) [:c-pointer :f32])]
          (a/index values element-index))

        (== (a/field tensor ggml_type) 2)
        (q4-0-value (+ bytes (* (/ element-index 32) 18))
                    (mod element-index 32))

        (== (a/field tensor ggml_type) 14)
        (q6-k-value (+ bytes (* (/ element-index 256) 210))
                    (mod element-index 256))

        :else 0.0))))

(a/defn- tensor-row-dot-kernel :f32
  "Allocation-free scalar reference matvec row for F32, Q4_0, or Q6_K."
  [[tensor-index :usize]
   [row :usize]
   [input [:* {:size :c :const? true} :f32]]]
  (if (or (>= tensor-index tensor-catalog-count)
          (== (a/field model-summary valid) false))
    0.0
    (let [tensor (a/index tensor-catalog tensor-index)
          input-size (as (intCast (a/index (a/field tensor dimensions) 0))
                         :usize)
          output-size (if (> (a/field tensor dimension_count) 1)
                        (as (intCast
                             (a/index (a/field tensor dimensions) 1))
                            :usize)
                        1)
          bytes (as (ptrFromInt (a/field tensor data_address)) [:c-pointer :u8])
          ^:var total (f32 0.0)]
      (if (>= row output-size)
        0.0
        (do
          (cond
            (== (a/field tensor ggml_type) 0)
            (let [values (as (ptrFromInt (a/field tensor data_address)) [:c-pointer :f32])
                  row-start (* row input-size)]
              (dotimes [index input-size]
                (= total (+ total
                            (* (a/index values (+ row-start index))
                               (a/index input index))))))

            (== (a/field tensor ggml_type) 2)
            (let [block-count (/ input-size 32)
                  row-start (* row block-count 18)]
              (dotimes [block-index block-count]
                (= total (+ total
                            (q4-0-dot (+ bytes row-start (* block-index 18))
                                      (+ input (* block-index 32)))))))

            (== (a/field tensor ggml_type) 14)
            (let [block-count (/ input-size 256)
                  row-start (* row block-count 210)]
              (dotimes [block-index block-count]
                (= total (+ total
                            (q6-k-dot (+ bytes row-start (* block-index 210))
                                      (+ input (* block-index 256))))))))
          total)))))

(a/defn tensor-row-dot :f32
  "Inspectable wrapper around the private matrix-row hot loop."
  [[tensor-index :usize]
   [row :usize]
   [input [:* {:size :c :const? true} :f32]]]
  (tensor-row-dot-kernel tensor-index row input))

(a/defn tensor-matvec! :bool
  "Apply one supported two-dimensional tensor to a dense input vector."
  [[output [:c-pointer :f32]]
   [output-count :usize]
   [tensor-index :usize]
   [input [:* {:size :c :const? true} :f32]]]
  (if (or (>= tensor-index tensor-catalog-count)
          (< (a/field (a/index tensor-catalog tensor-index) dimension_count) 2)
          (!= output-count
              (as (intCast
                   (a/index
                    (a/field (a/index tensor-catalog tensor-index) dimensions)
                    1))
                  :usize)))
    false
    (do
      (dotimes [row output-count]
        (= (a/index output row)
           (tensor-row-dot-kernel tensor-index row input)))
      true)))

(a/defn tensor-rms-norm! :bool
  "RMS-normalize through a named F32 model weight vector."
  [[output [:c-pointer :f32]]
   [input [:* {:size :c :const? true} :f32]]
   [weights-index :usize]
   [length :usize]
   [epsilon :f32]]
  (if (or (>= weights-index tensor-catalog-count)
          (!= (a/field (a/index tensor-catalog weights-index) ggml_type) 0)
          (!= (a/index
               (a/field (a/index tensor-catalog weights-index) dimensions) 0)
              length))
    false
    (let [^:var square-sum (f32 0.0)]
      (dotimes [index length]
        (= square-sum (+ square-sum
                         (* (a/index input index)
                            (a/index input index)))))
      (let [inverse-rms (/ 1.0
                           (std-math/sqrt
                            (+ (/ square-sum
                                  (as (floatFromInt length) :f32))
                               epsilon)))]
        (dotimes [index length]
          (= (a/index output index)
             (* (a/index input index)
                inverse-rms
                (tensor-element weights-index index)))))
      true)))

(a/defn- silu :f32
  [[value :f32]]
  (/ value (+ 1.0 (std-math/exp (- 0.0 value)))))

(a/defn layer-zero-mlp-probe :f32
  "350M-only diagnostic: first dense FFN on a real token embedding.
  Unsupported profiles/tokens return NaN before accessing fixed-size buffers."
  [[token :usize]]
  (when (or (!= model-profile-id model-profile-granite-h-350m)
            (>= token model-vocabulary-size))
    (return (std-math/nan :f32)))
  (let [^:var hidden (as (std-mem/zeroes (a/type [:array 768 :f32])) [:array 768 :f32])
        ^:var normalized (as (std-mem/zeroes (a/type [:array 768 :f32])) [:array 768 :f32])
        ^:var gate (as (std-mem/zeroes (a/type [:array 2048 :f32])) [:array 2048 :f32])
        ^:var up (as (std-mem/zeroes (a/type [:array 2048 :f32])) [:array 2048 :f32])
        ^:var activated (as (std-mem/zeroes (a/type [:array 2048 :f32])) [:array 2048 :f32])
        ^:var output (as (std-mem/zeroes (a/type [:array 768 :f32])) [:array 768 :f32])
        norm-index (find-tensor "blk.0.ffn_norm.weight")
        gate-index (find-tensor "blk.0.ffn_gate.weight")
        up-index (find-tensor "blk.0.ffn_up.weight")
        down-index (find-tensor "blk.0.ffn_down.weight")]
    (dotimes [index 768]
      (= (a/index hidden index)
         (* 12.0 (embedding-value-kernel token index))))
    (if (or (== norm-index tensor-not-found)
            (== gate-index tensor-not-found)
            (== up-index tensor-not-found)
            (== down-index tensor-not-found))
      0.0
      (do
        (= :_ (tensor-rms-norm! (ak/& (a/index normalized 0))
                                (ak/& (a/index hidden 0))
                                norm-index 768 0.00001))
        (= :_ (tensor-matvec! (ak/& (a/index gate 0)) 2048 gate-index
                              (ak/& (a/index normalized 0))))
        (= :_ (tensor-matvec! (ak/& (a/index up 0)) 2048 up-index
                              (ak/& (a/index normalized 0))))
        (dotimes [index 2048]
          (= (a/index activated index)
             (* (silu (a/index gate index)) (a/index up index))))
        (= :_ (tensor-matvec! (ak/& (a/index output 0)) 768 down-index
                              (ak/& (a/index activated 0))))
        (a/index output 0)))))

(a/defn action-head-logit :f32
  "Evaluate one racing action row over Granite's normalized final state."
  [[action :usize]
   [hidden [:* {:size :c :const? true} :f32]]]
  (if (or (! (a/field action-head-summary valid))
          (>= action action-head-output-count))
    0.0
    (let [row-start (* action action-head-input-count)
          ^:var total (f32 (a/index action-head-biases action))]
      (dotimes [index action-head-input-count]
        (= total
           (+ total
              (* (a/index action-head-weights (+ row-start index))
                 (a/index hidden index)))))
      total)))

(a/defn team-head-logit :f32
  "Evaluate one independent team-strategy row over Granite's prompt state."
  [[action :usize]
   [hidden [:* {:size :c :const? true} :f32]]]
  (if (or (! (a/field team-head-summary valid))
          (>= action team-head-output-count))
    0.0
    (let [row-start (* action action-head-input-count)
          ^:var total (f32 (a/index team-head-biases action))]
      (dotimes [index action-head-input-count]
        (= total
           (+ total
              (* (a/index team-head-weights (+ row-start index))
                 (a/index hidden index)))))
      total)))

(a/defn forward-token! ForwardReport
  "Run one token through the active model's layers for an independent sequence.
  The vocabulary sentinel consumes one position-bound fused observation vector
  prepared by `forward-fused-observation!`; ordinary token calls are unchanged."
  [[racer :usize]
   [token :usize]]
  (let [^:var hidden (as (std-mem/zeroes (a/type [:array model-max-hidden-size :f32])) [:array model-max-hidden-size :f32])
        ^:var normalized (as (std-mem/zeroes (a/type [:array model-max-hidden-size :f32])) [:array model-max-hidden-size :f32])
        ^:var projected (as (std-mem/zeroes (a/type [:array model-max-mamba-projection-size :f32])) [:array model-max-mamba-projection-size :f32])
        ^:var convolved (as (std-mem/zeroes (a/type [:array model-max-mamba-conv-size :f32])) [:array model-max-mamba-conv-size :f32])
        ^:var scan-output (as (std-mem/zeroes (a/type [:array model-max-mamba-inner-size :f32])) [:array model-max-mamba-inner-size :f32])
        ^:var gated-output (as (std-mem/zeroes (a/type [:array model-max-mamba-inner-size :f32])) [:array model-max-mamba-inner-size :f32])
        ^:var branch-output (as (std-mem/zeroes (a/type [:array model-max-hidden-size :f32])) [:array model-max-hidden-size :f32])
        ^:var ffn-gate (as (std-mem/zeroes (a/type [:array model-max-ffn-size :f32])) [:array model-max-ffn-size :f32])
        ^:var ffn-up (as (std-mem/zeroes (a/type [:array model-max-ffn-size :f32])) [:array model-max-ffn-size :f32])
        ^:var ffn-activated (as (std-mem/zeroes (a/type [:array model-max-ffn-size :f32])) [:array model-max-ffn-size :f32])
        ^:var ffn-output (as (std-mem/zeroes (a/type [:array model-max-hidden-size :f32])) [:array model-max-hidden-size :f32])
        ^:var query (as (std-mem/zeroes (a/type [:array model-max-hidden-size :f32])) [:array model-max-hidden-size :f32])
        ^:var key (as (std-mem/zeroes (a/type [:array model-max-attention-kv-size :f32])) [:array model-max-attention-kv-size :f32])
        ^:var value (as (std-mem/zeroes (a/type [:array model-max-attention-kv-size :f32])) [:array model-max-attention-kv-size :f32])
        ^:var scores (as (std-mem/zeroes (a/type [:array sequence-capacity :f32])) [:array sequence-capacity :f32])
        ^:var attention-output (as (std-mem/zeroes (a/type [:array model-max-hidden-size :f32])) [:array model-max-hidden-size :f32])
        candidate-tokens
        (a/init [32 33 34 35 36 37 38 39] [:array 8 :u32])
        ^:var candidate-logits (as (std-mem/zeroes (a/type [:array 8 :f32])) [:array 8 :f32])
        position (usize (if (< racer sequence-racer-count)
                          (as (a/index sequence-positions racer) :usize)
                          sequence-capacity))
        ^:var valid (bool (and (a/field model-summary valid)
                               (!= sequence-memory null)
                               (< racer sequence-racer-count)
                               (< position sequence-capacity)
                               (<= token model-vocabulary-size)))
        ^:var checksum (f32 0.0)
        team-actor (>= racer protocol/racer-count)
        candidate-count (if team-actor team-head-output-count
                            action-head-output-count)
        ^:var best-token (u32 32)
        ^:var best-logit (f32 -3.4e38)]
    (when valid
      (dotimes [index model-hidden-size]
        (= (a/index hidden index)
           (if (== token model-vocabulary-size)
             (a/index fused-observation-inputs
                      (+ (* racer model-hidden-size) index))
             (* 12.0 (embedding-value-kernel token index)))))
      (dotimes [layer model-layer-count]
        (when valid
          (if (attention-layer? layer)
            (let [key-cache (sequence-key-cache racer layer)
                  value-cache (sequence-value-cache racer layer)]
              (if (or (== key-cache null) (== value-cache null))
                (= valid false)
                (= valid
                   (attention-ffn-layer!
                    layer position
                    (ak/& (a/index hidden 0))
                    (a/unwrap key-cache) (a/unwrap value-cache)
                    (ak/& (a/index normalized 0))
                    (ak/& (a/index query 0))
                    (ak/& (a/index key 0))
                    (ak/& (a/index value 0))
                    (ak/& (a/index scores 0))
                    (ak/& (a/index attention-output 0))
                    (ak/& (a/index branch-output 0))
                    (ak/& (a/index ffn-gate 0))
                    (ak/& (a/index ffn-up 0))
                    (ak/& (a/index ffn-activated 0))
                    (ak/& (a/index ffn-output 0))))))
            (let [recurrent-state (sequence-mamba-state racer layer)
                  conv-state (sequence-conv-state racer layer)]
              (if (or (== recurrent-state null) (== conv-state null))
                (= valid false)
                (= valid
                   (mamba-ffn-layer!
                    layer
                    (ak/& (a/index hidden 0))
                    (a/unwrap recurrent-state) (a/unwrap conv-state)
                    (ak/& (a/index normalized 0))
                    (ak/& (a/index projected 0))
                    (ak/& (a/index convolved 0))
                    (ak/& (a/index scan-output 0))
                    (ak/& (a/index gated-output 0))
                    (ak/& (a/index branch-output 0))
                    (ak/& (a/index ffn-gate 0))
                    (ak/& (a/index ffn-up 0))
                    (ak/& (a/index ffn-activated 0))
                    (ak/& (a/index ffn-output 0)))))))))
      (when valid
        (= valid
           (tensor-rms-norm! (ak/& (a/index normalized 0))
                             (ak/& (a/index hidden 0))
                             0 model-hidden-size model-rms-epsilon)))
      (when valid
        ;; Keep the final eight real sequential token states. Short prompts
        ;; fill from slot zero; longer readable prompts slide one state at a
        ;; time so offline training and live inference see the same suffix.
        (when (>= position action-head-token-count)
          (dotimes [slot (- action-head-token-count 1)]
            (dotimes [index model-hidden-size]
              (= (a/index action-head-inputs
                          (+ (* racer action-head-input-count)
                             (* slot model-hidden-size)
                             index))
                 (a/index action-head-inputs
                          (+ (* racer action-head-input-count)
                             (* (+ slot 1) model-hidden-size)
                             index))))))
        (dotimes [index model-hidden-size]
          (let [hidden-value (a/index normalized index)
                feature-slot (min position (- action-head-token-count 1))]
            (= checksum (+ checksum hidden-value))
            (= (a/index action-head-inputs
                        (+ (* racer action-head-input-count)
                           (* feature-slot model-hidden-size)
                           index))
               hidden-value)))
        (dotimes [candidate candidate-count]
          (let [candidate-token (a/index candidate-tokens candidate)
                logit
                (cond
                  (and team-actor (a/field team-head-summary valid))
                  (team-head-logit
                   candidate
                   (ak/& (a/index action-head-inputs
                                  (* racer action-head-input-count))))

                  (and (! team-actor)
                       (a/field action-head-summary valid))
                  (action-head-logit
                   candidate
                   (ak/& (a/index action-head-inputs
                                  (* racer action-head-input-count))))

                  :else
                  (/ (embedding-row-dot
                      (as candidate-token :usize)
                      (ak/& (a/index normalized 0)))
                     3.0))]
            (= (a/index candidate-logits candidate) logit)
            (when (> logit best-logit)
              (= best-logit logit)
              (= best-token candidate-token))))
        (= (a/index sequence-positions racer) (intCast (+ position 1)))))
    (ForwardReport
     {:valid valid
      :racer (intCast racer)
      :position (intCast position)
      :input_token (intCast token)
      :best_token best-token
      :best_logit best-logit
      :hidden_first (a/index normalized 0)
      :hidden_checksum checksum
      :candidate_count (intCast candidate-count)
      :candidate_tokens candidate-tokens
      :candidate_logits candidate-logits})))

(a/defn forward-fused-observation! ForwardReport
  "Encode every positional observation field into two full Granite steps.
  Category embeddings are bound to their field position by a deterministic
  sign vector, summed in two ordered groups at stable RMS scale, and processed
  by all 32 model layers. No field is dropped and no policy feature bypasses
  Granite."
  [[racer :usize]
   [bytes [:* {:size :c :const? true} :u8]]
   [length :usize]
   [reset :bool]]
  (let [tokenized (tokenize-compact-ascii bytes length)
        ^:var report (as (empty-forward-report) ForwardReport)
        ^:var valid (bool (and (a/field tokenized valid)
                               (! (a/field tokenized truncated))
                               (== (a/field tokenized token_count)
                                   action-head-token-count)
                               (< racer sequence-racer-count)))]
    (when (and valid reset)
      (= valid (reset-sequence! racer)))
    (when valid
      ;; Two ordered four-field groups preserve the complete observation while
      ;; reducing eight sequential model passes to two. Position binding keeps
      ;; equal category bytes in different fields distinguishable.
      (dotimes [group 2]
        (when valid
          (dotimes [dimension model-hidden-size]
            (let [^:var total (f32 0.0)]
              (dotimes [local-position 4]
                (let [position (+ (* group 4) local-position)
                      token
                      (as (a/index (a/field tokenized tokens) position)
                          :usize)
                      position-value
                      (embedding-value-kernel (+ 32 position) dimension)
                      position-sign (f32 (if (>= position-value 0.0) 1.0 -1.0))]
                  (= total
                     (+ total
                        (* position-sign
                           (embedding-value-kernel token dimension))))))
              (= (a/index fused-observation-inputs
                          (+ (* racer model-hidden-size) dimension))
                 (* total 6.0))))
          (= report (forward-token! racer model-vocabulary-size))
          (= valid (a/field report valid)))))
    (when (! valid)
      (= (a/field report valid) false))
    report))

(a/defn empty-forward-report ForwardReport
  []
  (ForwardReport
   {:valid false :racer 0 :position 0 :input_token 0
    :best_token 0 :best_logit 0.0 :hidden_first 0.0 :hidden_checksum 0.0
    :candidate_count 0
    :candidate_tokens (std-mem/zeroes (a/type [:array 8 :u32]))
    :candidate_logits (std-mem/zeroes (a/type [:array 8 :f32]))}))

(a/defn forward-compact-prompt! ForwardReport
  "Tokenize and run one bounded compact ASCII observation entirely in native
  code. Resetting is explicit so callers can retain or replace agent memory."
  [[racer :usize]
   [bytes [:* {:size :c :const? true} :u8]]
   [length :usize]
   [reset :bool]]
  (let [tokenized (tokenize-compact-ascii bytes length)
        ^:var report (as (empty-forward-report) ForwardReport)
        ^:var valid (bool (and (a/field tokenized valid)
                               (! (a/field tokenized truncated))
                               (> (a/field tokenized token_count) 0)))]
    (when (and valid reset)
      (= valid (reset-sequence! racer)))
    (when valid
      (dotimes [index (a/field tokenized token_count)]
        (when valid
          (= report
             (forward-token!
              racer
              (as (a/index (a/field tokenized tokens) index)
                  :usize)))
          (= valid (a/field report valid)))))
    (when (! valid)
      (= (a/field report valid) false))
    report))

(a/defstruct LanguageGeneration
  "Experimental full-vocabulary generation, independent of racing action heads.
  Stop:1 EOS,2 token limit,3 context limit,4 invalid input/model/token bytes.
  Opt-in language workers deliver this verbatim; the simulation validates
  plans separately. Generation success is not a claim of decision quality."
  {:layout :extern}
  [[:valid :bool] [:stop :u8] [:input_tokens :u16] [:output_tokens :u16]
   [:byte_count :u16] [:tokens [:array 64 :u32]] [:bytes [:array 2048 :u8]]])

(a/defn gpt2-codepoint-byte :i16
  "Invert GPT-2's byte-to-Unicode alphabet. This decodes tokenizer storage,
  not a game command encoding. Concatenated output bytes form ordinary UTF-8." [[code :u32]]
  (cond
    (or (and (>= code 33) (<= code 126))
        (and (>= code 161) (<= code 172))
        (and (>= code 174) (<= code 255))) (intCast code)
    (and (>= code 256) (<= code 288)) (intCast (- code 256))
    (and (>= code 289) (<= code 322)) (intCast (- code 162))
    (== code 323) (as 173 :i16)
    :else (as -1 :i16)))

(a/defn decode-language-token! :u16
  "Decode one real GGUF vocabulary entry into bytes. Return65535 on invalid
  Unicode/alphabet or insufficient space; never silently truncate a token." [[token :u32] [output [:c-pointer :u8]] [capacity :usize]]
  (when (or (! tokenizer-valid) (>= token tokenizer-token-count))
    (return (as 65535 :u16)))
  (let [start (as (a/index tokenizer-token-starts token) :usize)
        length (as (a/index tokenizer-token-lengths token) :usize)
        ^:var offset (usize 0)
        ^:var written (u16 0)]
    (while (< offset length)
      (let [first-byte (a/index (a/unwrap model-bytes) (+ start offset))
            ^:var code (u32 first-byte)]
        (= offset (+ offset 1))
        (when (>= first-byte 128)
          (when (or (< first-byte 194) (> first-byte 197) (>= offset length))
            (return (as 65535 :u16)))
          (let [second-byte (a/index (a/unwrap model-bytes) (+ start offset))]
            (when (or (< second-byte 128) (> second-byte 191))
              (return (as 65535 :u16)))
            (= code (+ (* (as (- first-byte 192) :u32) 64)
                       (as (- second-byte 128) :u32)))
            (= offset (+ offset 1))))
        (let [byte (gpt2-codepoint-byte code)]
          (when (or (< byte 0) (>= written capacity))
            (return (as 65535 :u16)))
          (= (a/index output written) (intCast byte))
          (= written (+ written 1)))))
    written))

(a/defn next-language-token :u32
  "Greedy argmax over the pretrained model's ENTIRE vocabulary. No A-H or
  pit-head scores influence this result. Consume the last normalized native
  backbone state already retained by forward-token!. Invalid returns0xffffffff." [[racer :usize]]
  (when (or (>= racer sequence-racer-count) (! tokenizer-valid)
            (== (a/index sequence-positions racer) 0))
    (return (as 0xffffffff :u32)))
  (let [separate (find-tensor "output.weight")
        output (if (< separate tensor-catalog-count) separate
                   (find-tensor "token_embd.weight"))
        slot (min (- (as (a/index sequence-positions racer) :usize) 1)
                  (- action-head-token-count 1))
        hidden (ak/& (a/index action-head-inputs
                              (+ (* racer action-head-input-count) (* slot model-hidden-size))))
        ^:var best (u32 0xffffffff)
        ^:var best-score (f32 -3.4e38)]
    (when (>= output tensor-catalog-count)
      (return (as 0xffffffff :u32)))
    (let [tensor (a/index tensor-catalog output)]
      (when (or (!= (a/field tensor dimension_count) 2)
                (!= (a/index (a/field tensor dimensions) 0) model-hidden-size)
                (!= (a/index (a/field tensor dimensions) 1) tokenizer-token-count))
        (return (as 0xffffffff :u32))))
    (dotimes [token tokenizer-token-count]
      (let [score (tensor-row-dot-kernel output token hidden)]
        (when (! (std-math/isFinite score))
          (return (as 0xffffffff :u32)))
        (when (> score best-score)
          (= best-score score)
          (= best (intCast token)))))
    best))

(a/defn vocabulary-token-id :u32
  "Find an exact tokenizer vocabulary spelling from the loaded GGUF. This
  resolves model control tokens; ordinary user text is never parsed as roles."
  [[spelling [:* {:size :c :const? true} :u8]] [length :usize]]
  (when tokenizer-valid
    (dotimes [token tokenizer-token-count]
      (when (== (a/index tokenizer-token-lengths token) length)
        (let [start (a/index tokenizer-token-starts token)
              ^:var same true]
          (dotimes [i length]
            (when (!= (a/index (a/unwrap model-bytes) (+ start i))
                      (a/index spelling i))
              (= same false)))
          (when same (return (as (intCast token) :u32)))))))
  (as 0xffffffff :u32))

(a/defn- language-letter? :bool [[byte :u8]]
  (or (and (>= byte 65) (<= byte 90)) (and (>= byte 97) (<= byte 122))))

(a/defn- language-digit? :bool [[byte :u8]]
  (and (>= byte 48) (<= byte 57)))

(a/defn- language-space? :bool [[byte :u8]]
  (or (== byte 32) (and (>= byte 9) (<= byte 13))))

(a/defn- language-lower :u8 [[byte :u8]]
  (if (and (>= byte 65) (<= byte 90)) (+ byte 32) byte))

(a/defn language-piece-length :usize
  "ASCII subset of Granite's published pre-tokenizer regex, in its alternative
  order. BPE must not merge across these word/number/punctuation boundaries." [[bytes [:* {:size :c :const? true} :u8]] [length :usize]]
  (when (== length 0) (return (as 0 :usize)))
  (let [first-byte (a/index bytes 0)]
    (when (and (== first-byte 39) (>= length 2))
      (let [second-byte (language-lower (a/index bytes 1))]
        (when (or (== second-byte 115) (== second-byte 116)
                  (== second-byte 109) (== second-byte 100))
          (return (as 2 :usize)))
        (when (>= length 3)
          (let [third-byte (language-lower (a/index bytes 2))]
            (when (or (and (== second-byte 114) (== third-byte 101))
                      (and (== second-byte 118) (== third-byte 101))
                      (and (== second-byte 108) (== third-byte 108)))
              (return (as 3 :usize)))))))
    (let [prefix (if (language-letter? first-byte) (as 0 :usize) (as 1 :usize))]
      (when (and (< prefix length) (language-letter? (a/index bytes prefix))
                 (!= first-byte 10) (!= first-byte 13)
                 (! (language-digit? first-byte)))
        (let [^:var end (+ prefix 1)]
          (while (and (< end length) (language-letter? (a/index bytes end)))
            (= end (+ end 1)))
          (return end))))
    (when (language-digit? first-byte)
      (let [^:var end (usize 1)]
        (while (and (< end (min length 3)) (language-digit? (a/index bytes end)))
          (= end (+ end 1)))
        (return end)))
    (let [prefix (if (== first-byte 32) (as 1 :usize) (as 0 :usize))
          ^:var end prefix]
      (while (and (< end length)
                  (! (language-space? (a/index bytes end)))
                  (! (language-letter? (a/index bytes end)))
                  (! (language-digit? (a/index bytes end))))
        (= end (+ end 1)))
      (when (> end prefix)
        (while (and (< end length)
                    (or (== (a/index bytes end) 10) (== (a/index bytes end) 13)))
          (= end (+ end 1)))
        (return end)))
    (when (language-space? first-byte)
      (let [^:var end (usize 0)
            ^:var last-newline (usize 0)]
        (while (and (< end length) (language-space? (a/index bytes end)))
          (= end (+ end 1))
          (when (or (== (a/index bytes (- end 1)) 10)
                    (== (a/index bytes (- end 1)) 13))
            (= last-newline end)))
        (return (cond (> last-newline 0) last-newline
                      (== end length) end
                      (> end 1) (- end 1)
                      :else end))))
    (as 1 :usize)))

(a/defn tokenize-language-ascii TokenizationReport
  "Bounded ordinary text: apply Granite's word-splitting rule before GGUF BPE.
  The old compact-head tokenizer is left unchanged for existing head fixtures."
  [[bytes [:* {:size :c :const? true} :u8]] [length :usize]]
  (let [^:var result (std-mem/zeroes (a/type TokenizationReport))
        ^:var offset (usize 0)
        bound (min length tokenizer-capacity)]
    (= (a/field result byte_count) (intCast bound))
    (= (a/field result truncated) (> length tokenizer-capacity))
    (= (a/field result valid) tokenizer-valid)
    (while (and (< offset bound) (a/field result valid))
      (let [size (language-piece-length (+ bytes offset) (- bound offset))
            piece (tokenize-compact-ascii (+ bytes offset) size)]
        (when (! (a/field piece valid))
          (= (a/field result valid) false)
          (= (a/field result unsupported_index)
             (intCast (+ offset (a/field piece unsupported_index)))))
        (dotimes [i (a/field piece token_count)]
          (= (a/index (a/field result tokens) (a/field result token_count))
             (a/index (a/field piece tokens) i))
          (= (a/field result token_count) (+ (a/field result token_count) 1)))
        (= offset (+ offset size))))
    result))

(a/defn tokenize-language-chat-with-system TokenizationReport
  "Granite's real system/user/assistant frame with caller-provided plain text.
  Content is tokenized separately from role controls, so a message cannot
  impersonate another role by spelling a special token. Bounded ASCII proof."
  [[system [:* {:size :c :const? true} :u8]] [system-length :usize]
   [prompt [:* {:size :c :const? true} :u8]] [length :usize]]
  (let [content (tokenize-language-ascii prompt length)
        system-role (tokenize-language-ascii "system" 6)
        system-content (tokenize-language-ascii system system-length)
        user-role (tokenize-language-ascii "user" 4)
        assistant-role (tokenize-language-ascii "assistant" 9)
        start-role (vocabulary-token-id "<|start_of_role|>" 17)
        end-role (vocabulary-token-id "<|end_of_role|>" 15)
        eos (metadata-u32 (find-metadata "tokenizer.ggml.eos_token_id") 0xffffffff)
        total (+ 10 (a/field content token_count)
                 (a/field system-role token_count) (a/field system-content token_count)
                 (a/field user-role token_count) (a/field assistant-role token_count))
        ^:var result (std-mem/zeroes (a/type TokenizationReport))
        ^:var cursor (usize 0)]
    (= (a/field result byte_count) (a/field content byte_count))
    (= (a/field result truncated)
       (or (a/field content truncated) (a/field system-content truncated)
           (> total tokenizer-capacity)))
    (when (or (! tokenizer-valid) (! (a/field content valid))
              (! (a/field system-content valid))
              (a/field result truncated) (>= start-role tokenizer-token-count)
              (>= end-role tokenizer-token-count) (>= eos tokenizer-token-count))
      (return result))
    (= (a/index (a/field result tokens) cursor) start-role)
    (= cursor (+ cursor 1))
    (dotimes [i (a/field system-role token_count)]
      (= (a/index (a/field result tokens) cursor) (a/index (a/field system-role tokens) i))
      (= cursor (+ cursor 1)))
    (= (a/index (a/field result tokens) cursor) end-role)
    (= cursor (+ cursor 1))
    (dotimes [i (a/field system-content token_count)]
      (= (a/index (a/field result tokens) cursor) (a/index (a/field system-content tokens) i))
      (= cursor (+ cursor 1)))
    (= (a/index (a/field result tokens) cursor) eos)
    (= cursor (+ cursor 1))
    (= (a/index (a/field result tokens) cursor) (ascii-byte-token 10))
    (= cursor (+ cursor 1))
    (= (a/index (a/field result tokens) cursor) start-role)
    (= cursor (+ cursor 1))
    (dotimes [i (a/field user-role token_count)]
      (= (a/index (a/field result tokens) cursor) (a/index (a/field user-role tokens) i))
      (= cursor (+ cursor 1)))
    (= (a/index (a/field result tokens) cursor) end-role)
    (= cursor (+ cursor 1))
    (dotimes [i (a/field content token_count)]
      (= (a/index (a/field result tokens) cursor) (a/index (a/field content tokens) i))
      (= cursor (+ cursor 1)))
    (= (a/index (a/field result tokens) cursor) eos)
    (= cursor (+ cursor 1))
    (= (a/index (a/field result tokens) cursor) (ascii-byte-token 10))
    (= cursor (+ cursor 1))
    (= (a/index (a/field result tokens) cursor) start-role)
    (= cursor (+ cursor 1))
    (dotimes [i (a/field assistant-role token_count)]
      (= (a/index (a/field result tokens) cursor) (a/index (a/field assistant-role tokens) i))
      (= cursor (+ cursor 1)))
    (= (a/index (a/field result tokens) cursor) end-role)
    (= (a/field result token_count) (intCast (+ cursor 1)))
    (= (a/field result valid) true)
    result))

(a/defn tokenize-language-chat TokenizationReport
  "Apply Granite's official default system message to a plain user message."
  [[prompt [:* {:size :c :const? true} :u8]] [length :usize]]
  (tokenize-language-chat-with-system
   "You are a helpful assistant. Please ensure responses are professional, accurate, and safe." 90
   prompt length))

(a/defn generate-language-with-system! LanguageGeneration
  "Bounded native autoregressive user-chat proof with real vocabulary decoding.
  Does not train/load a racing head or install game actions. Callers must
  validate replies and deadlines before changing a driving intention."
  [[racer :usize]
   [system [:* {:size :c :const? true} :u8]] [system-length :usize]
   [prompt [:* {:size :c :const? true} :u8]]
   [length :usize] [max-new-tokens :usize]]
  (let [^:var result (std-mem/zeroes (a/type LanguageGeneration))
        tokenized (tokenize-language-chat-with-system system system-length prompt length)
        eos (metadata-u32 (find-metadata "tokenizer.ggml.eos_token_id") 0xffffffff)]
    (= (a/field result stop) 4)
    (when (or (! tokenizer-valid) (! (a/field tokenized valid))
              (a/field tokenized truncated) (== (a/field tokenized token_count) 0)
              (>= eos tokenizer-token-count) (! (reset-sequence! racer)))
      (return result))
    (dotimes [i (a/field tokenized token_count)]
      (when (! (a/field (forward-token! racer (a/index (a/field tokenized tokens) i)) valid))
        (return result))
      (= (a/field result input_tokens) (+ (a/field result input_tokens) 1)))
    (= (a/field result valid) true)
    (= (a/field result stop) 2)
    (dotimes [_ (min max-new-tokens 64)]
      (let [token (next-language-token racer)]
        (when (== token eos)
          (= (a/field result stop) 1)
          (return result))
        (when (>= (a/field result byte_count) 2048)
          (= (a/field result valid) false)
          (= (a/field result stop) 4)
          (return result))
        (let [size (decode-language-token! token
                                           (ak/& (a/index (a/field result bytes) (a/field result byte_count)))
                                           (- 2048 (a/field result byte_count)))]
          (when (== size 65535)
            (= (a/field result valid) false)
            (= (a/field result stop) 4)
            (return result))
          (= (a/field result byte_count) (+ (a/field result byte_count) size)))
        (= (a/index (a/field result tokens) (a/field result output_tokens)) token)
        (= (a/field result output_tokens) (+ (a/field result output_tokens) 1))
        (when (>= (a/field result output_tokens) (min max-new-tokens 64))
          (return result))
        (when (>= (a/index sequence-positions racer) sequence-capacity)
          (= (a/field result stop) 3)
          (return result))
        (when (! (a/field (forward-token! racer token) valid))
          (= (a/field result valid) false)
          (= (a/field result stop) 4)
          (return result))))
    result))

(a/defn generate-language! LanguageGeneration
  "Generate a plain user-chat reply using Granite's official default system."
  [[racer :usize] [prompt [:* {:size :c :const? true} :u8]]
   [length :usize] [max-new-tokens :usize]]
  (generate-language-with-system!
   racer
   "You are a helpful assistant. Please ensure responses are professional, accurate, and safe." 90
   prompt length max-new-tokens))

(a/defn kernel-self-test KernelReport
  "Execute deterministic native fixtures without allocating or invoking a server."
  []
  (let [block (a/init [0 60 153 153 153 153 153 153 153 153
                       153 153 153 153 153 153 153 153] [:array 18 :u8])
        input (a/init [1.0 1.0 1.0 1.0 1.0 1.0 1.0 1.0
                       1.0 1.0 1.0 1.0 1.0 1.0 1.0 1.0
                       1.0 1.0 1.0 1.0 1.0 1.0 1.0 1.0
                       1.0 1.0 1.0 1.0 1.0 1.0 1.0 1.0] [:array 32 :f32])
        norm-input (a/init [1.0 2.0 3.0 4.0] [:array 4 :f32])
        weights (a/init [1.0 1.0 1.0 1.0] [:array 4 :f32])
        ^:var norm-output (as (a/init [0.0 0.0 0.0 0.0] [:array 4 :f32]) [:array 4 :f32])
        ^:var probabilities (as (a/init [1.0 2.0 3.0 4.0] [:array 4 :f32]) [:array 4 :f32])]
    (rms-norm! (ak/& (a/index norm-output 0)) (ak/& (a/index norm-input 0))
               (ak/& (a/index weights 0)) 4 0.00001)
    (softmax! (ak/& (a/index probabilities 0)) 4)
    (KernelReport
     {:q4_dot (q4-0-dot (ak/& (a/index block 0)) (ak/& (a/index input 0)))
      :rms_first (a/index norm-output 0)
      :rms_last (a/index norm-output 3)
      :softmax_sum (+ (a/index probabilities 0) (a/index probabilities 1)
                      (a/index probabilities 2) (a/index probabilities 3))})))
