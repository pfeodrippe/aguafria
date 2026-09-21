(ns racing-game.inference
  "Narrow native Granite runtime: model ownership, strict GGUF parsing, and kernels."
  (:require [aguafria.keyword :as ak]
            [aguafria.std]
            [aguafria.std.crypto.hash.sha2 :as std-sha2]
            [aguafria.std.mem :as std-mem]
            [aguafria.std.math :as std-math]
            [aguafria.std.posix :as std-posix]
            [aguafria.zig :as az]
            [aguafria-examples-native.bindings]
            [aguafria-examples-native.bindings.runtime :as runtime]
            [racing-game.protocol :as protocol]))

(declare free-sequences! find-tensor tensor-rms-norm! tensor-matvec!
         tensor-element silu embedding-value-kernel empty-forward-report)

(az/defconst gguf-magic :u32 0x46554747)

(az/defconst gguf-max-version :u32 3)

(az/defconst gguf-max-tensors :u64 4096)

(az/defconst gguf-max-metadata :u64 65536)

(az/defconst gguf-max-array-elements :u64 4000000)

(az/defconst parser-ok :u32 0)

(az/defconst parser-truncated :u32 1)

(az/defconst parser-bad-magic :u32 2)

(az/defconst parser-bad-version :u32 3)

(az/defconst parser-limit :u32 4)

(az/defconst parser-unsupported-type :u32 5)

(az/defconst model-file-not-found :u32 100)

(az/defconst model-file-empty :u32 101)

(az/defconst model-allocation-failed :u32 102)

(az/defconst model-file-read-failed :u32 103)

(az/defconst model-profile-unsupported :u32 104)

(az/defconst model-storage-none :u8 0)

(az/defconst model-storage-owned :u8 1)

(az/defconst model-storage-mapped :u8 2)

(az/defconst tensor-not-found :usize 4096)

(az/defconst metadata-not-found :usize 128)

(az/defconst tokenizer-capacity :usize 160)

(az/defconst tokenizer-vocabulary-capacity :usize 100352)

(az/defconst tokenizer-hash-capacity :usize 262144)

(az/defconst tokenizer-empty-id :u32 0xffffffff)

(az/defconst tokenizer-empty-pair :u64 0xffffffffffffffff)

(az/defconst model-profile-none :u8 0)

(az/defconst model-profile-granite-h-350m :u8 1)

(az/defconst model-profile-granite-h-1b :u8 2)

(az/defconst model-profile-granite-h-micro :u8 3)

(az/defconst model-max-hidden-size :usize 2048)

(az/defconst model-max-ffn-size :usize 8192)

(az/defconst model-max-layer-count :usize 40)

(az/defconst model-max-mamba-inner-size :usize 4096)

(az/defconst model-max-mamba-projection-size :usize 8512)

(az/defconst model-max-mamba-conv-size :usize 4352)

(az/defconst model-max-mamba-head-count :usize 64)

(az/defconst model-max-attention-kv-size :usize 512)

(az/defvar model-profile-id :u8 model-profile-none)

(az/defvar model-hidden-size :usize 768)

(az/defconst model-vocabulary-size :usize 100352)

(az/defvar model-ffn-size :usize 2048)

(az/defvar model-layer-count :usize 32)

(az/defvar model-mamba-layer-count :usize 28)

(az/defvar model-mamba-inner-size :usize 1536)

(az/defvar model-mamba-conv-size :usize 1792)

(az/defvar model-mamba-projection-size :usize 3376)

(az/defvar model-mamba-head-count :usize 48)

(az/defvar model-mamba-head-size :usize 32)

(az/defvar model-mamba-state-size :usize 128)

(az/defvar model-mamba-recurrent-size :usize 196608)

(az/defvar model-mamba-conv-state-size :usize 5376)

(az/defvar model-attention-layer-count :usize 4)

(az/defvar model-attention-head-count :usize 12)

(az/defvar model-attention-kv-head-count :usize 4)

(az/defvar model-attention-head-size :usize 64)

(az/defvar model-attention-kv-size :usize 256)

(az/defvar model-attention-scale :f32 0.015625)

(az/defconst sequence-capacity :usize 160)

(az/defconst sequence-racer-count :usize protocol/actor-count)

(az/defvar sequence-mamba-floats :usize 66060288)

(az/defvar sequence-conv-floats :usize 1806336)

(az/defvar sequence-kv-floats :usize 1966080)

(az/defvar sequence-total-floats :usize 71798784)

(az/defvar sequence-total-bytes :usize 287195136)

(az/defvar model-residual-multiplier :f32 0.246)

(az/defconst model-rms-epsilon :f32 0.00001)

(az/defconst action-head-magic :u32 0x48415241)

(az/defconst action-head-version :u32 1)

(az/defconst action-head-output-count :usize 8)

(az/defconst action-head-token-count :usize 8)

(az/defconst action-head-max-input-count :usize 16384)

(az/defconst action-head-max-weight-count :usize 131072)

(az/defvar action-head-input-count :usize 6144)

(az/defvar action-head-weight-count :usize 49152)

(az/defconst action-head-header-bytes :usize 32)

(az/defconst team-head-magic :u32 0x4d414554)

(az/defconst team-head-output-count :usize 3)

(az/defconst team-head-max-weight-count :usize 49152)

(az/defvar team-head-weight-count :usize 18432)

(az/defstruct Reader
  {:layout :extern}
  [[:bytes [:c-pointer :u8]]
   [:length :usize]
   [:cursor :usize]
   [:error_code :u32]])

(az/defstruct StringView
  {:layout :extern}
  [[:start :usize]
   [:length :usize]])

(az/defstruct TensorInfo
  "Zero-copy descriptor whose name and tensor data remain inside model-bytes."
  {:layout :extern}
  [[:name_start :usize]
   [:name_length :usize]
   [:dimension_count :u8]
   [:dimensions [:array 4 :u64]]
   [:ggml_type :u32]
   [:relative_offset :u64]
   [:data_address :usize]])

(az/defstruct MetadataInfo
  "Zero-copy descriptor for one GGUF metadata value or array."
  {:layout :extern}
  [[:key_start :usize]
   [:key_length :usize]
   [:value_type :u32]
   [:element_type :u32]
   [:value_start :usize]
   [:element_count :u64]])

(az/defstruct TokenizationReport
  "Bounded exact byte-token encoding used by the first compact race prompts."
  {:layout :extern}
  [[:valid :bool]
   [:truncated :bool]
   [:byte_count :u16]
   [:token_count :u16]
   [:unsupported_index :u16]
   [:reserved :u16]
   [:tokens [:array 160 :u32]]])

(az/defstruct TokenizerMerge
  {:layout :extern}
  [[:found :bool]
   [:rank :u32]
   [:token :u32]])

(az/defstruct TokenizerSummary
  "Inspectable native vocabulary and BPE index built directly from GGUF."
  {:layout :extern}
  [[:valid :bool]
   [:token_count :u32]
   [:merge_count :u32]
   [:vocabulary_slots :u32]
   [:merge_slots :u32]])

(az/defstruct ModelProfileSummary
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

(az/defstruct GgufSummary
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

(az/defstruct KernelReport
  "Small deterministic numerical probe used by JVM and standalone tests."
  {:layout :extern}
  [[:q4_dot :f32]
   [:rms_first :f32]
   [:rms_last :f32]
   [:softmax_sum :f32]])

(az/defstruct SequenceSummary
  "Inspectable ownership and token positions for all independent racer minds."
  {:layout :extern}
  [[:initialized :bool]
   [:racer_count :u8]
   [:capacity :u16]
   [:state_bytes :usize]
   [:positions [:array sequence-racer-count :u16]]])

(az/defstruct ForwardReport
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

(az/defstruct ActionHeadSummary
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

(az/defvar model-bytes [:optional [:c-pointer :u8]] ak/null)

(az/defvar model-byte-count :usize 0)

(az/defvar model-storage :u8 model-storage-none)

(az/defvar tensor-catalog-count :usize 0)

(az/defvar tensor-catalog [:array 4096 TensorInfo]
  (std-mem/zeroes (az/type [:array 4096 TensorInfo])))

(az/defvar metadata-catalog-count :usize 0)

(az/defvar metadata-catalog [:array 128 MetadataInfo]
  (std-mem/zeroes (az/type [:array 128 MetadataInfo])))

(az/defvar tokenizer-valid :bool false)

(az/defvar tokenizer-token-count :u32 0)

(az/defvar tokenizer-merge-count :u32 0)

(az/defvar tokenizer-token-starts [:array 100352 :u32]
  (std-mem/zeroes (az/type [:array 100352 :u32])))

(az/defvar tokenizer-token-lengths [:array 100352 :u16]
  (std-mem/zeroes (az/type [:array 100352 :u16])))

(az/defvar tokenizer-vocabulary-slots [:array 262144 :u32]
  (std-mem/zeroes (az/type [:array 262144 :u32])))

(az/defvar tokenizer-merge-pairs [:array 262144 :u64]
  (std-mem/zeroes (az/type [:array 262144 :u64])))

(az/defvar tokenizer-merge-ranks [:array 262144 :u32]
  (std-mem/zeroes (az/type [:array 262144 :u32])))

(az/defvar tokenizer-merge-tokens [:array 262144 :u32]
  (std-mem/zeroes (az/type [:array 262144 :u32])))

(az/defvar model-summary GgufSummary
  (GgufSummary {:loaded false :valid false :error_code parser-truncated
                :version 0 :tensor_count 0 :metadata_count 0
                :f32_tensors 0 :q4_0_tensors 0 :q6_k_tensors 0
                :descriptor_end 0 :data_offset 0 :file_size 0}))

(az/defvar sequence-memory [:optional [:c-pointer :f32]] ak/null)

(az/defvar sequence-memory-floats :usize 0)

(az/defvar sequence-positions [:array sequence-racer-count :u16]
  (std-mem/zeroes (az/type [:array sequence-racer-count :u16])))

(az/defvar action-head-inputs [:array 196608 :f32]
  (std-mem/zeroes (az/type [:array 196608 :f32])))

(az/defvar fused-observation-inputs [:array 24576 :f32]
  (std-mem/zeroes (az/type [:array 24576 :f32])))

(az/defvar action-head-weights [:array 131072 :f32]
  (std-mem/zeroes (az/type [:array 131072 :f32])))

(az/defvar action-head-biases [:array 8 :f32]
  (std-mem/zeroes (az/type [:array 8 :f32])))

(az/defvar action-head-summary ActionHeadSummary
  (ActionHeadSummary
   {:loaded false :valid false :error_code 1 :version 0
    :input_count 0 :output_count 0 :observation_schema 0 :action_schema 0
    :weight_count 0 :file_size 0}))

(az/defvar team-head-weights [:array 49152 :f32]
  (std-mem/zeroes (az/type [:array 49152 :f32])))

(az/defvar team-head-biases [:array 3 :f32]
  (std-mem/zeroes (az/type [:array 3 :f32])))

(az/defvar team-head-summary ActionHeadSummary
  (ActionHeadSummary
   {:loaded false :valid false :error_code 1 :version 0
    :input_count 0 :output_count 0 :observation_schema 0 :action_schema 0
    :weight_count 0 :file_size 0}))

(az/defn can-read :bool
  [[reader [:* Reader]]
   [count :usize]]
  (and (ak/== (az/field (az/deref reader) error_code) parser-ok)
       (<= count (- (az/field (az/deref reader) length)
                    (az/field (az/deref reader) cursor)))))

(az/defn read-u8! :u8
  [[reader [:* Reader]]]
  (if (can-read reader 1)
    (let [value (az/index (az/field (az/deref reader) bytes)
                          (az/field (az/deref reader) cursor))]
      (ak/= (az/field (az/deref reader) cursor)
            (+ (az/field (az/deref reader) cursor) 1))
      value)
    (do
      (when (ak/== (az/field (az/deref reader) error_code) parser-ok)
        (ak/= (az/field (az/deref reader) error_code) parser-truncated))
      0)))

(az/defn read-u32! :u32
  [[reader [:* Reader]]]
  (let [b0 (ak/as (read-u8! reader) :u32)
        b1 (ak/as (read-u8! reader) :u32)
        b2 (ak/as (read-u8! reader) :u32)
        b3 (ak/as (read-u8! reader) :u32)]
    (+ b0 (* b1 256) (* b2 65536) (* b3 16777216))))

(az/defn read-u64! :u64
  [[reader [:* Reader]]]
  (let [low (ak/as (read-u32! reader) :u64)
        high (ak/as (read-u32! reader) :u64)]
    (+ low (* high 4294967296))))

(az/defn skip-bytes! :void
  [[reader [:* Reader]]
   [count :u64]]
  (if (and (<= count (ak/as (az/field (az/deref reader) length) :u64))
           (can-read reader (ak/intCast count)))
    (ak/= (az/field (az/deref reader) cursor)
          (+ (az/field (az/deref reader) cursor) (ak/as (ak/intCast count) :usize)))
    (ak/= (az/field (az/deref reader) error_code) parser-truncated)))

(az/defn read-string-view! StringView
  [[reader [:* Reader]]]
  (let [length (read-u64! reader)
        start (az/field (az/deref reader) cursor)]
    (skip-bytes! reader length)
    (StringView {:start start :length (ak/intCast length)})))

(az/defn skip-string! :void
  [[reader [:* Reader]]]
  (ak/= :_ (read-string-view! reader)))

(az/defn scalar-byte-size :u8
  [[value-type :u32]]
  (cond
    (or (ak/== value-type 0) (ak/== value-type 1) (ak/== value-type 7)) 1
    (or (ak/== value-type 2) (ak/== value-type 3)) 2
    (or (ak/== value-type 4) (ak/== value-type 5) (ak/== value-type 6)) 4
    (or (ak/== value-type 10) (ak/== value-type 11) (ak/== value-type 12)) 8
    :else 0))

(az/defn skip-value! :void
  [[reader [:* Reader]]
   [value-type :u32]
   [depth :u8]]
  (cond
    (ak/== value-type 8)
    (skip-string! reader)

    (ak/== value-type 9)
    (if (>= depth 4)
      (ak/= (az/field (az/deref reader) error_code) parser-limit)
      (let [element-type (read-u32! reader)
            count (read-u64! reader)]
        (if (> count gguf-max-array-elements)
          (ak/= (az/field (az/deref reader) error_code) parser-limit)
          (dotimes [_ count]
            (skip-value! reader element-type (+ depth 1))))))

    :else
    (let [width (scalar-byte-size value-type)]
      (if (ak/== width 0)
        (ak/= (az/field (az/deref reader) error_code) parser-unsupported-type)
        (skip-bytes! reader width)))))

(az/defn parse-gguf GgufSummary
  [[bytes [:c-pointer :u8]]
   [length :usize]]
  (let [^:var reader (ak/as (Reader {:bytes bytes :length length :cursor 0 :error_code parser-ok}) Reader)
        magic (read-u32! (ak/& reader))
        version (read-u32! (ak/& reader))
        tensor-count (read-u64! (ak/& reader))
        metadata-count (read-u64! (ak/& reader))
        ^:var f32-count (ak/u32 0)
        ^:var q4-count (ak/u32 0)
        ^:var q6-count (ak/u32 0)]
    (ak/= tensor-catalog-count 0)
    (ak/= metadata-catalog-count 0)
    (when (ak/!= magic gguf-magic)
      (ak/= (az/field reader error_code) parser-bad-magic))
    (when (and (ak/== (az/field reader error_code) parser-ok)
               (or (ak/== version 0) (> version gguf-max-version)))
      (ak/= (az/field reader error_code) parser-bad-version))
    (when (and (ak/== (az/field reader error_code) parser-ok)
               (or (> tensor-count gguf-max-tensors)
                   (> metadata-count gguf-max-metadata)))
      (ak/= (az/field reader error_code) parser-limit))
    (when (ak/== (az/field reader error_code) parser-ok)
      (dotimes [metadata-index metadata-count]
        (let [key (read-string-view! (ak/& reader))
              value-type (read-u32! (ak/& reader))]
          (if (ak/== value-type 9)
            (let [element-type (read-u32! (ak/& reader))
                  element-count (read-u64! (ak/& reader))
                  value-start (az/field reader cursor)]
              (when (< metadata-index metadata-not-found)
                (ak/= (az/index metadata-catalog (ak/intCast metadata-index))
                      (MetadataInfo
                       {:key_start (az/field key start)
                        :key_length (az/field key length)
                        :value_type value-type
                        :element_type element-type
                        :value_start value-start
                        :element_count element-count})))
              (if (> element-count gguf-max-array-elements)
                (ak/= (az/field reader error_code) parser-limit)
                (dotimes [_ element-count]
                  (skip-value! (ak/& reader) element-type 1))))
            (let [value-start (az/field reader cursor)]
              (when (< metadata-index metadata-not-found)
                (ak/= (az/index metadata-catalog (ak/intCast metadata-index))
                      (MetadataInfo
                       {:key_start (az/field key start)
                        :key_length (az/field key length)
                        :value_type value-type
                        :element_type 0
                        :value_start value-start
                        :element_count 1})))
              (skip-value! (ak/& reader) value-type 0))))))
    (when (ak/== (az/field reader error_code) parser-ok)
      (dotimes [tensor-index tensor-count]
        (let [name (read-string-view! (ak/& reader))
              dimensions (read-u32! (ak/& reader))
              ^:var shape (ak/as (az/array-init [0 0 0 0] [:array 4 :u64]) [:array 4 :u64])]
          (if (> dimensions 4)
            (ak/= (az/field reader error_code) parser-limit)
            (dotimes [dimension dimensions]
              (ak/= (az/index shape dimension) (read-u64! (ak/& reader)))))
          (let [ggml-type (read-u32! (ak/& reader))
                relative-offset (read-u64! (ak/& reader))]
            (when (ak/== ggml-type 0) (ak/= f32-count (+ f32-count 1)))
            (when (ak/== ggml-type 2) (ak/= q4-count (+ q4-count 1)))
            (when (ak/== ggml-type 14) (ak/= q6-count (+ q6-count 1)))
            (when (and (ak/!= ggml-type 0)
                       (ak/!= ggml-type 2)
                       (ak/!= ggml-type 14))
              (ak/= (az/field reader error_code) parser-unsupported-type))
            (when (< tensor-index gguf-max-tensors)
              (ak/= (az/index tensor-catalog (ak/intCast tensor-index))
                    (TensorInfo {:name_start (az/field name start)
                                 :name_length (az/field name length)
                                 :dimension_count (ak/intCast dimensions)
                                 :dimensions shape
                                 :ggml_type ggml-type
                                 :relative_offset relative-offset
                                 :data_address 0})))))))
    (let [descriptor-end (az/field reader cursor)
          data-offset (* (/ (+ descriptor-end 31) 32) 32)
          valid (and (ak/== (az/field reader error_code) parser-ok)
                     (<= data-offset length))]
      (when valid
        (ak/= metadata-catalog-count (ak/intCast metadata-count))
        (ak/= tensor-catalog-count (ak/intCast tensor-count))
        (dotimes [tensor-index tensor-catalog-count]
          (ak/= (az/field (az/index tensor-catalog tensor-index) data_address)
                (+ (ak/intFromPtr bytes)
                   data-offset
                   (ak/as (ak/intCast
                           (az/field (az/index tensor-catalog tensor-index)
                                     relative_offset))
                          :usize)))))
      (GgufSummary {:loaded true :valid valid
                    :error_code (az/field reader error_code)
                    :version version :tensor_count tensor-count
                    :metadata_count metadata-count
                    :f32_tensors f32-count :q4_0_tensors q4-count
                    :q6_k_tensors q6-count
                    :descriptor_end descriptor-end
                    :data_offset data-offset :file_size length}))))

(az/defn action-head-header-u32 :u32
  [[header [:pointer {:size :c :const? true} :u8]]
   [offset :usize]]
  (+ (ak/as (az/index header offset) :u32)
     (* (ak/as (az/index header (+ offset 1)) :u32) 256)
     (* (ak/as (az/index header (+ offset 2)) :u32) 65536)
     (* (ak/as (az/index header (+ offset 3)) :u32) 16777216)))

(az/defn unload-action-head! :void
  "Disable the racing-specific head without touching the shared base model."
  []
  (ak/= action-head-summary
        (ActionHeadSummary
         {:loaded false :valid false :error_code 1 :version 0
          :input_count 0 :output_count 0
          :observation_schema 0 :action_schema 0
          :weight_count 0 :file_size 0})))

(az/defn unload-team-head! :void
  "Disable the independent three-choice team strategy head."
  []
  (ak/= team-head-summary
        (ActionHeadSummary
         {:loaded false :valid false :error_code 1 :version 0
          :input_count 0 :output_count 0
          :observation_schema 0 :action_schema 0
          :weight_count 0 :file_size 0})))

(az/defn load-action-head! ActionHeadSummary
  "Load the verified fixed-layout A-H action head. The file contains a
  32-byte little-endian header, 8x6144 row-major f32 weights, and 8 f32 biases.
  The current two-step feature extractor fills the first 1536 values and keeps
  the remaining compatibility slots zero."
  [[path [:pointer {:size :c :const? true} :u8]]]
  (do
    (unload-action-head!)
    (let [file (runtime/fopen path "rb")]
      (if (ak/== file ak/null)
        action-head-summary
        (do
          (ak/= :_ (runtime/fseek file 0 2))
          (let [signed-size (runtime/ftell file)]
            (ak/= :_ (runtime/fseek file 0 0))
            (if (<= signed-size 0)
              (ak/= action-head-summary
                    (ActionHeadSummary
                     {:loaded true :valid false :error_code 2 :version 0
                      :input_count 0 :output_count 0
                      :observation_schema 0 :action_schema 0
                      :weight_count 0 :file_size 0}))
              (let [size (ak/as (ak/intCast signed-size) :usize)
                    ^:var header
                    (std-mem/zeroes (az/type [:array 32 :u8]))
                    header-read
                    (runtime/fread (ak/& (az/index header 0)) 1
                                   action-head-header-bytes file)
                    magic (action-head-header-u32
                           (ak/& (az/index header 0)) 0)
                    version (action-head-header-u32
                             (ak/& (az/index header 0)) 4)
                    input-count (action-head-header-u32
                                 (ak/& (az/index header 0)) 8)
                    output-count (action-head-header-u32
                                  (ak/& (az/index header 0)) 12)
                    observation-schema (action-head-header-u32
                                        (ak/& (az/index header 0)) 16)
                    action-schema (action-head-header-u32
                                   (ak/& (az/index header 0)) 20)
                    weight-count (action-head-header-u32
                                  (ak/& (az/index header 0)) 24)
                    expected-size
                    (+ action-head-header-bytes
                       (* (+ action-head-weight-count action-head-output-count)
                          (ak/sizeOf :f32)))
                    compatible
                    (and (ak/== header-read action-head-header-bytes)
                         (ak/== size expected-size)
                         (ak/== magic action-head-magic)
                         (ak/== version action-head-version)
                         (ak/== input-count action-head-input-count)
                         (ak/== output-count action-head-output-count)
                         (ak/== observation-schema
                                protocol/observation-schema-version)
                         (ak/== action-schema protocol/action-schema-version)
                         (ak/== weight-count action-head-weight-count))]
                (if (ak/! compatible)
                  (ak/= action-head-summary
                        (ActionHeadSummary
                         {:loaded true :valid false :error_code 3
                          :version version :input_count input-count
                          :output_count output-count
                          :observation_schema observation-schema
                          :action_schema action-schema
                          :weight_count weight-count :file_size size}))
                  (let [weights-read
                        (runtime/fread
                         (ak/& (az/index action-head-weights 0))
                         (ak/sizeOf :f32) action-head-weight-count file)
                        biases-read
                        (runtime/fread
                         (ak/& (az/index action-head-biases 0))
                         (ak/sizeOf :f32) action-head-output-count file)
                        valid
                        (and (ak/== weights-read action-head-weight-count)
                             (ak/== biases-read action-head-output-count))]
                    (ak/= action-head-summary
                          (ActionHeadSummary
                           {:loaded true :valid valid
                            :error_code (if valid 0 4)
                            :version version :input_count input-count
                            :output_count output-count
                            :observation_schema observation-schema
                            :action_schema action-schema
                            :weight_count weight-count :file_size size})))))))
          (ak/= :_ (runtime/fclose file))
          action-head-summary)))))

(az/defn action-head-status ActionHeadSummary
  []
  action-head-summary)

(az/defn load-team-head! ActionHeadSummary
  "Load the verified stay-out/pit-A/pit-B team strategy head."
  [[path [:pointer {:size :c :const? true} :u8]]]
  (do
    (unload-team-head!)
    (let [file (runtime/fopen path "rb")]
      (if (ak/== file ak/null)
        team-head-summary
        (do
          (ak/= :_ (runtime/fseek file 0 2))
          (let [signed-size (runtime/ftell file)]
            (ak/= :_ (runtime/fseek file 0 0))
            (if (<= signed-size 0)
              (ak/= team-head-summary
                    (ActionHeadSummary
                     {:loaded true :valid false :error_code 2 :version 0
                      :input_count 0 :output_count 0
                      :observation_schema 0 :action_schema 0
                      :weight_count 0 :file_size 0}))
              (let [size (ak/as (ak/intCast signed-size) :usize)
                    ^:var header
                    (std-mem/zeroes (az/type [:array 32 :u8]))
                    header-read
                    (runtime/fread (ak/& (az/index header 0)) 1
                                   action-head-header-bytes file)
                    magic (action-head-header-u32
                           (ak/& (az/index header 0)) 0)
                    version (action-head-header-u32
                             (ak/& (az/index header 0)) 4)
                    input-count (action-head-header-u32
                                 (ak/& (az/index header 0)) 8)
                    output-count (action-head-header-u32
                                  (ak/& (az/index header 0)) 12)
                    observation-schema (action-head-header-u32
                                        (ak/& (az/index header 0)) 16)
                    action-schema (action-head-header-u32
                                   (ak/& (az/index header 0)) 20)
                    weight-count (action-head-header-u32
                                  (ak/& (az/index header 0)) 24)
                    expected-size
                    (+ action-head-header-bytes
                       (* (+ team-head-weight-count team-head-output-count)
                          (ak/sizeOf :f32)))
                    compatible
                    (and (ak/== header-read action-head-header-bytes)
                         (ak/== size expected-size)
                         (ak/== magic team-head-magic)
                         (ak/== version action-head-version)
                         (ak/== input-count action-head-input-count)
                         (ak/== output-count team-head-output-count)
                         (ak/== observation-schema
                                protocol/observation-schema-version)
                         (ak/== action-schema protocol/action-schema-version)
                         (ak/== weight-count team-head-weight-count))]
                (if (ak/! compatible)
                  (ak/= team-head-summary
                        (ActionHeadSummary
                         {:loaded true :valid false :error_code 3
                          :version version :input_count input-count
                          :output_count output-count
                          :observation_schema observation-schema
                          :action_schema action-schema
                          :weight_count weight-count :file_size size}))
                  (let [weights-read
                        (runtime/fread
                         (ak/& (az/index team-head-weights 0))
                         (ak/sizeOf :f32) team-head-weight-count file)
                        biases-read
                        (runtime/fread
                         (ak/& (az/index team-head-biases 0))
                         (ak/sizeOf :f32) team-head-output-count file)
                        valid
                        (and (ak/== weights-read team-head-weight-count)
                             (ak/== biases-read team-head-output-count))]
                    (ak/= team-head-summary
                          (ActionHeadSummary
                           {:loaded true :valid valid
                            :error_code (if valid 0 4)
                            :version version :input_count input-count
                            :output_count output-count
                            :observation_schema observation-schema
                            :action_schema action-schema
                            :weight_count weight-count :file_size size})))))))
          (ak/= :_ (runtime/fclose file))
          team-head-summary)))))

(az/defn team-head-status ActionHeadSummary
  []
  team-head-summary)

(az/defn unload-model! :void
  []
  (free-sequences!)
  (unload-action-head!)
  (unload-team-head!)
  (when (ak/!= model-bytes ak/null)
    (if (ak/== model-storage model-storage-mapped)
      (ak/= :_
            (runtime/munmap
             (az/cast (az/unwrap model-bytes) [:* :anyopaque])
             model-byte-count))
      (runtime/free (az/cast (az/unwrap model-bytes) [:* :anyopaque]))))
  (ak/= model-bytes ak/null)
  (ak/= model-byte-count 0)
  (ak/= model-storage model-storage-none)
  (ak/= model-profile-id model-profile-none)
  (ak/= tokenizer-valid false)
  (ak/= tokenizer-token-count 0)
  (ak/= tokenizer-merge-count 0)
  (ak/= tensor-catalog-count 0)
  (ak/= metadata-catalog-count 0)
  (ak/= model-summary
        (GgufSummary {:loaded false :valid false :error_code parser-truncated
                      :version 0 :tensor_count 0 :metadata_count 0
                      :f32_tensors 0 :q4_0_tensors 0 :q6_k_tensors 0
                :descriptor_end 0 :data_offset 0 :file_size 0})))

(az/defn model-storage-kind :u8
  "Inspect whether weights are absent, heap-owned, or read-only mapped."
  []
  model-storage)

(az/defn loaded-model-sha256 [:array 32 :u8]
  "Compute the exact SHA-256 digest of the currently owned model bytes."
  []
  (let [^:var digest (ak/as (std-mem/zeroes (az/type [:array 32 :u8])) [:array 32 :u8])]
    (when (ak/!= model-bytes ak/null)
      ((az/field std-sha2/Sha256 hash)
       (az/slice (az/unwrap model-bytes) 0 model-byte-count)
       (ak/& digest)
       {}))
    digest))

(az/defn sha256-matches? :bool
  "Compare one digest value with an expected 32-byte digest."
  [[actual [:array 32 :u8]]
   [expected [:pointer {:size :one :const? true} [:array 32 :u8]]]]
  (let [^:var equal (ak/bool true)]
    (dotimes [index 32]
      (when (ak/!= (az/index actual index)
                   (az/index (az/deref expected) index))
        (ak/= equal false)))
    equal))

(az/defn file-sha256-matches? :bool
  "Verify a native file without retaining its bytes after the check."
  [[path [:pointer {:size :c :const? true} :u8]]
   [expected [:pointer {:size :one :const? true} [:array 32 :u8]]]]
  (let [file (runtime/fopen path "rb")]
    (if (ak/== file ak/null)
      false
      (let [^:var valid (ak/bool false)]
        (ak/= :_ (runtime/fseek file 0 2))
        (let [signed-size (runtime/ftell file)]
          (ak/= :_ (runtime/fseek file 0 0))
          (when (> signed-size 0)
            (let [size (ak/as (ak/intCast signed-size) :usize)
                  allocation (runtime/malloc size)]
              (when (ak/!= allocation ak/null)
                (let [bytes (az/cast allocation [:c-pointer :u8])
                      count (runtime/fread bytes 1 size file)
                      ^:var actual (ak/as (std-mem/zeroes (az/type [:array 32 :u8])) [:array 32 :u8])]
                  (when (ak/== count size)
                    ((az/field std-sha2/Sha256 hash)
                     (az/slice bytes 0 size)
                     (ak/& actual)
                     {})
                    (ak/= valid (sha256-matches? actual expected)))
                  (runtime/free allocation))))))
        (ak/= :_ (runtime/fclose file))
        valid))))

(az/defn tensor-info TensorInfo
  "Inspect one parsed tensor descriptor by stable GGUF order."
  [[index :usize]]
  (if (< index tensor-catalog-count)
    (az/index tensor-catalog index)
    (TensorInfo {:name_start 0 :name_length 0 :dimension_count 0
                 :dimensions (az/array-init [0 0 0 0] [:array 4 :u64])
                 :ggml_type 0 :relative_offset 0 :data_address 0})))

(az/defn metadata-info MetadataInfo
  "Inspect one parsed GGUF metadata descriptor by stable file order."
  [[index :usize]]
  (if (< index metadata-catalog-count)
    (az/index metadata-catalog index)
    (MetadataInfo {:key_start 0 :key_length 0 :value_type 0
                   :element_type 0 :value_start 0 :element_count 0})))

(az/defn metadata-name-byte :u8
  "Return one UTF-8 byte from a metadata key."
  [[metadata-index :usize]
   [byte-index :usize]]
  (if (or (ak/== model-bytes ak/null)
          (>= metadata-index metadata-catalog-count)
          (>= byte-index
              (az/field (az/index metadata-catalog metadata-index) key_length)))
    0
    (az/index (az/unwrap model-bytes)
              (+ (az/field (az/index metadata-catalog metadata-index) key_start)
                 byte-index))))

(az/defn metadata-name-equals :bool
  [[metadata-index :usize]
   [expected [:pointer {:size :c :const? true} :u8]]]
  (if (or (ak/== model-bytes ak/null)
          (>= metadata-index metadata-catalog-count))
    false
    (let [length (az/field (az/index metadata-catalog metadata-index) key_length)
          ^:var equal (ak/bool true)]
      (dotimes [byte-index length]
        (when (ak/!= (metadata-name-byte metadata-index byte-index)
                     (az/index expected byte-index))
          (ak/= equal false)))
      (and equal (ak/== (az/index expected length) 0)))))

(az/defn find-metadata :usize
  "Resolve a GGUF metadata entry by its exact zero-terminated key."
  [[expected [:pointer {:size :c :const? true} :u8]]]
  (let [^:var found (ak/usize metadata-not-found)]
    (dotimes [metadata-index metadata-catalog-count]
      (when (and (ak/== found metadata-not-found)
                 (metadata-name-equals metadata-index expected))
        (ak/= found metadata-index)))
    found))

(az/defn model-u32-at :u32
  [[offset :usize]]
  (if (or (ak/== model-bytes ak/null) (> (+ offset 4) model-byte-count))
    0
    (+ (ak/as (az/index (az/unwrap model-bytes) offset) :u32)
       (* (ak/as (az/index (az/unwrap model-bytes) (+ offset 1)) :u32) 256)
       (* (ak/as (az/index (az/unwrap model-bytes) (+ offset 2)) :u32) 65536)
       (* (ak/as (az/index (az/unwrap model-bytes) (+ offset 3)) :u32) 16777216))))

(az/defn model-u64-at :u64
  [[offset :usize]]
  (if (or (ak/== model-bytes ak/null) (> (+ offset 8) model-byte-count))
    0
    (+ (ak/as (model-u32-at offset) :u64)
       (ak/<< (ak/as (model-u32-at (+ offset 4)) :u64) 32))))

(az/defn metadata-u32 :u32
  "Read a scalar u32 metadata value, returning `fallback` on type mismatch."
  [[metadata-index :usize]
   [fallback :u32]]
  (if (and (< metadata-index metadata-catalog-count)
           (ak/== (az/field (az/index metadata-catalog metadata-index) value_type) 4))
    (model-u32-at
     (az/field (az/index metadata-catalog metadata-index) value_start))
    fallback))

(az/defn metadata-f32 :f32
  "Read a scalar f32 metadata value, returning `fallback` on type mismatch."
  [[metadata-index :usize]
   [fallback :f32]]
  (if (and (< metadata-index metadata-catalog-count)
           (ak/== (az/field (az/index metadata-catalog metadata-index) value_type) 6))
    (ak/as (ak/bitCast
            (model-u32-at
             (az/field (az/index metadata-catalog metadata-index) value_start)))
           :f32)
    fallback))

(az/defn tokenizer-hash-range :u64
  [[start :usize]
   [length :usize]]
  (let [^:var hash (ak/u64 1469598103934665603)]
    (when (ak/!= model-bytes ak/null)
      (dotimes [index length]
        (ak/= hash
              (ak/*% (ak/bit-xor
                      hash
                      (ak/as (az/index (az/unwrap model-bytes)
                                       (+ start index))
                             :u64))
                     1099511628211))))
    hash))

(az/defn tokenizer-hash-concat :u64
  [[left-start :usize]
   [left-length :usize]
   [right-start :usize]
   [right-length :usize]]
  (let [^:var hash (ak/u64 1469598103934665603)]
    (when (ak/!= model-bytes ak/null)
      (dotimes [index left-length]
        (ak/= hash
              (ak/*% (ak/bit-xor
                      hash
                      (ak/as (az/index (az/unwrap model-bytes)
                                       (+ left-start index))
                             :u64))
                     1099511628211)))
      (dotimes [index right-length]
        (ak/= hash
              (ak/*% (ak/bit-xor
                      hash
                      (ak/as (az/index (az/unwrap model-bytes)
                                       (+ right-start index))
                             :u64))
                     1099511628211))))
    hash))

(az/defn tokenizer-token-equals-range :bool
  [[token :u32]
   [start :usize]
   [length :usize]]
  (if (or (>= token tokenizer-token-count)
          (ak/!= (ak/as (az/index tokenizer-token-lengths token) :usize)
                 length))
    false
    (let [token-start (ak/as (az/index tokenizer-token-starts token) :usize)
          ^:var equal (ak/bool true)]
      (dotimes [index length]
        (when (ak/!= (az/index (az/unwrap model-bytes) (+ token-start index))
                     (az/index (az/unwrap model-bytes) (+ start index)))
          (ak/= equal false)))
      equal)))

(az/defn tokenizer-token-equals-concat :bool
  [[token :u32]
   [left-start :usize]
   [left-length :usize]
   [right-start :usize]
   [right-length :usize]]
  (if (or (>= token tokenizer-token-count)
          (ak/!= (ak/as (az/index tokenizer-token-lengths token) :usize)
                 (+ left-length right-length)))
    false
    (let [token-start (ak/as (az/index tokenizer-token-starts token) :usize)
          ^:var equal (ak/bool true)]
      (dotimes [index left-length]
        (when (ak/!= (az/index (az/unwrap model-bytes) (+ token-start index))
                     (az/index (az/unwrap model-bytes) (+ left-start index)))
          (ak/= equal false)))
      (dotimes [index right-length]
        (when (ak/!=
               (az/index (az/unwrap model-bytes)
                         (+ token-start left-length index))
               (az/index (az/unwrap model-bytes) (+ right-start index)))
          (ak/= equal false)))
      equal)))

(az/defn tokenizer-insert-vocabulary! :bool
  [[token :u32]]
  (let [start (ak/as (az/index tokenizer-token-starts token) :usize)
        length (ak/as (az/index tokenizer-token-lengths token) :usize)
        base (ak/as (ak/intCast
                     (mod (tokenizer-hash-range start length)
                          tokenizer-hash-capacity))
                    :usize)
        ^:var inserted (ak/bool false)]
    (dotimes [probe tokenizer-hash-capacity]
      (when (ak/! inserted)
        (let [slot (mod (+ base probe) tokenizer-hash-capacity)]
          (when (ak/== (az/index tokenizer-vocabulary-slots slot)
                       tokenizer-empty-id)
            (ak/= (az/index tokenizer-vocabulary-slots slot) token)
            (ak/= inserted true)
            (ak/break)))))
    inserted))

(az/defn tokenizer-find-range :u32
  [[start :usize]
   [length :usize]]
  (let [base (ak/as (ak/intCast
                     (mod (tokenizer-hash-range start length)
                          tokenizer-hash-capacity))
                    :usize)
        ^:var result (ak/u32 tokenizer-empty-id)
        ^:var searching (ak/bool true)]
    (dotimes [probe tokenizer-hash-capacity]
      (when searching
        (let [slot (mod (+ base probe) tokenizer-hash-capacity)
              token (az/index tokenizer-vocabulary-slots slot)]
          (cond
            (ak/== token tokenizer-empty-id) (ak/= searching false)
            (tokenizer-token-equals-range token start length)
            (do (ak/= result token) (ak/= searching false)))
          (when (ak/! searching) (ak/break)))))
    result))

(az/defn tokenizer-find-concat :u32
  [[left-start :usize]
   [left-length :usize]
   [right-start :usize]
   [right-length :usize]]
  (let [base
        (ak/as (ak/intCast
                (mod (tokenizer-hash-concat
                      left-start left-length right-start right-length)
                     tokenizer-hash-capacity))
               :usize)
        ^:var result (ak/u32 tokenizer-empty-id)
        ^:var searching (ak/bool true)]
    (dotimes [probe tokenizer-hash-capacity]
      (when searching
        (let [slot (mod (+ base probe) tokenizer-hash-capacity)
              token (az/index tokenizer-vocabulary-slots slot)]
          (cond
            (ak/== token tokenizer-empty-id) (ak/= searching false)
            (tokenizer-token-equals-concat
             token left-start left-length right-start right-length)
            (do (ak/= result token) (ak/= searching false)))
          (when (ak/! searching) (ak/break)))))
    result))

(az/defn tokenizer-pair-key :u64
  [[left :u32]
   [right :u32]]
  (+ (ak/<< (ak/as left :u64) 32) (ak/as right :u64)))

(az/defn tokenizer-pair-slot :usize
  [[pair :u64]
   [probe :usize]]
  (ak/as (ak/intCast
          (mod (+ (ak/*% pair 11400714819323198485)
                  (ak/as probe :u64))
               tokenizer-hash-capacity))
         :usize))

(az/defn tokenizer-insert-merge! :bool
  [[left :u32]
   [right :u32]
   [rank :u32]
   [token :u32]]
  (let [pair (tokenizer-pair-key left right)
        ^:var inserted (ak/bool false)]
    (dotimes [probe tokenizer-hash-capacity]
      (when (ak/! inserted)
        (let [slot (tokenizer-pair-slot pair probe)]
          (when (ak/== (az/index tokenizer-merge-pairs slot)
                       tokenizer-empty-pair)
            (ak/= (az/index tokenizer-merge-pairs slot) pair)
            (ak/= (az/index tokenizer-merge-ranks slot) rank)
            (ak/= (az/index tokenizer-merge-tokens slot) token)
            (ak/= inserted true)
            (ak/break)))))
    inserted))

(az/defn tokenizer-find-merge TokenizerMerge
  [[left :u32]
   [right :u32]]
  (let [pair (tokenizer-pair-key left right)
        ^:var found (ak/bool false)
        ^:var searching (ak/bool true)
        ^:var rank (ak/u32 0xffffffff)
        ^:var token (ak/u32 0)]
    (dotimes [probe tokenizer-hash-capacity]
      (when searching
        (let [slot (tokenizer-pair-slot pair probe)
              candidate (az/index tokenizer-merge-pairs slot)]
          (cond
            (ak/== candidate tokenizer-empty-pair) (ak/= searching false)
            (ak/== candidate pair)
            (do
              (ak/= found true)
              (ak/= searching false)
              (ak/= rank (az/index tokenizer-merge-ranks slot))
              (ak/= token (az/index tokenizer-merge-tokens slot))))
          (when (ak/! searching) (ak/break)))))
    (TokenizerMerge {:found found :rank rank :token token})))

(az/defn initialize-tokenizer! :bool
  "Build exact GPT-2 BPE vocabulary and merge indexes directly from the mapped
  GGUF metadata. This is the tokenizer used by both the game and bake-off."
  []
  (let [tokens-index (find-metadata "tokenizer.ggml.tokens")
        merges-index (find-metadata "tokenizer.ggml.merges")
        tokens-info
        (az/index metadata-catalog
                  (if (< tokens-index metadata-catalog-count) tokens-index 0))
        merges-info
        (az/index metadata-catalog
                  (if (< merges-index metadata-catalog-count) merges-index 0))
        ^:var valid (ak/bool (and (< tokens-index metadata-catalog-count)
                   (< merges-index metadata-catalog-count)
                   (ak/== (az/field tokens-info value_type) 9)
                   (ak/== (az/field tokens-info element_type) 8)
                   (ak/== (az/field tokens-info element_count)
                          tokenizer-vocabulary-capacity)
                   (ak/== (az/field merges-info value_type) 9)
                   (ak/== (az/field merges-info element_type) 8)
                   (<= (az/field merges-info element_count)
                       tokenizer-vocabulary-capacity)))
        ^:var cursor (ak/usize (if valid (az/field tokens-info value_start) 0))]
    (ak/= tokenizer-valid false)
    (ak/= tokenizer-token-count 0)
    (ak/= tokenizer-merge-count 0)
    (dotimes [slot tokenizer-hash-capacity]
      (ak/= (az/index tokenizer-vocabulary-slots slot) tokenizer-empty-id)
      (ak/= (az/index tokenizer-merge-pairs slot) tokenizer-empty-pair))
    (when valid
      (dotimes [token tokenizer-vocabulary-capacity]
        (when valid
          (let [length64 (model-u64-at cursor)
                start (+ cursor 8)]
            (if (or (> length64 65535)
                    (> (+ start (ak/as (ak/intCast length64) :usize))
                       model-byte-count))
              (ak/= valid false)
              (do
                (ak/= (az/index tokenizer-token-starts token)
                      (ak/intCast start))
                (ak/= (az/index tokenizer-token-lengths token)
                      (ak/intCast length64))
                (ak/= tokenizer-token-count (ak/intCast (+ token 1)))
                (ak/= cursor (+ start (ak/as (ak/intCast length64) :usize))))))))
      (dotimes [token tokenizer-vocabulary-capacity]
        (when valid
          (ak/= valid (tokenizer-insert-vocabulary! (ak/intCast token)))))
      (ak/= cursor (az/field merges-info value_start))
      (dotimes [merge-index (az/field merges-info element_count)]
        (when valid
          (let [length64 (model-u64-at cursor)
                length (ak/as (ak/intCast length64) :usize)
                start (+ cursor 8)
                ^:var separator (ak/usize length)]
            (when (> (+ start length) model-byte-count)
              (ak/= valid false))
            (when valid
              (dotimes [index length]
                (when (and (ak/== separator length)
                           (ak/== (az/index (az/unwrap model-bytes)
                                           (+ start index))
                                  32))
                  (ak/= separator index)))
              (if (or (ak/== separator 0) (>= separator (- length 1)))
                (ak/= valid false)
                (let [right-start (+ start separator 1)
                      right-length (- length separator 1)
                      left (tokenizer-find-range start separator)
                      right (tokenizer-find-range right-start right-length)
                      merged (tokenizer-find-concat
                              start separator right-start right-length)]
                  (if (or (ak/== left tokenizer-empty-id)
                          (ak/== right tokenizer-empty-id)
                          (ak/== merged tokenizer-empty-id)
                          (ak/! (tokenizer-insert-merge!
                                 left right (ak/intCast merge-index) merged)))
                    (ak/= valid false)
                    (ak/= tokenizer-merge-count
                          (ak/intCast (+ merge-index 1)))))))
            (ak/= cursor (+ start length))))))
    (ak/= tokenizer-valid valid)
    valid))

(az/defn tokenizer-summary TokenizerSummary
  []
  (TokenizerSummary
   {:valid tokenizer-valid
    :token_count tokenizer-token-count
    :merge_count tokenizer-merge-count
    :vocabulary_slots (ak/intCast tokenizer-hash-capacity)
    :merge_slots (ak/intCast tokenizer-hash-capacity)}))

(az/defn configure-model-profile! :bool
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
        kv-index (find-tensor (if (ak/== hidden 768)
                                "blk.10.attn_k.weight" "blk.5.attn_k.weight"))
        kv-heads
        (if (and (> attention-heads 0) (> hidden 0)
                 (ak/== (mod hidden (ak/max attention-heads 1)) 0)
                 (< kv-index tensor-catalog-count)
                 (ak/== (az/field (az/index tensor-catalog kv-index) dimension_count) 2)
                 (ak/== (az/index (az/field (az/index tensor-catalog kv-index) dimensions) 0) hidden)
                 (ak/== (mod (az/index (az/field (az/index tensor-catalog kv-index) dimensions) 1)
                             (/ hidden attention-heads)) 0))
          (ak/as (ak/intCast
            (/ (az/index (az/field (az/index tensor-catalog kv-index) dimensions) 1)
               (/ hidden attention-heads))) :u32)
          (ak/as 0 :u32))
        profile
        (cond
          (and (ak/== hidden 768) (ak/== ffn 2048) (ak/== layers 32)
               (ak/== inner 1536) (ak/== state 128) (ak/== groups 1)
               (ak/== heads 48) (ak/== attention-heads 12)
               (ak/== kv-heads 4))
          model-profile-granite-h-350m

          (and (ak/== hidden 1536) (ak/== ffn 4096) (ak/== layers 40)
               (ak/== inner 3072) (ak/== state 128) (ak/== groups 1)
               (ak/== heads 48) (ak/== attention-heads 12)
               (ak/== kv-heads 4))
          model-profile-granite-h-1b

          (and (ak/== hidden 2048) (ak/== ffn 8192) (ak/== layers 40)
               (ak/== inner 4096) (ak/== state 128) (ak/== groups 1)
               (ak/== heads 64) (ak/== attention-heads 32)
               (ak/== kv-heads 8))
          model-profile-granite-h-micro

          :else model-profile-none)
        ^:var valid (ak/bool (ak/!= profile model-profile-none))]
    (ak/= model-profile-id profile)
    (when valid
      (ak/= model-hidden-size (ak/intCast hidden))
      (ak/= model-ffn-size (ak/intCast ffn))
      (ak/= model-layer-count (ak/intCast layers))
      (ak/= model-mamba-layer-count (- model-layer-count 4))
      (ak/= model-mamba-inner-size (ak/intCast inner))
      (ak/= model-mamba-conv-size
            (+ model-mamba-inner-size (* 2 (ak/as state :usize))))
      (ak/= model-mamba-projection-size
            (+ (* 2 model-mamba-inner-size)
               (* 2 (ak/as state :usize))
               (ak/as heads :usize)))
      (ak/= model-mamba-head-count (ak/intCast heads))
      (ak/= model-mamba-head-size
            (/ model-mamba-inner-size model-mamba-head-count))
      (ak/= model-mamba-state-size (ak/intCast state))
      (ak/= model-mamba-recurrent-size
            (* model-mamba-head-count model-mamba-head-size
               model-mamba-state-size))
      (ak/= model-mamba-conv-state-size (* model-mamba-conv-size 3))
      (ak/= model-attention-layer-count 4)
      (ak/= model-attention-head-count (ak/intCast attention-heads))
      (ak/= model-attention-kv-head-count (ak/intCast kv-heads))
      (ak/= model-attention-head-size
            (/ model-hidden-size model-attention-head-count))
      (ak/= model-attention-kv-size
            (* model-attention-kv-head-count model-attention-head-size))
      (ak/= model-attention-scale
            (metadata-f32
             (find-metadata "granitehybrid.attention.scale")
             (if (ak/== profile model-profile-granite-h-1b)
               0.0078125
               0.015625)))
      (ak/= model-residual-multiplier
            (metadata-f32
             (find-metadata "granitehybrid.residual_scale")
             (if (ak/== profile model-profile-granite-h-350m) 0.246 0.22)))
      (ak/= action-head-input-count
            (* action-head-token-count model-hidden-size))
      (ak/= action-head-weight-count
            (* action-head-output-count action-head-input-count))
      (ak/= team-head-weight-count
            (* team-head-output-count action-head-input-count))
      (ak/= sequence-mamba-floats
            (* sequence-racer-count model-mamba-layer-count
               model-mamba-recurrent-size))
      (ak/= sequence-conv-floats
            (* sequence-racer-count model-mamba-layer-count
               model-mamba-conv-state-size))
      (ak/= sequence-kv-floats
            (* sequence-racer-count model-attention-layer-count
               sequence-capacity model-attention-kv-size))
      (ak/= sequence-total-floats
            (+ sequence-mamba-floats sequence-conv-floats
               (* 2 sequence-kv-floats)))
      (ak/= sequence-total-bytes
            (* sequence-total-floats (ak/sizeOf :f32))))
    (when valid
      (ak/= valid (initialize-tokenizer!)))
    (when (ak/! valid)
      (ak/= model-profile-id model-profile-none))
    valid))

(az/defn model-profile-summary ModelProfileSummary
  "Expose the active dimensions used by the native game and bake-off."
  []
  (ModelProfileSummary
   {:valid (ak/!= model-profile-id model-profile-none)
    :profile model-profile-id
    :hidden_size (ak/intCast model-hidden-size)
    :ffn_size (ak/intCast model-ffn-size)
    :layer_count (ak/intCast model-layer-count)
    :mamba_layer_count (ak/intCast model-mamba-layer-count)
    :mamba_inner_size (ak/intCast model-mamba-inner-size)
    :mamba_projection_size (ak/intCast model-mamba-projection-size)
    :mamba_conv_size (ak/intCast model-mamba-conv-size)
    :mamba_head_count (ak/intCast model-mamba-head-count)
    :mamba_head_size (ak/intCast model-mamba-head-size)
    :attention_layer_count (ak/intCast model-attention-layer-count)
    :attention_head_count (ak/intCast model-attention-head-count)
    :attention_kv_head_count (ak/intCast model-attention-kv-head-count)
    :attention_head_size (ak/intCast model-attention-head-size)
    :sequence_state_bytes sequence-total-bytes}))

(az/defn ascii-byte-token :u32
  "Map one ASCII byte to the model's exact GPT-2 base-byte token id."
  [[byte :u8]]
  (cond
    (and (>= byte 33) (<= byte 126)) (- (ak/as byte :u32) 33)
    (ak/== byte 32) 220
    (ak/== byte 10) 198
    (ak/== byte 9) 197
    (ak/== byte 13) 201
    :else 100269))

(az/defn tokenize-compact-ascii TokenizationReport
  "Encode bounded readable ASCII with Granite's exact GGUF GPT-2 BPE merges.
  Before a model is loaded the base-byte result remains available for parser
  probes; live game and bake-off paths require the initialized native index."
  [[bytes [:pointer {:size :c :const? true} :u8]]
   [length :usize]]
  (let [count (ak/min length tokenizer-capacity)
        ^:var token-count (ak/usize count)
        ^:var valid (ak/bool true)
        ^:var unsupported (ak/u16 (ak/intCast length))
        ^:var tokens (ak/as (std-mem/zeroes (az/type [:array 160 :u32])) [:array 160 :u32])]
    (dotimes [index count]
      (let [byte (az/index bytes index)
            token (ascii-byte-token byte)]
        (ak/= (az/index tokens index) token)
        (when (and valid (ak/== token 100269))
          (ak/= valid false)
          (ak/= unsupported (ak/intCast index)))))
    (dotimes [_ count]
      (when (and valid tokenizer-valid (> token-count 1))
        (let [^:var found (ak/bool false)
              ^:var best-rank (ak/u32 0xffffffff)
              ^:var best-left (ak/u32 0)
              ^:var best-right (ak/u32 0)
              ^:var best-token (ak/u32 0)]
          (dotimes [index (- token-count 1)]
            (let [left (az/index tokens index)
                  right (az/index tokens (+ index 1))
                  merge (tokenizer-find-merge left right)]
              (when (and (az/field merge found)
                         (< (az/field merge rank) best-rank))
                (ak/= found true)
                (ak/= best-rank (az/field merge rank))
                (ak/= best-left left)
                (ak/= best-right right)
                (ak/= best-token (az/field merge token)))))
          (when found
            (let [^:var input-index (ak/usize 0)
                  ^:var output-index (ak/usize 0)]
              (ak/while (< input-index token-count)
                (if (and (< (+ input-index 1) token-count)
                         (ak/== (az/index tokens input-index) best-left)
                         (ak/== (az/index tokens (+ input-index 1)) best-right))
                  (do
                    (ak/= (az/index tokens output-index) best-token)
                    (ak/= input-index (+ input-index 2)))
                  (do
                    (ak/= (az/index tokens output-index)
                          (az/index tokens input-index))
                    (ak/= input-index (+ input-index 1))))
                (ak/= output-index (+ output-index 1)))
              (ak/= token-count output-index))))))
    (TokenizationReport
     {:valid valid
      :truncated (> length tokenizer-capacity)
      :byte_count (ak/intCast length)
      :token_count (ak/intCast token-count)
      :unsupported_index unsupported
      :reserved 0
      :tokens tokens})))

(az/defn tensor-name-byte :u8
  "Return one UTF-8 byte from a tensor name for JVM/native inspection."
  [[tensor-index :usize]
   [byte-index :usize]]
  (if (or (ak/== model-bytes ak/null)
          (>= tensor-index tensor-catalog-count)
          (>= byte-index
              (az/field (az/index tensor-catalog tensor-index) name_length)))
    0
    (az/index (az/unwrap model-bytes)
              (+ (az/field (az/index tensor-catalog tensor-index) name_start)
                 byte-index))))

(az/defn tensor-name-equals :bool
  [[tensor-index :usize]
   [expected [:pointer {:size :c :const? true} :u8]]]
  (if (or (ak/== model-bytes ak/null)
          (>= tensor-index tensor-catalog-count))
    false
    (let [length (az/field (az/index tensor-catalog tensor-index) name_length)
          ^:var equal (ak/bool true)]
      (dotimes [byte-index length]
        (when (ak/!= (tensor-name-byte tensor-index byte-index)
                     (az/index expected byte-index))
          (ak/= equal false)))
      (and equal (ak/== (az/index expected length) 0)))))

(az/defn find-tensor :usize
  "Resolve a GGUF tensor by its exact zero-terminated name."
  [[expected [:pointer {:size :c :const? true} :u8]]]
  (let [^:var found (ak/usize tensor-not-found)]
    (dotimes [tensor-index tensor-catalog-count]
      (when (and (ak/== found tensor-not-found)
                 (tensor-name-equals tensor-index expected))
        (ak/= found tensor-index)))
    found))

(az/defn load-model! GgufSummary
  "Map and validate the pinned GGUF, falling back to an owned native read."
  [[path [:pointer {:size :c :const? true} :u8]]]
  (unload-model!)
  (let [file (runtime/fopen path "rb")]
    (if (ak/== file ak/null)
      (do
        (ak/= model-summary
              (GgufSummary
               {:loaded false :valid false :error_code model-file-not-found
                :version 0 :tensor_count 0 :metadata_count 0
                :f32_tensors 0 :q4_0_tensors 0 :q6_k_tensors 0
                :descriptor_end 0 :data_offset 0 :file_size 0}))
        model-summary)
      (do
        (ak/= :_ (runtime/fseek file 0 2))
        (let [signed-size (runtime/ftell file)]
          (ak/= :_ (runtime/fseek file 0 0))
          (if (<= signed-size 0)
            (ak/= model-summary
                  (GgufSummary
                   {:loaded true :valid false :error_code model-file-empty
                    :version 0 :tensor_count 0 :metadata_count 0
                    :f32_tensors 0 :q4_0_tensors 0 :q6_k_tensors 0
                    :descriptor_end 0 :data_offset 0 :file_size 0}))
            (let [size (ak/as (ak/intCast signed-size) :usize)
                  mapping
                  (catch
                   (std-posix/mmap
                    ak/null size
                    {:READ true}
                    {:TYPE :.PRIVATE}
                    (runtime/fileno file)
                    0)
                   ak/null)]
              (if (ak/!= mapping ak/null)
                (let [bytes
                      (ak/as (ak/ptrCast
                              (az/field (az/unwrap mapping) ptr))
                             (az/type [:c-pointer :u8]))]
                  (ak/= model-bytes bytes)
                  (ak/= model-byte-count size)
                  (ak/= model-storage model-storage-mapped)
                  (ak/= model-summary (parse-gguf bytes size)))
                (let [allocation (runtime/malloc size)]
                  (if (ak/== allocation ak/null)
                    (ak/= model-summary
                          (GgufSummary
                           {:loaded true :valid false
                            :error_code model-allocation-failed
                            :version 0 :tensor_count 0 :metadata_count 0
                            :f32_tensors 0 :q4_0_tensors 0 :q6_k_tensors 0
                            :descriptor_end 0 :data_offset 0 :file_size size}))
                    (let [bytes (az/cast allocation [:c-pointer :u8])
                          count (runtime/fread bytes 1 size file)]
                      (if (ak/== count size)
                        (do
                          (ak/= model-bytes bytes)
                          (ak/= model-byte-count size)
                          (ak/= model-storage model-storage-owned)
                          (ak/= model-summary (parse-gguf bytes size)))
                        (do
                          (runtime/free allocation)
                          (ak/= model-summary
                                (GgufSummary
                                 {:loaded true :valid false
                                  :error_code model-file-read-failed
                                  :version 0 :tensor_count 0 :metadata_count 0
                                  :f32_tensors 0 :q4_0_tensors 0
                                  :q6_k_tensors 0 :descriptor_end 0
                                  :data_offset 0 :file_size size})))))))))))
        (when (and (az/field model-summary valid)
                   (ak/! (configure-model-profile!)))
          (ak/= (az/field model-summary valid) false)
          (ak/= (az/field model-summary error_code) model-profile-unsupported))
        (ak/= :_ (runtime/fclose file))
        model-summary))))

(az/defn inference-summary GgufSummary
  []
  model-summary)

(az/defn- q4-0-dot :f32
  "SIMD Q4_0 dot product for one GGML block of 32 values. The packed low
  and high nibbles become two 16-lane vectors, preserving GGML's layout while
  allowing Zig to use the host's native vector instructions."
  [[block [:pointer {:size :c :const? true} :u8]]
   [input [:pointer {:size :c :const? true} :f32]]]
  (let [scale-bits (+ (ak/as (az/index block 0) :u16)
                      (* (ak/as (az/index block 1) :u16) 256))
        scale (ak/as (ak/floatCast (ak/as (ak/bitCast scale-bits) :f16)) :f32)
        packed-bytes (ak/as (ak/bitCast
                      (az/deref
                       (az/cast (+ block 2)
                                [:pointer {:size :one :const? true}
                                 [:array 16 :u8]]))) [:vector 16 :u8])
        low-unsigned
        (ak/as (ak/& packed-bytes
              (ak/as (ak/splat 15) (az/type [:vector 16 :u8]))) [:vector 16 :u8])
        high-unsigned
        (ak/as (ak/>> packed-bytes
               (ak/as (ak/splat 4) (az/type [:vector 16 :u8]))) [:vector 16 :u8])
        low-signed
        (ak/as (- (ak/as (ak/intCast low-unsigned)
                  (az/type [:vector 16 :i16]))
           (ak/as (ak/splat 8) (az/type [:vector 16 :i16]))) [:vector 16 :i16])
        high-signed
        (ak/as (- (ak/as (ak/intCast high-unsigned)
                  (az/type [:vector 16 :i16]))
           (ak/as (ak/splat 8) (az/type [:vector 16 :i16]))) [:vector 16 :i16])
        low-values
        (ak/as (ak/as (ak/floatFromInt low-signed)
               (az/type [:vector 16 :f32])) [:vector 16 :f32])
        high-values
        (ak/as (ak/as (ak/floatFromInt high-signed)
               (az/type [:vector 16 :f32])) [:vector 16 :f32])
        low-input (ak/as (ak/bitCast
                   (az/deref
                    (az/cast input
                             [:pointer {:size :one :const? true}
                              [:array 16 :f32]]))) [:vector 16 :f32])
        high-input (ak/as (ak/bitCast
                    (az/deref
                     (az/cast (+ input 16)
                              [:pointer {:size :one :const? true}
                               [:array 16 :f32]]))) [:vector 16 :f32])]
    (* scale
       (ak/reduce :.Add
                  (+ (* low-values low-input)
                     (* high-values high-input))))))

(az/defn- q4-0-value :f32
  "Decode one value from a GGML Q4_0 block without allocating."
  [[block [:pointer {:size :c :const? true} :u8]]
   [index :usize]]
  (if (>= index 32)
    0.0
    (let [scale-bits (+ (ak/as (az/index block 0) :u16)
                        (* (ak/as (az/index block 1) :u16) 256))
          scale (ak/as (ak/floatCast (ak/as (ak/bitCast scale-bits) :f16))
                       :f32)
          packed-byte (az/index block (+ 2 (mod index 16)))
          quantized (if (< index 16)
                      (mod packed-byte 16)
                      (/ packed-byte 16))]
      (* scale
         (ak/as (ak/floatFromInt (- (ak/as quantized :i16) 8))
                :f32)))))

(az/defn rms-norm! :void
  "Allocation-free RMSNorm over one dense activation vector."
  [[output [:c-pointer :f32]]
   [input [:pointer {:size :c :const? true} :f32]]
   [weights [:pointer {:size :c :const? true} :f32]]
   [length :usize]
   [epsilon :f32]]
  (let [^:var square-sum (ak/f32 0.0)]
    (dotimes [index length]
      (ak/= square-sum (+ square-sum (* (az/index input index)
                                       (az/index input index)))))
    (let [inverse-rms (/ 1.0 (std-math/sqrt (+ (/ square-sum
                                                   (ak/as (ak/floatFromInt length) :f32))
                                                epsilon)))]
      (dotimes [index length]
        (ak/= (az/index output index)
              (* (az/index input index) inverse-rms (az/index weights index)))))))

(az/defn softmax! :void
  "Stable in-place softmax used by the attention reference path."
  [[values [:c-pointer :f32]]
   [length :usize]]
  (when (> length 0)
    (let [^:var maximum (ak/f32 (az/index values 0))
          ^:var total (ak/f32 0.0)]
      (dotimes [index length]
        (ak/= maximum (ak/max maximum (az/index values index))))
      (dotimes [index length]
        (let [value (std-math/exp (- (az/index values index) maximum))]
          (ak/= (az/index values index) value)
          (ak/= total (+ total value))))
      (dotimes [index length]
        (ak/= (az/index values index) (/ (az/index values index) total))))))

(az/defn- softplus :f32
  [[value :f32]]
  (if (> value 20.0)
    value
    (std-math/log1p (std-math/exp value))))

(az/defn mamba-selective-step! :void
  "One exact recurrent Mamba-2 selective-state update for the model's
  single-group layout. State is `[head][head-component][ssm-component]`."
  [[output [:c-pointer :f32]]
   [state [:c-pointer :f32]]
   [hidden [:pointer {:size :c :const? true} :f32]]
   [dt [:pointer {:size :c :const? true} :f32]]
   [a [:pointer {:size :c :const? true} :f32]]
   [b [:pointer {:size :c :const? true} :f32]]
   [c [:pointer {:size :c :const? true} :f32]]
   [d [:pointer {:size :c :const? true} :f32]]
   [dt-bias [:pointer {:size :c :const? true} :f32]]
   [head-count :usize]
   [head-dimension :usize]
   [state-size :usize]]
  (dotimes [head head-count]
    (let [delta (softplus (+ (az/index dt head) (az/index dt-bias head)))
          decay (std-math/exp (* delta (az/index a head)))]
      (dotimes [component head-dimension]
        (let [hidden-index (+ (* head head-dimension) component)
              hidden-value (az/index hidden hidden-index)
              ^:var total (ak/f32 0.0)]
          (dotimes [state-component state-size]
            (let [state-index (+ (* hidden-index state-size) state-component)
                  next-state (+ (* (az/index state state-index) decay)
                                (* delta (az/index b state-component)
                                   hidden-value))]
              (ak/= (az/index state state-index) next-state)
              (ak/= total (+ total
                             (* next-state (az/index c state-component))))))
          (ak/= (az/index output hidden-index)
                (+ total (* hidden-value (az/index d head)))))))))

(az/defn tensor-rms-norm-gated! :bool
  "Apply Granite's SiLU gate before weighted RMS normalization."
  [[output [:c-pointer :f32]]
   [hidden [:pointer {:size :c :const? true} :f32]]
   [gate [:pointer {:size :c :const? true} :f32]]
   [weights-index :usize]
   [length :usize]
   [epsilon :f32]]
  (if (or (>= weights-index tensor-catalog-count)
          (ak/!= (az/field (az/index tensor-catalog weights-index) ggml_type) 0))
    false
    (let [^:var square-sum (ak/f32 0.0)]
      (dotimes [index length]
        (let [value (* (az/index hidden index)
                       (silu (az/index gate index)))]
          (ak/= (az/index output index) value)
          (ak/= square-sum (+ square-sum (* value value)))))
      (let [inverse-rms (/ 1.0
                           (std-math/sqrt
                            (+ (/ square-sum
                                  (ak/as (ak/floatFromInt length) :f32))
                               epsilon)))]
        (dotimes [index length]
          (ak/= (az/index output index)
                (* (az/index output index) inverse-rms
                   (tensor-element weights-index index)))))
      true)))

(az/defn attention-layer? :bool
  "Whether one Granite hybrid layer uses causal GQA instead of Mamba-2."
  [[layer :usize]]
  (if (or (ak/== model-profile-id model-profile-granite-h-1b)
          (ak/== model-profile-id model-profile-granite-h-micro))
    (or (ak/== layer 5)
        (ak/== layer 15)
        (ak/== layer 25)
        (ak/== layer 35))
    (or (ak/== layer 10)
        (ak/== layer 13)
        (ak/== layer 17)
        (ak/== layer 27))))

(az/defn attention-layer-slot :usize
  "Map one supported profile's attention layer to compact KV slot 0..3."
  [[layer :usize]]
  (if (ak/! (attention-layer? layer))
    model-attention-layer-count
    (let [^:var slot (ak/usize 0)]
      (dotimes [candidate layer]
        (when (attention-layer? candidate)
          (ak/= slot (+ slot 1))))
      slot)))

(az/defn attention-layers-before :usize
  [[layer :usize]]
  (let [^:var count (ak/usize 0)]
    (dotimes [candidate layer]
      (when (attention-layer? candidate)
        (ak/= count (+ count 1))))
    count))

(az/defn layer-base-index :usize
  "Resolve the first tensor in a pinned Granite layer without name scans."
  [[layer :usize]]
  (if (>= layer model-layer-count)
    tensor-not-found
    (- (+ 2 (* layer 13)) (* (attention-layers-before layer) 4))))

(az/defn layer-attention-norm-index :usize
  [[layer :usize]]
  (let [base (layer-base-index layer)]
    (if (ak/== base tensor-not-found)
      tensor-not-found
      (+ base (if (attention-layer? layer)
                (ak/as 1 :usize)
                (ak/as 0 :usize))))))

(az/defn layer-ffn-down-index :usize
  [[layer :usize]]
  (let [base (layer-base-index layer)]
    (if (ak/== base tensor-not-found)
      tensor-not-found
      (+ base (if (attention-layer? layer)
                (ak/as 5 :usize)
                (ak/as 1 :usize))))))

(az/defn layer-ffn-gate-index :usize
  [[layer :usize]]
  (let [base (layer-base-index layer)]
    (if (ak/== base tensor-not-found)
      tensor-not-found
      (+ base (if (attention-layer? layer)
                (ak/as 6 :usize)
                (ak/as 2 :usize))))))

(az/defn layer-ffn-norm-index :usize
  [[layer :usize]]
  (let [base (layer-base-index layer)]
    (if (ak/== base tensor-not-found)
      tensor-not-found
      (+ base (if (attention-layer? layer)
                (ak/as 7 :usize)
                (ak/as 3 :usize))))))

(az/defn layer-ffn-up-index :usize
  [[layer :usize]]
  (let [base (layer-base-index layer)]
    (if (ak/== base tensor-not-found)
      tensor-not-found
      (+ base (if (attention-layer? layer)
                (ak/as 8 :usize)
                (ak/as 4 :usize))))))

(az/defn free-sequences! :void
  "Release all native racer cognition state while leaving shared weights loaded."
  []
  (when (ak/!= sequence-memory ak/null)
    (runtime/free
     (az/cast (az/unwrap sequence-memory) [:* :anyopaque])))
  (ak/= sequence-memory ak/null)
  (ak/= sequence-memory-floats 0)
  (dotimes [racer sequence-racer-count]
    (ak/= (az/index sequence-positions racer) 0)))

(az/defn reset-all-sequences! :bool
  "Clear every recurrent, convolution, and KV state in-place."
  []
  (let [initialized (ak/!= sequence-memory ak/null)]
    (when initialized
      (let [memory (az/unwrap sequence-memory)]
        (ak/memset (az/slice memory 0 sequence-memory-floats) 0.0))
      (dotimes [racer sequence-racer-count]
        (ak/= (az/index sequence-positions racer) 0))
      (ak/memset (az/slice action-head-inputs 0 (* sequence-racer-count action-head-input-count)) 0.0))
    initialized))

(az/defn initialize-sequences! :bool
  "Own one shared allocation containing thirty isolated model sequence states:
  twenty drivers followed by ten team strategists."
  []
  (free-sequences!)
  (let [allocation (runtime/malloc sequence-total-bytes)
        ^:var initialized (ak/bool false)]
    (when (ak/!= allocation ak/null)
      (ak/= sequence-memory (az/cast allocation [:c-pointer :f32]))
      (ak/= sequence-memory-floats sequence-total-floats)
      (ak/= initialized (reset-all-sequences!)))
    initialized))

(az/defn reset-sequence! :bool
  "Clear one racer's independent recurrent and KV history."
  [[racer :usize]]
  (let [valid (and (< racer sequence-racer-count)
                   (ak/!= sequence-memory ak/null))]
    (when valid
      (let [memory (az/unwrap sequence-memory)
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
        (ak/memset (az/slice memory mamba-start (+ mamba-start mamba-count)) 0.0)
        (ak/memset (az/slice memory conv-start (+ conv-start conv-count)) 0.0)
        (ak/memset (az/slice memory key-start (+ key-start kv-count)) 0.0)
        (ak/memset (az/slice memory value-start (+ value-start kv-count)) 0.0)
        (ak/memset (az/slice action-head-inputs (* racer action-head-input-count)
                    (* (+ racer 1) action-head-input-count)) 0.0)
        (ak/= (az/index sequence-positions racer) 0)))
    valid))

(az/defn copy-last-hidden! :bool
  "Copy one racer's most recent final normalized hidden state for offline
  training or inspection. The caller owns `output` and at least
  model-hidden-size floats (768, 1536 or 2048 for supported profiles)."
  [[racer :usize]
   [output [:c-pointer :f32]]]
  (let [valid (and (< racer sequence-racer-count)
                   (> (az/index sequence-positions racer) 0))]
    (when valid
      (dotimes [index model-hidden-size]
        (ak/= (az/index output index)
              (az/index action-head-inputs
                        (+ (* racer action-head-input-count)
                           (* (ak/as (ak/min
                                      (- (az/index sequence-positions racer) 1)
                                      (ak/as 7 :u16))
                                     :usize)
                              model-hidden-size)
                           index)))))
    valid))

(az/defn copy-action-features! :bool
  "Copy the fixed action-head feature buffer. Sequential extraction fills all
  eight slots; fused extraction fills the first slot and clears the rest."
  [[racer :usize]
   [output [:c-pointer :f32]]]
  (let [valid (and (< racer sequence-racer-count)
                   (> (az/index sequence-positions racer) 0))]
    (when valid
      (dotimes [index action-head-input-count]
        (ak/= (az/index output index)
              (az/index action-head-inputs
                        (+ (* racer action-head-input-count) index)))))
    valid))

(az/defn sequence-summary SequenceSummary
  "Return allocation size and each racer's independent token position."
  []
  (SequenceSummary
   {:initialized (ak/!= sequence-memory ak/null)
    :racer_count (ak/intCast sequence-racer-count)
    :capacity (ak/intCast sequence-capacity)
    :state_bytes (if (ak/== sequence-memory ak/null) 0 sequence-total-bytes)
    :positions sequence-positions}))

(az/defn sequence-mamba-state [:optional [:c-pointer :f32]]
  [[racer :usize]
   [layer :usize]]
  (if (or (ak/== sequence-memory ak/null)
          (>= racer sequence-racer-count)
          (>= layer model-layer-count)
          (attention-layer? layer))
    ak/null
    (+ (az/unwrap sequence-memory)
       (* racer model-mamba-layer-count model-mamba-recurrent-size)
       (* (- layer (attention-layers-before layer))
          model-mamba-recurrent-size))))

(az/defn sequence-conv-state [:optional [:c-pointer :f32]]
  [[racer :usize]
   [layer :usize]]
  (if (or (ak/== sequence-memory ak/null)
          (>= racer sequence-racer-count)
          (>= layer model-layer-count)
          (attention-layer? layer))
    ak/null
    (+ (az/unwrap sequence-memory)
       sequence-mamba-floats
       (* racer model-mamba-layer-count model-mamba-conv-state-size)
       (* (- layer (attention-layers-before layer))
          model-mamba-conv-state-size))))

(az/defn sequence-key-cache [:optional [:c-pointer :f32]]
  [[racer :usize]
   [layer :usize]]
  (let [slot (attention-layer-slot layer)]
    (if (or (ak/== sequence-memory ak/null)
            (>= racer sequence-racer-count)
            (>= slot model-attention-layer-count))
      ak/null
      (+ (az/unwrap sequence-memory)
         sequence-mamba-floats sequence-conv-floats
         (* racer model-attention-layer-count sequence-capacity
            model-attention-kv-size)
         (* slot sequence-capacity model-attention-kv-size)))))

(az/defn sequence-value-cache [:optional [:c-pointer :f32]]
  [[racer :usize]
   [layer :usize]]
  (let [slot (attention-layer-slot layer)]
    (if (or (ak/== sequence-memory ak/null)
            (>= racer sequence-racer-count)
            (>= slot model-attention-layer-count))
      ak/null
      (+ (az/unwrap sequence-memory)
         sequence-mamba-floats sequence-conv-floats sequence-kv-floats
         (* racer model-attention-layer-count sequence-capacity
            model-attention-kv-size)
         (* slot sequence-capacity model-attention-kv-size)))))

(az/defn layer-ffn! :bool
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
        ^:var valid (ak/bool (ak/! (or (ak/== norm-index tensor-not-found)
                        (ak/== gate-index tensor-not-found)
                        (ak/== up-index tensor-not-found)
                        (ak/== down-index tensor-not-found))))]
    (when valid
      (ak/= valid
            (tensor-rms-norm! normalized hidden norm-index
                              model-hidden-size model-rms-epsilon)))
    (when valid
      (ak/= valid (tensor-matvec! gate model-ffn-size gate-index normalized)))
    (when valid
      (ak/= valid (tensor-matvec! up model-ffn-size up-index normalized)))
    (when valid
      (dotimes [index model-ffn-size]
        (ak/= (az/index activated index)
              (* (silu (az/index gate index)) (az/index up index))))
      (ak/= valid
            (tensor-matvec! output model-hidden-size down-index activated)))
    (when valid
      (dotimes [index model-hidden-size]
        (ak/= (az/index hidden index)
              (+ (az/index hidden index)
                 (* model-residual-multiplier (az/index output index))))))
    valid))

(az/defn mamba-layer-step! :bool
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
        ^:var a (ak/as (std-mem/zeroes (az/type [:array model-max-mamba-head-count :f32])) [:array model-max-mamba-head-count :f32])
        ^:var d (ak/as (std-mem/zeroes (az/type [:array model-max-mamba-head-count :f32])) [:array model-max-mamba-head-count :f32])
        ^:var dt-bias (ak/as (std-mem/zeroes (az/type [:array model-max-mamba-head-count :f32])) [:array model-max-mamba-head-count :f32])
        ^:var valid (ak/bool (and (< layer model-layer-count)
                   (ak/! (attention-layer? layer))
                   (ak/!= base tensor-not-found)))]
    (when valid
      (ak/= valid
            (tensor-rms-norm! normalized hidden norm-index
                              model-hidden-size model-rms-epsilon)))
    (when valid
      (ak/= valid
            (tensor-matvec! projected model-mamba-projection-size
                            in-index normalized)))
    (when valid
      (dotimes [channel model-mamba-conv-size]
        (let [state-start (* channel 3)
              weight-start (* channel 4)
              current (az/index projected (+ model-mamba-inner-size channel))
              ^:var total (ak/f32 (tensor-element conv-bias-index channel))]
          (dotimes [tap 3]
            (ak/= total
                  (+ total
                     (* (az/index conv-state (+ state-start tap))
                        (tensor-element conv-weight-index
                                        (+ weight-start tap))))))
          (ak/= total
                (+ total
                   (* current
                      (tensor-element conv-weight-index (+ weight-start 3)))))
          (ak/= (az/index conv-state state-start)
                (az/index conv-state (+ state-start 1)))
          (ak/= (az/index conv-state (+ state-start 1))
                (az/index conv-state (+ state-start 2)))
          (ak/= (az/index conv-state (+ state-start 2)) current)
          (ak/= (az/index convolved channel) (silu total))))
      (dotimes [head model-mamba-head-count]
        ;; GGUF conversion already transforms HF A_log into -exp(A_log).
        ;; Applying that transform twice changes the recurrent dynamics.
        (ak/= (az/index a head)
              (tensor-element a-index head))
        (ak/= (az/index d head) (tensor-element d-index head))
        (ak/= (az/index dt-bias head) (tensor-element dt-bias-index head)))
      (mamba-selective-step!
       scan-output recurrent-state convolved
       (+ projected
          (* 2 model-mamba-inner-size)
          (* 2 model-mamba-state-size))
       (ak/& (az/index a 0))
       (+ convolved model-mamba-inner-size)
       (+ convolved model-mamba-inner-size model-mamba-state-size)
       (ak/& (az/index d 0))
       (ak/& (az/index dt-bias 0))
       model-mamba-head-count model-mamba-head-size
       model-mamba-state-size)
      (ak/= valid
            (tensor-rms-norm-gated!
             gated-output scan-output projected mamba-norm-index
             model-mamba-inner-size model-rms-epsilon)))
    (when valid
      (ak/= valid
            (tensor-matvec! branch-output model-hidden-size out-index
                            gated-output)))
    (when valid
      (dotimes [index model-hidden-size]
        (ak/= (az/index hidden index)
              (+ (az/index hidden index)
                 (* model-residual-multiplier
                    (az/index branch-output index))))))
    valid))

(az/defn mamba-ffn-layer! :bool
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

(az/defn attention-layer-step! :bool
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
        ^:var valid (ak/bool (and (< position sequence-capacity)
                   (attention-layer? layer)
                   (ak/!= base tensor-not-found)))]
    (when valid
      (ak/= valid
            (tensor-rms-norm! normalized hidden norm-index
                              model-hidden-size model-rms-epsilon)))
    (when valid
      (ak/= valid
            (tensor-matvec! query model-hidden-size query-index normalized)))
    (when valid
      (ak/= valid
            (tensor-matvec! key model-attention-kv-size key-index normalized)))
    (when valid
      (ak/= valid
            (tensor-matvec! value model-attention-kv-size value-index normalized)))
    (when valid
      (dotimes [component model-attention-kv-size]
        (ak/= (az/index key-cache
                        (+ (* position model-attention-kv-size) component))
              (az/index key component))
        (ak/= (az/index value-cache
                        (+ (* position model-attention-kv-size) component))
              (az/index value component)))
      (dotimes [query-head model-attention-head-count]
        (let [kv-head
              (/ query-head
                 (/ model-attention-head-count
                    model-attention-kv-head-count))
              query-start (* query-head model-attention-head-size)
              kv-start (* kv-head model-attention-head-size)]
          (dotimes [token (+ position 1)]
            (let [cache-start (+ (* token model-attention-kv-size) kv-start)
                  ^:var score (ak/f32 0.0)]
              (dotimes [component model-attention-head-size]
                (ak/= score
                      (+ score
                         (* (az/index query (+ query-start component))
                            (az/index key-cache
                                      (+ cache-start component))))))
              (ak/= (az/index scores token) (* score model-attention-scale))))
          (softmax! scores (+ position 1))
          (dotimes [component model-attention-head-size]
            (let [^:var total (ak/f32 0.0)]
              (dotimes [token (+ position 1)]
                (ak/= total
                      (+ total
                         (* (az/index scores token)
                            (az/index value-cache
                                      (+ (* token model-attention-kv-size)
                                         kv-start component))))))
              (ak/= (az/index attention-output (+ query-start component))
                    total)))))
      (ak/= valid
            (tensor-matvec! branch-output model-hidden-size out-index
                            attention-output)))
    (when valid
      (dotimes [index model-hidden-size]
        (ak/= (az/index hidden index)
              (+ (az/index hidden index)
                 (* model-residual-multiplier
                    (az/index branch-output index))))))
    valid))

(az/defn attention-ffn-layer! :bool
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

(az/defn attention-layer-probe :f32
  "350M-only diagnostic: isolated attention+FFN with a single-token cache.
  Unsupported profiles/tokens return NaN before accessing fixed-size buffers."
  [[layer :usize]
   [token :usize]
   [component :usize]]
  (when (or (ak/!= model-profile-id model-profile-granite-h-350m)
            (>= token model-vocabulary-size))
    (ak/return (std-math/nan :f32)))
  (if (or (ak/! (attention-layer? layer))
          (>= component model-hidden-size))
    0.0
    (let [^:var hidden (ak/as (std-mem/zeroes (az/type [:array 768 :f32])) [:array 768 :f32])
          ^:var normalized (ak/as (std-mem/zeroes (az/type [:array 768 :f32])) [:array 768 :f32])
          ^:var query (ak/as (std-mem/zeroes (az/type [:array 768 :f32])) [:array 768 :f32])
          ^:var key (ak/as (std-mem/zeroes (az/type [:array 256 :f32])) [:array 256 :f32])
          ^:var value (ak/as (std-mem/zeroes (az/type [:array 256 :f32])) [:array 256 :f32])
          ^:var scores (ak/as (std-mem/zeroes (az/type [:array 160 :f32])) [:array 160 :f32])
          ^:var attention-output (ak/as (std-mem/zeroes (az/type [:array 768 :f32])) [:array 768 :f32])
          ^:var branch-output (ak/as (std-mem/zeroes (az/type [:array 768 :f32])) [:array 768 :f32])
          ^:var ffn-gate (ak/as (std-mem/zeroes (az/type [:array 2048 :f32])) [:array 2048 :f32])
          ^:var ffn-up (ak/as (std-mem/zeroes (az/type [:array 2048 :f32])) [:array 2048 :f32])
          ^:var ffn-activated (ak/as (std-mem/zeroes (az/type [:array 2048 :f32])) [:array 2048 :f32])
          ^:var ffn-output (ak/as (std-mem/zeroes (az/type [:array 768 :f32])) [:array 768 :f32])
          cache-elements (* sequence-capacity model-attention-kv-size)
          cache-allocation
          (runtime/malloc (* 2 cache-elements (ak/sizeOf :f32)))]
      (if (ak/== cache-allocation ak/null)
        0.0
        (do
          (defer (runtime/free cache-allocation))
          (let [key-cache (az/cast cache-allocation [:c-pointer :f32])
                value-cache (+ key-cache cache-elements)]
            (dotimes [index (* 2 cache-elements)]
              (ak/= (az/index key-cache index) 0.0))
            (dotimes [index model-hidden-size]
              (ak/= (az/index hidden index)
                    (* 12.0 (embedding-value-kernel token index))))
            (if (attention-ffn-layer!
                 layer 0
                 (ak/& (az/index hidden 0)) key-cache value-cache
                 (ak/& (az/index normalized 0))
                 (ak/& (az/index query 0))
                 (ak/& (az/index key 0))
                 (ak/& (az/index value 0))
                 (ak/& (az/index scores 0))
                 (ak/& (az/index attention-output 0))
                 (ak/& (az/index branch-output 0))
                 (ak/& (az/index ffn-gate 0))
                 (ak/& (az/index ffn-up 0))
                 (ak/& (az/index ffn-activated 0))
                 (ak/& (az/index ffn-output 0)))
              (az/index hidden component)
              0.0)))))))

(az/defn mamba-layer-zero-probe :f32
  "350M-only diagnostic: first-token Mamba branch with zero initial state.
  Unsupported profiles/tokens return NaN before accessing fixed-size buffers."
  [[token :usize]
   [component :usize]]
  (when (or (ak/!= model-profile-id model-profile-granite-h-350m)
            (>= token model-vocabulary-size))
    (ak/return (std-math/nan :f32)))
  (let [^:var residual (ak/as (std-mem/zeroes (az/type [:array 768 :f32])) [:array 768 :f32])
        ^:var normalized (ak/as (std-mem/zeroes (az/type [:array 768 :f32])) [:array 768 :f32])
        ^:var projected (ak/as (std-mem/zeroes (az/type [:array 3376 :f32])) [:array 3376 :f32])
        ^:var convolved (ak/as (std-mem/zeroes (az/type [:array 1792 :f32])) [:array 1792 :f32])
        ^:var scan-output (ak/as (std-mem/zeroes (az/type [:array 1536 :f32])) [:array 1536 :f32])
        ^:var gated-output (ak/as (std-mem/zeroes (az/type [:array 1536 :f32])) [:array 1536 :f32])
        ^:var branch-output (ak/as (std-mem/zeroes (az/type [:array 768 :f32])) [:array 768 :f32])
        ^:var a (ak/as (std-mem/zeroes (az/type [:array 48 :f32])) [:array 48 :f32])
        ^:var d (ak/as (std-mem/zeroes (az/type [:array 48 :f32])) [:array 48 :f32])
        ^:var dt-bias (ak/as (std-mem/zeroes (az/type [:array 48 :f32])) [:array 48 :f32])
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
            (ak/== norm-index tensor-not-found)
            (ak/== in-index tensor-not-found)
            (ak/== conv-weight-index tensor-not-found)
            (ak/== conv-bias-index tensor-not-found)
            (ak/== a-index tensor-not-found)
            (ak/== d-index tensor-not-found)
            (ak/== dt-bias-index tensor-not-found)
            (ak/== mamba-norm-index tensor-not-found)
            (ak/== out-index tensor-not-found))
      0.0
      (let [state-allocation (runtime/malloc (* 196608 (ak/sizeOf :f32)))]
        (if (ak/== state-allocation ak/null)
          0.0
          (do
            (defer (runtime/free state-allocation))
            (let [recurrent-state (az/cast state-allocation [:c-pointer :f32])]
              (dotimes [index 196608]
                (ak/= (az/index recurrent-state index) 0.0))
              (dotimes [index 768]
                (ak/= (az/index residual index)
                      (* 12.0 (embedding-value-kernel token index))))
              (ak/= :_ (tensor-rms-norm!
                       (ak/& (az/index normalized 0))
                       (ak/& (az/index residual 0)) norm-index 768 0.00001))
              (ak/= :_ (tensor-matvec!
                       (ak/& (az/index projected 0)) 3376 in-index
                       (ak/& (az/index normalized 0))))
              (dotimes [channel 1792]
                (ak/= (az/index convolved channel)
                      (silu (+ (* (az/index projected (+ 1536 channel))
                                  (tensor-element conv-weight-index
                                                  (+ (* channel 4) 3)))
                               (tensor-element conv-bias-index channel)))))
              (dotimes [head 48]
                (ak/= (az/index a head)
                      (tensor-element a-index head))
                (ak/= (az/index d head) (tensor-element d-index head))
                (ak/= (az/index dt-bias head)
                      (tensor-element dt-bias-index head)))
              (mamba-selective-step!
               (ak/& (az/index scan-output 0))
               recurrent-state
               (ak/& (az/index convolved 0))
               (ak/& (az/index projected 3328))
               (ak/& (az/index a 0))
               (ak/& (az/index convolved 1536))
               (ak/& (az/index convolved 1664))
               (ak/& (az/index d 0))
               (ak/& (az/index dt-bias 0))
               48 32 128)
              (ak/= :_ (tensor-rms-norm-gated!
                       (ak/& (az/index gated-output 0))
                       (ak/& (az/index scan-output 0))
                       (ak/& (az/index projected 0))
                       mamba-norm-index 1536 0.00001))
              (ak/= :_ (tensor-matvec!
                       (ak/& (az/index branch-output 0)) 768 out-index
                       (ak/& (az/index gated-output 0))))
              (+ (az/index residual component)
                 (* 0.246 (az/index branch-output component))))))))))

(az/defn mamba-layer-zero-full-probe :f32
  "350M-only diagnostic: first-token complete Mamba/FFN block.
  Unsupported profiles/tokens return NaN before accessing fixed-size buffers."
  [[token :usize]
   [component :usize]]
  (when (or (ak/!= model-profile-id model-profile-granite-h-350m)
            (>= token model-vocabulary-size))
    (ak/return (std-math/nan :f32)))
  (if (>= component model-hidden-size)
    0.0
    (let [^:var hidden (ak/as (std-mem/zeroes (az/type [:array 768 :f32])) [:array 768 :f32])
          ^:var normalized (ak/as (std-mem/zeroes (az/type [:array 768 :f32])) [:array 768 :f32])
          ^:var projected (ak/as (std-mem/zeroes (az/type [:array 3376 :f32])) [:array 3376 :f32])
          ^:var convolved (ak/as (std-mem/zeroes (az/type [:array 1792 :f32])) [:array 1792 :f32])
          ^:var scan-output (ak/as (std-mem/zeroes (az/type [:array 1536 :f32])) [:array 1536 :f32])
          ^:var gated-output (ak/as (std-mem/zeroes (az/type [:array 1536 :f32])) [:array 1536 :f32])
          ^:var branch-output (ak/as (std-mem/zeroes (az/type [:array 768 :f32])) [:array 768 :f32])
          ^:var ffn-gate (ak/as (std-mem/zeroes (az/type [:array 2048 :f32])) [:array 2048 :f32])
          ^:var ffn-up (ak/as (std-mem/zeroes (az/type [:array 2048 :f32])) [:array 2048 :f32])
          ^:var ffn-activated (ak/as (std-mem/zeroes (az/type [:array 2048 :f32])) [:array 2048 :f32])
          ^:var ffn-output (ak/as (std-mem/zeroes (az/type [:array 768 :f32])) [:array 768 :f32])
          ^:var conv-state (ak/as (std-mem/zeroes (az/type [:array 5376 :f32])) [:array 5376 :f32])
          state-allocation
          (runtime/malloc (* model-mamba-recurrent-size (ak/sizeOf :f32)))]
      (if (ak/== state-allocation ak/null)
        0.0
        (do
          (defer (runtime/free state-allocation))
          (let [recurrent-state (az/cast state-allocation [:c-pointer :f32])]
            (dotimes [index model-mamba-recurrent-size]
              (ak/= (az/index recurrent-state index) 0.0))
            (dotimes [index model-hidden-size]
              (ak/= (az/index hidden index)
                    (* 12.0 (embedding-value-kernel token index))))
            (if (mamba-ffn-layer!
                 0
                 (ak/& (az/index hidden 0))
                 recurrent-state
                 (ak/& (az/index conv-state 0))
                 (ak/& (az/index normalized 0))
                 (ak/& (az/index projected 0))
                 (ak/& (az/index convolved 0))
                 (ak/& (az/index scan-output 0))
                 (ak/& (az/index gated-output 0))
                 (ak/& (az/index branch-output 0))
                 (ak/& (az/index ffn-gate 0))
                 (ak/& (az/index ffn-up 0))
                 (ak/& (az/index ffn-activated 0))
                 (ak/& (az/index ffn-output 0)))
              (az/index hidden component)
              0.0)))))))

(az/defn- q6-k-value :f32
  "Decode one value from a GGML Q6_K block without allocating."
  [[block [:pointer {:size :c :const? true} :u8]]
   [index :usize]]
  (if (>= index 256)
    0.0
    (let [sub-block (/ index 32)
          within (mod index 32)
          low-index (+ (* (/ sub-block 4) 64)
                       (* (mod sub-block 2) 32)
                       within)
          low-shift (ak/u3 (if (>= (mod sub-block 4) 2) 4 0))
          high-index (+ 128 (* (/ sub-block 4) 32) within)
          high-shift (ak/u3 (ak/intCast (* (mod sub-block 4) 2)))
          low (ak/& (ak/>> (az/index block low-index) low-shift) 15)
          high (ak/& (ak/>> (az/index block high-index) high-shift) 3)
          quantized (- (ak/as (+ low (* high 16)) :i16) 32)
          scale-byte (az/index block (+ 192 (/ index 16)))
          scale (ak/as (ak/bitCast scale-byte) :i8)
          delta-bits (+ (ak/as (az/index block 208) :u16)
                        (* (ak/as (az/index block 209) :u16) 256))
          delta (ak/as (ak/floatCast (ak/as (ak/bitCast delta-bits) :f16))
                       :f32)]
      (* delta
         (ak/as (ak/floatFromInt scale) :f32)
         (ak/as (ak/floatFromInt quantized) :f32)))))

(az/defn- q6-k-dot :f32
  "SIMD dot product for one GGML Q6_K block, sixteen values per signed
  subscale. Decode each packed plane once; preserve q6-k-value's weight layout."
  [[block [:pointer {:size :c :const? true} :u8]]
   [input [:pointer {:size :c :const? true} :f32]]]
  (let [delta-bits (+ (ak/as (az/index block 208) :u16)
                      (* (ak/as (az/index block 209) :u16) 256))
        delta (ak/as (ak/floatCast (ak/as (ak/bitCast delta-bits) :f16)) :f32)
        ^:var total (ak/as (ak/splat (ak/as 0.0 :f32)) [:vector 16 :f32])]
    (dotimes [group 16]
      (let [sub-block (/ group 2)
            within (* (mod group 2) 16)
            low-index (+ (* (/ sub-block 4) 64) (* (mod sub-block 2) 32) within)
            high-index (+ 128 (* (/ sub-block 4) 32) within)
            low-shift (ak/u3 (if (>= (mod sub-block 4) 2) 4 0))
            high-shift (ak/u3 (ak/intCast (* (mod sub-block 4) 2)))
            low-packed (ak/as (ak/bitCast (az/deref (az/cast (+ block low-index)
                                             [:pointer {:size :one :const? true} [:array 16 :u8]]))) [:vector 16 :u8])
            high-packed (ak/as (ak/bitCast (az/deref (az/cast (+ block high-index)
                                              [:pointer {:size :one :const? true} [:array 16 :u8]]))) [:vector 16 :u8])
            low (ak/& (ak/>> low-packed (ak/as (ak/splat low-shift) (az/type [:vector 16 :u3])))
                      (ak/as (ak/splat 15) (az/type [:vector 16 :u8])))
            high (ak/& (ak/>> high-packed (ak/as (ak/splat high-shift) (az/type [:vector 16 :u3])))
                       (ak/as (ak/splat 3) (az/type [:vector 16 :u8])))
            quantized (- (ak/as (ak/intCast (+ low (* high (ak/as (ak/splat 16) (az/type [:vector 16 :u8])))))
                                (az/type [:vector 16 :i16]))
                         (ak/as (ak/splat 32) (az/type [:vector 16 :i16])))
            scale (ak/as (ak/bitCast (az/index block (+ 192 group))) :i8)
            scaled-delta (ak/as (ak/splat (* delta (ak/as (ak/floatFromInt scale) :f32)))
                               (az/type [:vector 16 :f32]))
            values (* scaled-delta (ak/as (ak/floatFromInt quantized) (az/type [:vector 16 :f32])))
            inputs (ak/as (ak/bitCast (az/deref (az/cast (+ input (* group 16))
                                         [:pointer {:size :one :const? true} [:array 16 :f32]]))) [:vector 16 :f32])]
        (ak/= total (+ total (* values inputs)))))
    (ak/reduce :.Add total)))

(az/defn- embedding-value-kernel :f32
  "Read the active profile's embedding width and quantization, not a fixed
  350M row stride. The supported GGUF catalog places embeddings at index1."
  [[token :usize]
   [component :usize]]
  (if (or (ak/== (az/field model-summary valid) false)
          (< tensor-catalog-count 2)
          (>= token model-vocabulary-size)
          (>= component model-hidden-size))
    0.0
    (tensor-element 1 (+ (* token model-hidden-size) component))))

(az/defn embedding-value :f32
  "Inspectable wrapper around the private hot-loop embedding decoder."
  [[token :usize]
   [component :usize]]
  (embedding-value-kernel token component))

(az/defn embedding-row-dot :f32
  "Dot one tied Q6_K token-embedding row with a normalized hidden vector."
  [[token :usize]
   [input [:pointer {:size :c :const? true} :f32]]]
  (if (or (ak/== (az/field model-summary valid) false)
          (< tensor-catalog-count 2)
          (>= token 100352))
    0.0
    (let [tensor (az/index tensor-catalog 1)
          bytes (ak/as (ak/ptrFromInt (az/field tensor data_address)) [:c-pointer :u8])
          row-offset (* token 630)
          ^:var total (ak/f32 0.0)]
      (dotimes [block 3]
        (ak/= total
              (+ total
                 (q6-k-dot (+ bytes row-offset (* block 210))
                           (+ input (* block 256))))))
      total)))

(az/defn- tensor-element :f32
  "Decode one logical flat tensor element for every supported model layout."
  [[tensor-index :usize]
   [element-index :usize]]
  (if (or (>= tensor-index tensor-catalog-count)
          (ak/== (az/field model-summary valid) false))
    0.0
    (let [tensor (az/index tensor-catalog tensor-index)
          bytes (ak/as (ak/ptrFromInt (az/field tensor data_address)) [:c-pointer :u8])]
      (cond
        (ak/== (az/field tensor ggml_type) 0)
        (let [values (ak/as (ak/ptrFromInt (az/field tensor data_address)) [:c-pointer :f32])]
          (az/index values element-index))

        (ak/== (az/field tensor ggml_type) 2)
        (q4-0-value (+ bytes (* (/ element-index 32) 18))
                    (mod element-index 32))

        (ak/== (az/field tensor ggml_type) 14)
        (q6-k-value (+ bytes (* (/ element-index 256) 210))
                    (mod element-index 256))

        :else 0.0))))

(az/defn- tensor-row-dot-kernel :f32
  "Allocation-free scalar reference matvec row for F32, Q4_0, or Q6_K."
  [[tensor-index :usize]
   [row :usize]
   [input [:pointer {:size :c :const? true} :f32]]]
  (if (or (>= tensor-index tensor-catalog-count)
          (ak/== (az/field model-summary valid) false))
    0.0
    (let [tensor (az/index tensor-catalog tensor-index)
          input-size (ak/as (ak/intCast (az/index (az/field tensor dimensions) 0))
                            :usize)
          output-size (if (> (az/field tensor dimension_count) 1)
                        (ak/as (ak/intCast
                                (az/index (az/field tensor dimensions) 1))
                               :usize)
                        1)
          bytes (ak/as (ak/ptrFromInt (az/field tensor data_address)) [:c-pointer :u8])
          ^:var total (ak/f32 0.0)]
      (if (>= row output-size)
        0.0
        (do
          (cond
            (ak/== (az/field tensor ggml_type) 0)
            (let [values (ak/as (ak/ptrFromInt (az/field tensor data_address)) [:c-pointer :f32])
                  row-start (* row input-size)]
              (dotimes [index input-size]
                (ak/= total (+ total
                               (* (az/index values (+ row-start index))
                                  (az/index input index))))))

            (ak/== (az/field tensor ggml_type) 2)
            (let [block-count (/ input-size 32)
                  row-start (* row block-count 18)]
              (dotimes [block-index block-count]
                (ak/= total (+ total
                               (q4-0-dot (+ bytes row-start (* block-index 18))
                                         (+ input (* block-index 32)))))))

            (ak/== (az/field tensor ggml_type) 14)
            (let [block-count (/ input-size 256)
                  row-start (* row block-count 210)]
              (dotimes [block-index block-count]
                (ak/= total (+ total
                               (q6-k-dot (+ bytes row-start (* block-index 210))
                                         (+ input (* block-index 256))))))))
          total)))))

(az/defn tensor-row-dot :f32
  "Inspectable wrapper around the private matrix-row hot loop."
  [[tensor-index :usize]
   [row :usize]
   [input [:pointer {:size :c :const? true} :f32]]]
  (tensor-row-dot-kernel tensor-index row input))

(az/defn tensor-matvec! :bool
  "Apply one supported two-dimensional tensor to a dense input vector."
  [[output [:c-pointer :f32]]
   [output-count :usize]
   [tensor-index :usize]
   [input [:pointer {:size :c :const? true} :f32]]]
  (if (or (>= tensor-index tensor-catalog-count)
          (< (az/field (az/index tensor-catalog tensor-index) dimension_count) 2)
          (ak/!= output-count
                 (ak/as (ak/intCast
                         (az/index
                          (az/field (az/index tensor-catalog tensor-index) dimensions)
                          1))
                        :usize)))
    false
    (do
      (dotimes [row output-count]
        (ak/= (az/index output row)
              (tensor-row-dot-kernel tensor-index row input)))
      true)))

(az/defn tensor-rms-norm! :bool
  "RMS-normalize through a named F32 model weight vector."
  [[output [:c-pointer :f32]]
   [input [:pointer {:size :c :const? true} :f32]]
   [weights-index :usize]
   [length :usize]
   [epsilon :f32]]
  (if (or (>= weights-index tensor-catalog-count)
          (ak/!= (az/field (az/index tensor-catalog weights-index) ggml_type) 0)
          (ak/!= (az/index
                  (az/field (az/index tensor-catalog weights-index) dimensions) 0)
                 length))
    false
    (let [^:var square-sum (ak/f32 0.0)]
      (dotimes [index length]
        (ak/= square-sum (+ square-sum
                            (* (az/index input index)
                               (az/index input index)))))
      (let [inverse-rms (/ 1.0
                           (std-math/sqrt
                            (+ (/ square-sum
                                  (ak/as (ak/floatFromInt length) :f32))
                               epsilon)))]
        (dotimes [index length]
          (ak/= (az/index output index)
                (* (az/index input index)
                   inverse-rms
                   (tensor-element weights-index index)))))
      true)))

(az/defn- silu :f32
  [[value :f32]]
  (/ value (+ 1.0 (std-math/exp (- 0.0 value)))))

(az/defn layer-zero-mlp-probe :f32
  "350M-only diagnostic: first dense FFN on a real token embedding.
  Unsupported profiles/tokens return NaN before accessing fixed-size buffers."
  [[token :usize]]
  (when (or (ak/!= model-profile-id model-profile-granite-h-350m)
            (>= token model-vocabulary-size))
    (ak/return (std-math/nan :f32)))
  (let [^:var hidden (ak/as (std-mem/zeroes (az/type [:array 768 :f32])) [:array 768 :f32])
        ^:var normalized (ak/as (std-mem/zeroes (az/type [:array 768 :f32])) [:array 768 :f32])
        ^:var gate (ak/as (std-mem/zeroes (az/type [:array 2048 :f32])) [:array 2048 :f32])
        ^:var up (ak/as (std-mem/zeroes (az/type [:array 2048 :f32])) [:array 2048 :f32])
        ^:var activated (ak/as (std-mem/zeroes (az/type [:array 2048 :f32])) [:array 2048 :f32])
        ^:var output (ak/as (std-mem/zeroes (az/type [:array 768 :f32])) [:array 768 :f32])
        norm-index (find-tensor "blk.0.ffn_norm.weight")
        gate-index (find-tensor "blk.0.ffn_gate.weight")
        up-index (find-tensor "blk.0.ffn_up.weight")
        down-index (find-tensor "blk.0.ffn_down.weight")]
    (dotimes [index 768]
      (ak/= (az/index hidden index)
            (* 12.0 (embedding-value-kernel token index))))
    (if (or (ak/== norm-index tensor-not-found)
            (ak/== gate-index tensor-not-found)
            (ak/== up-index tensor-not-found)
            (ak/== down-index tensor-not-found))
      0.0
      (do
        (ak/= :_ (tensor-rms-norm! (ak/& (az/index normalized 0))
                                   (ak/& (az/index hidden 0))
                                   norm-index 768 0.00001))
        (ak/= :_ (tensor-matvec! (ak/& (az/index gate 0)) 2048 gate-index
                                (ak/& (az/index normalized 0))))
        (ak/= :_ (tensor-matvec! (ak/& (az/index up 0)) 2048 up-index
                                (ak/& (az/index normalized 0))))
        (dotimes [index 2048]
          (ak/= (az/index activated index)
                (* (silu (az/index gate index)) (az/index up index))))
        (ak/= :_ (tensor-matvec! (ak/& (az/index output 0)) 768 down-index
                                (ak/& (az/index activated 0))))
        (az/index output 0)))))

(az/defn action-head-logit :f32
  "Evaluate one racing action row over Granite's normalized final state."
  [[action :usize]
   [hidden [:pointer {:size :c :const? true} :f32]]]
  (if (or (ak/! (az/field action-head-summary valid))
          (>= action action-head-output-count))
    0.0
    (let [row-start (* action action-head-input-count)
          ^:var total (ak/f32 (az/index action-head-biases action))]
      (dotimes [index action-head-input-count]
        (ak/= total
              (+ total
                 (* (az/index action-head-weights (+ row-start index))
                    (az/index hidden index)))))
      total)))

(az/defn team-head-logit :f32
  "Evaluate one independent team-strategy row over Granite's prompt state."
  [[action :usize]
   [hidden [:pointer {:size :c :const? true} :f32]]]
  (if (or (ak/! (az/field team-head-summary valid))
          (>= action team-head-output-count))
    0.0
    (let [row-start (* action action-head-input-count)
          ^:var total (ak/f32 (az/index team-head-biases action))]
      (dotimes [index action-head-input-count]
        (ak/= total
              (+ total
                 (* (az/index team-head-weights (+ row-start index))
                    (az/index hidden index)))))
      total)))

(az/defn forward-token! ForwardReport
  "Run one token through the active model's layers for an independent sequence.
  The vocabulary sentinel consumes one position-bound fused observation vector
  prepared by `forward-fused-observation!`; ordinary token calls are unchanged."
  [[racer :usize]
   [token :usize]]
  (let [^:var hidden (ak/as (std-mem/zeroes (az/type [:array model-max-hidden-size :f32])) [:array model-max-hidden-size :f32])
        ^:var normalized (ak/as (std-mem/zeroes (az/type [:array model-max-hidden-size :f32])) [:array model-max-hidden-size :f32])
        ^:var projected (ak/as (std-mem/zeroes (az/type [:array model-max-mamba-projection-size :f32])) [:array model-max-mamba-projection-size :f32])
        ^:var convolved (ak/as (std-mem/zeroes (az/type [:array model-max-mamba-conv-size :f32])) [:array model-max-mamba-conv-size :f32])
        ^:var scan-output (ak/as (std-mem/zeroes (az/type [:array model-max-mamba-inner-size :f32])) [:array model-max-mamba-inner-size :f32])
        ^:var gated-output (ak/as (std-mem/zeroes (az/type [:array model-max-mamba-inner-size :f32])) [:array model-max-mamba-inner-size :f32])
        ^:var branch-output (ak/as (std-mem/zeroes (az/type [:array model-max-hidden-size :f32])) [:array model-max-hidden-size :f32])
        ^:var ffn-gate (ak/as (std-mem/zeroes (az/type [:array model-max-ffn-size :f32])) [:array model-max-ffn-size :f32])
        ^:var ffn-up (ak/as (std-mem/zeroes (az/type [:array model-max-ffn-size :f32])) [:array model-max-ffn-size :f32])
        ^:var ffn-activated (ak/as (std-mem/zeroes (az/type [:array model-max-ffn-size :f32])) [:array model-max-ffn-size :f32])
        ^:var ffn-output (ak/as (std-mem/zeroes (az/type [:array model-max-hidden-size :f32])) [:array model-max-hidden-size :f32])
        ^:var query (ak/as (std-mem/zeroes (az/type [:array model-max-hidden-size :f32])) [:array model-max-hidden-size :f32])
        ^:var key (ak/as (std-mem/zeroes (az/type [:array model-max-attention-kv-size :f32])) [:array model-max-attention-kv-size :f32])
        ^:var value (ak/as (std-mem/zeroes (az/type [:array model-max-attention-kv-size :f32])) [:array model-max-attention-kv-size :f32])
        ^:var scores (ak/as (std-mem/zeroes (az/type [:array sequence-capacity :f32])) [:array sequence-capacity :f32])
        ^:var attention-output (ak/as (std-mem/zeroes (az/type [:array model-max-hidden-size :f32])) [:array model-max-hidden-size :f32])
        candidate-tokens
        (az/array-init [32 33 34 35 36 37 38 39] [:array 8 :u32])
        ^:var candidate-logits (ak/as (std-mem/zeroes (az/type [:array 8 :f32])) [:array 8 :f32])
        position (ak/usize (if (< racer sequence-racer-count)
                   (ak/as (az/index sequence-positions racer) :usize)
                   sequence-capacity))
        ^:var valid (ak/bool (and (az/field model-summary valid)
                   (ak/!= sequence-memory ak/null)
                   (< racer sequence-racer-count)
                   (< position sequence-capacity)
                   (<= token model-vocabulary-size)))
        ^:var checksum (ak/f32 0.0)
        team-actor (>= racer protocol/racer-count)
        candidate-count (if team-actor team-head-output-count
                            action-head-output-count)
        ^:var best-token (ak/u32 32)
        ^:var best-logit (ak/f32 -3.4e38)]
    (when valid
      (dotimes [index model-hidden-size]
        (ak/= (az/index hidden index)
              (if (ak/== token model-vocabulary-size)
                (az/index fused-observation-inputs
                          (+ (* racer model-hidden-size) index))
                (* 12.0 (embedding-value-kernel token index)))))
      (dotimes [layer model-layer-count]
        (when valid
          (if (attention-layer? layer)
            (let [key-cache (sequence-key-cache racer layer)
                  value-cache (sequence-value-cache racer layer)]
              (if (or (ak/== key-cache ak/null) (ak/== value-cache ak/null))
                (ak/= valid false)
                (ak/= valid
                      (attention-ffn-layer!
                       layer position
                       (ak/& (az/index hidden 0))
                       (az/unwrap key-cache) (az/unwrap value-cache)
                       (ak/& (az/index normalized 0))
                       (ak/& (az/index query 0))
                       (ak/& (az/index key 0))
                       (ak/& (az/index value 0))
                       (ak/& (az/index scores 0))
                       (ak/& (az/index attention-output 0))
                       (ak/& (az/index branch-output 0))
                       (ak/& (az/index ffn-gate 0))
                       (ak/& (az/index ffn-up 0))
                       (ak/& (az/index ffn-activated 0))
                       (ak/& (az/index ffn-output 0))))))
            (let [recurrent-state (sequence-mamba-state racer layer)
                  conv-state (sequence-conv-state racer layer)]
              (if (or (ak/== recurrent-state ak/null) (ak/== conv-state ak/null))
                (ak/= valid false)
                (ak/= valid
                      (mamba-ffn-layer!
                       layer
                       (ak/& (az/index hidden 0))
                       (az/unwrap recurrent-state) (az/unwrap conv-state)
                       (ak/& (az/index normalized 0))
                       (ak/& (az/index projected 0))
                       (ak/& (az/index convolved 0))
                       (ak/& (az/index scan-output 0))
                       (ak/& (az/index gated-output 0))
                       (ak/& (az/index branch-output 0))
                       (ak/& (az/index ffn-gate 0))
                       (ak/& (az/index ffn-up 0))
                       (ak/& (az/index ffn-activated 0))
                       (ak/& (az/index ffn-output 0)))))))))
      (when valid
        (ak/= valid
              (tensor-rms-norm! (ak/& (az/index normalized 0))
                                (ak/& (az/index hidden 0))
                                0 model-hidden-size model-rms-epsilon)))
      (when valid
        ;; Keep the final eight real sequential token states. Short prompts
        ;; fill from slot zero; longer readable prompts slide one state at a
        ;; time so offline training and live inference see the same suffix.
        (when (>= position action-head-token-count)
          (dotimes [slot (- action-head-token-count 1)]
            (dotimes [index model-hidden-size]
              (ak/= (az/index action-head-inputs
                              (+ (* racer action-head-input-count)
                                 (* slot model-hidden-size)
                                 index))
                    (az/index action-head-inputs
                              (+ (* racer action-head-input-count)
                                 (* (+ slot 1) model-hidden-size)
                                 index))))))
        (dotimes [index model-hidden-size]
          (let [hidden-value (az/index normalized index)
                feature-slot (ak/min position (- action-head-token-count 1))]
            (ak/= checksum (+ checksum hidden-value))
            (ak/= (az/index action-head-inputs
                            (+ (* racer action-head-input-count)
                               (* feature-slot model-hidden-size)
                               index))
                  hidden-value)))
        (dotimes [candidate candidate-count]
          (let [candidate-token (az/index candidate-tokens candidate)
                logit
                (cond
                  (and team-actor (az/field team-head-summary valid))
                  (team-head-logit
                   candidate
                   (ak/& (az/index action-head-inputs
                                   (* racer action-head-input-count))))

                  (and (ak/! team-actor)
                       (az/field action-head-summary valid))
                  (action-head-logit
                   candidate
                   (ak/& (az/index action-head-inputs
                                   (* racer action-head-input-count))))

                  :else
                  (/ (embedding-row-dot
                      (ak/as candidate-token :usize)
                      (ak/& (az/index normalized 0)))
                     3.0))]
            (ak/= (az/index candidate-logits candidate) logit)
            (when (> logit best-logit)
              (ak/= best-logit logit)
              (ak/= best-token candidate-token))))
        (ak/= (az/index sequence-positions racer) (ak/intCast (+ position 1)))))
    (ForwardReport
     {:valid valid
      :racer (ak/intCast racer)
      :position (ak/intCast position)
      :input_token (ak/intCast token)
      :best_token best-token
      :best_logit best-logit
      :hidden_first (az/index normalized 0)
      :hidden_checksum checksum
      :candidate_count (ak/intCast candidate-count)
      :candidate_tokens candidate-tokens
      :candidate_logits candidate-logits})))

(az/defn forward-fused-observation! ForwardReport
  "Encode every positional observation field into two full Granite steps.
  Category embeddings are bound to their field position by a deterministic
  sign vector, summed in two ordered groups at stable RMS scale, and processed
  by all 32 model layers. No field is dropped and no policy feature bypasses
  Granite."
  [[racer :usize]
   [bytes [:pointer {:size :c :const? true} :u8]]
   [length :usize]
   [reset :bool]]
  (let [tokenized (tokenize-compact-ascii bytes length)
        ^:var report (ak/as (empty-forward-report) ForwardReport)
        ^:var valid (ak/bool (and (az/field tokenized valid)
                   (ak/! (az/field tokenized truncated))
                   (ak/== (az/field tokenized token_count)
                          action-head-token-count)
                   (< racer sequence-racer-count)))]
    (when (and valid reset)
      (ak/= valid (reset-sequence! racer)))
    (when valid
      ;; Two ordered four-field groups preserve the complete observation while
      ;; reducing eight sequential model passes to two. Position binding keeps
      ;; equal category bytes in different fields distinguishable.
      (dotimes [group 2]
        (when valid
          (dotimes [dimension model-hidden-size]
            (let [^:var total (ak/f32 0.0)]
              (dotimes [local-position 4]
                (let [position (+ (* group 4) local-position)
                      token
                      (ak/as (az/index (az/field tokenized tokens) position)
                             :usize)
                      position-value
                      (embedding-value-kernel (+ 32 position) dimension)
                      position-sign (ak/f32 (if (>= position-value 0.0) 1.0 -1.0))]
                  (ak/= total
                        (+ total
                           (* position-sign
                              (embedding-value-kernel token dimension))))))
              (ak/= (az/index fused-observation-inputs
                              (+ (* racer model-hidden-size) dimension))
                    (* total 6.0))))
          (ak/= report (forward-token! racer model-vocabulary-size))
          (ak/= valid (az/field report valid)))))
    (when (ak/! valid)
      (ak/= (az/field report valid) false))
    report))

(az/defn empty-forward-report ForwardReport
  []
  (ForwardReport
   {:valid false :racer 0 :position 0 :input_token 0
    :best_token 0 :best_logit 0.0 :hidden_first 0.0 :hidden_checksum 0.0
    :candidate_count 0
    :candidate_tokens (std-mem/zeroes (az/type [:array 8 :u32]))
    :candidate_logits (std-mem/zeroes (az/type [:array 8 :f32]))}))

(az/defn forward-compact-prompt! ForwardReport
  "Tokenize and run one bounded compact ASCII observation entirely in native
  code. Resetting is explicit so callers can retain or replace agent memory."
  [[racer :usize]
   [bytes [:pointer {:size :c :const? true} :u8]]
   [length :usize]
   [reset :bool]]
  (let [tokenized (tokenize-compact-ascii bytes length)
        ^:var report (ak/as (empty-forward-report) ForwardReport)
        ^:var valid (ak/bool (and (az/field tokenized valid)
                   (ak/! (az/field tokenized truncated))
                   (> (az/field tokenized token_count) 0)))]
    (when (and valid reset)
      (ak/= valid (reset-sequence! racer)))
    (when valid
      (dotimes [index (az/field tokenized token_count)]
        (when valid
          (ak/= report
                (forward-token!
                 racer
                 (ak/as (az/index (az/field tokenized tokens) index)
                        :usize)))
          (ak/= valid (az/field report valid)))))
    (when (ak/! valid)
      (ak/= (az/field report valid) false))
    report))

(az/defstruct LanguageGeneration
  "Experimental full-vocabulary generation, independent of racing action heads.
  Stop:1 EOS,2 token limit,3 context limit,4 invalid input/model/token bytes.
  Opt-in language workers deliver this verbatim; the simulation validates
  plans separately. Generation success is not a claim of decision quality."
  {:layout :extern}
  [[:valid :bool] [:stop :u8] [:input_tokens :u16] [:output_tokens :u16]
   [:byte_count :u16] [:tokens [:array 64 :u32]] [:bytes [:array 2048 :u8]]])

(az/defn gpt2-codepoint-byte :i16
  "Invert GPT-2's byte-to-Unicode alphabet. This decodes tokenizer storage,
  not a game command encoding. Concatenated output bytes form ordinary UTF-8." [[code :u32]]
  (cond
    (or (and (>= code 33) (<= code 126))
        (and (>= code 161) (<= code 172))
        (and (>= code 174) (<= code 255))) (ak/intCast code)
    (and (>= code 256) (<= code 288)) (ak/intCast (- code 256))
    (and (>= code 289) (<= code 322)) (ak/intCast (- code 162))
    (ak/== code 323) (ak/as 173 :i16)
    :else (ak/as -1 :i16)))

(az/defn decode-language-token! :u16
  "Decode one real GGUF vocabulary entry into bytes. Return65535 on invalid
  Unicode/alphabet or insufficient space; never silently truncate a token." [[token :u32] [output [:c-pointer :u8]] [capacity :usize]]
  (when (or (ak/! tokenizer-valid) (>= token tokenizer-token-count))
    (ak/return (ak/as 65535 :u16)))
  (let [start (ak/as (az/index tokenizer-token-starts token) :usize)
        length (ak/as (az/index tokenizer-token-lengths token) :usize)
        ^:var offset (ak/usize 0)
        ^:var written (ak/u16 0)]
    (ak/while (< offset length)
      (let [first-byte (az/index (az/unwrap model-bytes) (+ start offset))
            ^:var code (ak/u32 first-byte)]
        (ak/= offset (+ offset 1))
        (when (>= first-byte 128)
          (when (or (< first-byte 194) (> first-byte 197) (>= offset length))
            (ak/return (ak/as 65535 :u16)))
          (let [second-byte (az/index (az/unwrap model-bytes) (+ start offset))]
            (when (or (< second-byte 128) (> second-byte 191))
              (ak/return (ak/as 65535 :u16)))
            (ak/= code (+ (* (ak/as (- first-byte 192) :u32) 64)
                         (ak/as (- second-byte 128) :u32)))
            (ak/= offset (+ offset 1))))
        (let [byte (gpt2-codepoint-byte code)]
          (when (or (< byte 0) (>= written capacity))
            (ak/return (ak/as 65535 :u16)))
          (ak/= (az/index output written) (ak/intCast byte))
          (ak/= written (+ written 1)))))
    written))

(az/defn next-language-token :u32
  "Greedy argmax over the pretrained model's ENTIRE vocabulary. No A-H or
  pit-head scores influence this result. Consume the last normalized native
  backbone state already retained by forward-token!. Invalid returns0xffffffff." [[racer :usize]]
  (when (or (>= racer sequence-racer-count) (ak/! tokenizer-valid)
            (ak/== (az/index sequence-positions racer) 0))
    (ak/return (ak/as 0xffffffff :u32)))
  (let [separate (find-tensor "output.weight")
        output (if (< separate tensor-catalog-count) separate
                 (find-tensor "token_embd.weight"))
        slot (ak/min (- (ak/as (az/index sequence-positions racer) :usize) 1)
                     (- action-head-token-count 1))
        hidden (ak/& (az/index action-head-inputs
                      (+ (* racer action-head-input-count) (* slot model-hidden-size))))
        ^:var best (ak/u32 0xffffffff)
        ^:var best-score (ak/f32 -3.4e38)]
    (when (>= output tensor-catalog-count)
      (ak/return (ak/as 0xffffffff :u32)))
    (let [tensor (az/index tensor-catalog output)]
      (when (or (ak/!= (az/field tensor dimension_count) 2)
                (ak/!= (az/index (az/field tensor dimensions) 0) model-hidden-size)
                (ak/!= (az/index (az/field tensor dimensions) 1) tokenizer-token-count))
        (ak/return (ak/as 0xffffffff :u32))))
    (dotimes [token tokenizer-token-count]
      (let [score (tensor-row-dot-kernel output token hidden)]
        (when (ak/! (std-math/isFinite score))
          (ak/return (ak/as 0xffffffff :u32)))
        (when (> score best-score)
          (ak/= best-score score)
          (ak/= best (ak/intCast token)))))
    best))

(az/defn vocabulary-token-id :u32
  "Find an exact tokenizer vocabulary spelling from the loaded GGUF. This
  resolves model control tokens; ordinary user text is never parsed as roles."
  [[spelling [:pointer {:size :c :const? true} :u8]] [length :usize]]
  (when tokenizer-valid
    (dotimes [token tokenizer-token-count]
      (when (ak/== (az/index tokenizer-token-lengths token) length)
        (let [start (az/index tokenizer-token-starts token)
              ^:var same true]
          (dotimes [i length]
            (when (ak/!= (az/index (az/unwrap model-bytes) (+ start i))
                        (az/index spelling i))
              (ak/= same false)))
          (when same (ak/return (ak/as (ak/intCast token) :u32)))))))
  (ak/as 0xffffffff :u32))

(az/defn- language-letter? :bool [[byte :u8]]
  (or (and (>= byte 65) (<= byte 90)) (and (>= byte 97) (<= byte 122))))

(az/defn- language-digit? :bool [[byte :u8]]
  (and (>= byte 48) (<= byte 57)))

(az/defn- language-space? :bool [[byte :u8]]
  (or (ak/== byte 32) (and (>= byte 9) (<= byte 13))))

(az/defn- language-lower :u8 [[byte :u8]]
  (if (and (>= byte 65) (<= byte 90)) (+ byte 32) byte))

(az/defn language-piece-length :usize
  "ASCII subset of Granite's published pre-tokenizer regex, in its alternative
  order. BPE must not merge across these word/number/punctuation boundaries." [[bytes [:pointer {:size :c :const? true} :u8]] [length :usize]]
  (when (ak/== length 0) (ak/return (ak/as 0 :usize)))
  (let [first-byte (az/index bytes 0)]
    (when (and (ak/== first-byte 39) (>= length 2))
      (let [second-byte (language-lower (az/index bytes 1))]
        (when (or (ak/== second-byte 115) (ak/== second-byte 116)
                  (ak/== second-byte 109) (ak/== second-byte 100))
          (ak/return (ak/as 2 :usize)))
        (when (>= length 3)
          (let [third-byte (language-lower (az/index bytes 2))]
            (when (or (and (ak/== second-byte 114) (ak/== third-byte 101))
                      (and (ak/== second-byte 118) (ak/== third-byte 101))
                      (and (ak/== second-byte 108) (ak/== third-byte 108)))
              (ak/return (ak/as 3 :usize)))))))
    (let [prefix (if (language-letter? first-byte) (ak/as 0 :usize) (ak/as 1 :usize))]
      (when (and (< prefix length) (language-letter? (az/index bytes prefix))
                 (ak/!= first-byte 10) (ak/!= first-byte 13)
                 (ak/! (language-digit? first-byte)))
        (let [^:var end (+ prefix 1)]
          (ak/while (and (< end length) (language-letter? (az/index bytes end)))
            (ak/= end (+ end 1)))
          (ak/return end))))
    (when (language-digit? first-byte)
      (let [^:var end (ak/usize 1)]
        (ak/while (and (< end (ak/min length 3)) (language-digit? (az/index bytes end)))
          (ak/= end (+ end 1)))
        (ak/return end)))
    (let [prefix (if (ak/== first-byte 32) (ak/as 1 :usize) (ak/as 0 :usize))
          ^:var end prefix]
      (ak/while (and (< end length)
                     (ak/! (language-space? (az/index bytes end)))
                     (ak/! (language-letter? (az/index bytes end)))
                     (ak/! (language-digit? (az/index bytes end))))
        (ak/= end (+ end 1)))
      (when (> end prefix)
        (ak/while (and (< end length)
                       (or (ak/== (az/index bytes end) 10) (ak/== (az/index bytes end) 13)))
          (ak/= end (+ end 1)))
        (ak/return end)))
    (when (language-space? first-byte)
      (let [^:var end (ak/usize 0)
            ^:var last-newline (ak/usize 0)]
        (ak/while (and (< end length) (language-space? (az/index bytes end)))
          (ak/= end (+ end 1))
          (when (or (ak/== (az/index bytes (- end 1)) 10)
                    (ak/== (az/index bytes (- end 1)) 13))
            (ak/= last-newline end)))
        (ak/return (cond (> last-newline 0) last-newline
                         (ak/== end length) end
                         (> end 1) (- end 1)
                         :else end))))
    (ak/as 1 :usize)))

(az/defn tokenize-language-ascii TokenizationReport
  "Bounded ordinary text: apply Granite's word-splitting rule before GGUF BPE.
  The old compact-head tokenizer is left unchanged for existing head fixtures."
  [[bytes [:pointer {:size :c :const? true} :u8]] [length :usize]]
  (let [^:var result (std-mem/zeroes (az/type TokenizationReport))
        ^:var offset (ak/usize 0)
        bound (ak/min length tokenizer-capacity)]
    (ak/= (az/field result byte_count) (ak/intCast bound))
    (ak/= (az/field result truncated) (> length tokenizer-capacity))
    (ak/= (az/field result valid) tokenizer-valid)
    (ak/while (and (< offset bound) (az/field result valid))
      (let [size (language-piece-length (+ bytes offset) (- bound offset))
            piece (tokenize-compact-ascii (+ bytes offset) size)]
        (when (ak/! (az/field piece valid))
          (ak/= (az/field result valid) false)
          (ak/= (az/field result unsupported_index)
                (ak/intCast (+ offset (az/field piece unsupported_index)))))
        (dotimes [i (az/field piece token_count)]
          (ak/= (az/index (az/field result tokens) (az/field result token_count))
                (az/index (az/field piece tokens) i))
          (ak/= (az/field result token_count) (+ (az/field result token_count) 1)))
        (ak/= offset (+ offset size))))
    result))

(az/defn tokenize-language-chat-with-system TokenizationReport
  "Granite's real system/user/assistant frame with caller-provided plain text.
  Content is tokenized separately from role controls, so a message cannot
  impersonate another role by spelling a special token. Bounded ASCII proof."
  [[system [:pointer {:size :c :const? true} :u8]] [system-length :usize]
   [prompt [:pointer {:size :c :const? true} :u8]] [length :usize]]
  (let [content (tokenize-language-ascii prompt length)
        system-role (tokenize-language-ascii "system" 6)
        system-content (tokenize-language-ascii system system-length)
        user-role (tokenize-language-ascii "user" 4)
        assistant-role (tokenize-language-ascii "assistant" 9)
        start-role (vocabulary-token-id "<|start_of_role|>" 17)
        end-role (vocabulary-token-id "<|end_of_role|>" 15)
        eos (metadata-u32 (find-metadata "tokenizer.ggml.eos_token_id") 0xffffffff)
        total (+ 10 (az/field content token_count)
                    (az/field system-role token_count) (az/field system-content token_count)
                   (az/field user-role token_count) (az/field assistant-role token_count))
        ^:var result (std-mem/zeroes (az/type TokenizationReport))
        ^:var cursor (ak/usize 0)]
    (ak/= (az/field result byte_count) (az/field content byte_count))
    (ak/= (az/field result truncated)
          (or (az/field content truncated) (az/field system-content truncated)
              (> total tokenizer-capacity)))
    (when (or (ak/! tokenizer-valid) (ak/! (az/field content valid))
              (ak/! (az/field system-content valid))
              (az/field result truncated) (>= start-role tokenizer-token-count)
              (>= end-role tokenizer-token-count) (>= eos tokenizer-token-count))
      (ak/return result))
    (ak/= (az/index (az/field result tokens) cursor) start-role)
    (ak/= cursor (+ cursor 1))
    (dotimes [i (az/field system-role token_count)]
      (ak/= (az/index (az/field result tokens) cursor) (az/index (az/field system-role tokens) i))
      (ak/= cursor (+ cursor 1)))
    (ak/= (az/index (az/field result tokens) cursor) end-role)
    (ak/= cursor (+ cursor 1))
    (dotimes [i (az/field system-content token_count)]
      (ak/= (az/index (az/field result tokens) cursor) (az/index (az/field system-content tokens) i))
      (ak/= cursor (+ cursor 1)))
    (ak/= (az/index (az/field result tokens) cursor) eos)
    (ak/= cursor (+ cursor 1))
    (ak/= (az/index (az/field result tokens) cursor) (ascii-byte-token 10))
    (ak/= cursor (+ cursor 1))
    (ak/= (az/index (az/field result tokens) cursor) start-role)
    (ak/= cursor (+ cursor 1))
    (dotimes [i (az/field user-role token_count)]
      (ak/= (az/index (az/field result tokens) cursor) (az/index (az/field user-role tokens) i))
      (ak/= cursor (+ cursor 1)))
    (ak/= (az/index (az/field result tokens) cursor) end-role)
    (ak/= cursor (+ cursor 1))
    (dotimes [i (az/field content token_count)]
      (ak/= (az/index (az/field result tokens) cursor) (az/index (az/field content tokens) i))
      (ak/= cursor (+ cursor 1)))
    (ak/= (az/index (az/field result tokens) cursor) eos)
    (ak/= cursor (+ cursor 1))
    (ak/= (az/index (az/field result tokens) cursor) (ascii-byte-token 10))
    (ak/= cursor (+ cursor 1))
    (ak/= (az/index (az/field result tokens) cursor) start-role)
    (ak/= cursor (+ cursor 1))
    (dotimes [i (az/field assistant-role token_count)]
      (ak/= (az/index (az/field result tokens) cursor) (az/index (az/field assistant-role tokens) i))
      (ak/= cursor (+ cursor 1)))
    (ak/= (az/index (az/field result tokens) cursor) end-role)
    (ak/= (az/field result token_count) (ak/intCast (+ cursor 1)))
    (ak/= (az/field result valid) true)
    result))

(az/defn tokenize-language-chat TokenizationReport
  "Apply Granite's official default system message to a plain user message."
  [[prompt [:pointer {:size :c :const? true} :u8]] [length :usize]]
  (tokenize-language-chat-with-system
   "You are a helpful assistant. Please ensure responses are professional, accurate, and safe." 90
   prompt length))

(az/defn generate-language-with-system! LanguageGeneration
  "Bounded native autoregressive user-chat proof with real vocabulary decoding.
  Does not train/load a racing head or install game actions. Callers must
  validate replies and deadlines before changing a driving intention."
  [[racer :usize]
   [system [:pointer {:size :c :const? true} :u8]] [system-length :usize]
   [prompt [:pointer {:size :c :const? true} :u8]]
   [length :usize] [max-new-tokens :usize]]
  (let [^:var result (std-mem/zeroes (az/type LanguageGeneration))
        tokenized (tokenize-language-chat-with-system system system-length prompt length)
        eos (metadata-u32 (find-metadata "tokenizer.ggml.eos_token_id") 0xffffffff)]
    (ak/= (az/field result stop) 4)
    (when (or (ak/! tokenizer-valid) (ak/! (az/field tokenized valid))
              (az/field tokenized truncated) (ak/== (az/field tokenized token_count) 0)
              (>= eos tokenizer-token-count) (ak/! (reset-sequence! racer)))
      (ak/return result))
    (dotimes [i (az/field tokenized token_count)]
      (when (ak/! (az/field (forward-token! racer (az/index (az/field tokenized tokens) i)) valid))
        (ak/return result))
      (ak/= (az/field result input_tokens) (+ (az/field result input_tokens) 1)))
    (ak/= (az/field result valid) true)
    (ak/= (az/field result stop) 2)
    (dotimes [_ (ak/min max-new-tokens 64)]
      (let [token (next-language-token racer)]
        (when (ak/== token eos)
          (ak/= (az/field result stop) 1)
          (ak/return result))
        (when (>= (az/field result byte_count) 2048)
          (ak/= (az/field result valid) false)
          (ak/= (az/field result stop) 4)
          (ak/return result))
        (let [size (decode-language-token! token
                     (ak/& (az/index (az/field result bytes) (az/field result byte_count)))
                     (- 2048 (az/field result byte_count)))]
          (when (ak/== size 65535)
            (ak/= (az/field result valid) false)
            (ak/= (az/field result stop) 4)
            (ak/return result))
          (ak/= (az/field result byte_count) (+ (az/field result byte_count) size)))
        (ak/= (az/index (az/field result tokens) (az/field result output_tokens)) token)
        (ak/= (az/field result output_tokens) (+ (az/field result output_tokens) 1))
        (when (>= (az/field result output_tokens) (ak/min max-new-tokens 64))
          (ak/return result))
        (when (>= (az/index sequence-positions racer) sequence-capacity)
          (ak/= (az/field result stop) 3)
          (ak/return result))
        (when (ak/! (az/field (forward-token! racer token) valid))
          (ak/= (az/field result valid) false)
          (ak/= (az/field result stop) 4)
          (ak/return result))))
    result))

(az/defn generate-language! LanguageGeneration
  "Generate a plain user-chat reply using Granite's official default system."
  [[racer :usize] [prompt [:pointer {:size :c :const? true} :u8]]
   [length :usize] [max-new-tokens :usize]]
  (generate-language-with-system!
   racer
   "You are a helpful assistant. Please ensure responses are professional, accurate, and safe." 90
   prompt length max-new-tokens))

(az/defn kernel-self-test KernelReport
  "Execute deterministic native fixtures without allocating or invoking a server."
  []
  (let [block (az/array-init [0 60 153 153 153 153 153 153 153 153
                              153 153 153 153 153 153 153 153] [:array 18 :u8])
        input (az/array-init [1.0 1.0 1.0 1.0 1.0 1.0 1.0 1.0
                              1.0 1.0 1.0 1.0 1.0 1.0 1.0 1.0
                              1.0 1.0 1.0 1.0 1.0 1.0 1.0 1.0
                              1.0 1.0 1.0 1.0 1.0 1.0 1.0 1.0] [:array 32 :f32])
        norm-input (az/array-init [1.0 2.0 3.0 4.0] [:array 4 :f32])
        weights (az/array-init [1.0 1.0 1.0 1.0] [:array 4 :f32])
        ^:var norm-output (ak/as (az/array-init [0.0 0.0 0.0 0.0] [:array 4 :f32]) [:array 4 :f32])
        ^:var probabilities (ak/as (az/array-init [1.0 2.0 3.0 4.0] [:array 4 :f32]) [:array 4 :f32])]
    (rms-norm! (ak/& (az/index norm-output 0)) (ak/& (az/index norm-input 0))
               (ak/& (az/index weights 0)) 4 0.00001)
    (softmax! (ak/& (az/index probabilities 0)) 4)
    (KernelReport
     {:q4_dot (q4-0-dot (ak/& (az/index block 0)) (ak/& (az/index input 0)))
      :rms_first (az/index norm-output 0)
      :rms_last (az/index norm-output 3)
      :softmax_sum (+ (az/index probabilities 0) (az/index probabilities 1)
                      (az/index probabilities 2) (az/index probabilities 3))})))
