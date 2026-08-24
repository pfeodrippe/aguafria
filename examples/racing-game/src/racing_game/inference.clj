(ns racing-game.inference
  "Narrow native Granite runtime: model ownership, strict GGUF parsing, and kernels."
  (:require [aguafria.keyword :as ak]
            [aguafria.std]
            [aguafria.std.mem :as std-mem]
            [aguafria.std.math :as std-math]
            [aguafria.zig :as az]
            [aguafria-examples-native.bindings]
            [aguafria-examples-native.bindings.runtime :as runtime]
            [racing-game.protocol :as protocol]))

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

(az/defconst tensor-not-found :usize 4096)

(az/defconst metadata-not-found :usize 128)

(az/defconst tokenizer-capacity :usize 128)

(az/defconst model-hidden-size :usize 768)

(az/defconst model-ffn-size :usize 2048)

(az/defconst model-layer-count :usize 32)

(az/defconst model-mamba-layer-count :usize 28)

(az/defconst model-mamba-inner-size :usize 1536)

(az/defconst model-mamba-conv-size :usize 1792)

(az/defconst model-mamba-head-count :usize 48)

(az/defconst model-mamba-head-size :usize 32)

(az/defconst model-mamba-state-size :usize 128)

(az/defconst model-mamba-recurrent-size :usize 196608)

(az/defconst model-mamba-conv-state-size :usize 5376)

(az/defconst model-attention-layer-count :usize 4)

(az/defconst model-attention-head-count :usize 12)

(az/defconst model-attention-kv-head-count :usize 4)

(az/defconst model-attention-head-size :usize 64)

(az/defconst model-attention-kv-size :usize 256)

(az/defconst model-attention-scale :f32 0.015625)

(az/defconst sequence-capacity :usize 160)

(az/defconst sequence-racer-count :usize 8)

(az/defconst sequence-mamba-floats :usize 44040192)

(az/defconst sequence-conv-floats :usize 1204224)

(az/defconst sequence-kv-floats :usize 1310720)

(az/defconst sequence-total-floats :usize 47865856)

(az/defconst sequence-total-bytes :usize 191463424)

(az/defconst model-residual-multiplier :f32 0.246)

(az/defconst model-rms-epsilon :f32 0.00001)

(az/defconst action-head-magic :u32 0x48415241)

(az/defconst action-head-version :u32 1)

(az/defconst action-head-output-count :usize 8)

(az/defconst action-head-token-count :usize 8)

(az/defconst action-head-input-count :usize 6144)

(az/defconst action-head-weight-count :usize 49152)

(az/defconst action-head-header-bytes :usize 32)

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
   [:tokens [:array 128 :u32]]])

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
   [:positions [:array 8 :u16]]])

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

(az/defvar model-bytes [:optional [:c-pointer :u8]] null)

(az/defvar model-byte-count :usize 0)

(az/defvar tensor-catalog-count :usize 0)

(az/defvar tensor-catalog [:array 4096 TensorInfo]
  (std-mem/zeroes (az/type [:array 4096 TensorInfo])))

(az/defvar metadata-catalog-count :usize 0)

(az/defvar metadata-catalog [:array 128 MetadataInfo]
  (std-mem/zeroes (az/type [:array 128 MetadataInfo])))

(az/defvar model-summary GgufSummary
  (GgufSummary {:loaded false :valid false :error_code parser-truncated
                :version 0 :tensor_count 0 :metadata_count 0
                :f32_tensors 0 :q4_0_tensors 0 :q6_k_tensors 0
                :descriptor_end 0 :data_offset 0 :file_size 0}))

(az/defvar sequence-memory [:optional [:c-pointer :f32]] null)

(az/defvar sequence-memory-floats :usize 0)

(az/defvar sequence-positions [:array 8 :u16]
  (std-mem/zeroes (az/type [:array 8 :u16])))

(az/defvar action-head-inputs [:array 49152 :f32]
  (std-mem/zeroes (az/type [:array 49152 :f32])))

(az/defvar action-head-weights [:array 49152 :f32]
  (std-mem/zeroes (az/type [:array 49152 :f32])))

(az/defvar action-head-biases [:array 8 :f32]
  (std-mem/zeroes (az/type [:array 8 :f32])))

(az/defvar action-head-summary ActionHeadSummary
  (ActionHeadSummary
   {:loaded false :valid false :error_code 1 :version 0
    :input_count 0 :output_count 0 :observation_schema 0 :action_schema 0
    :weight_count 0 :file_size 0}))

(az/defn can-read
  {:export false :implicit-return true}
  :-
  :bool
  [[reader [:* Reader]]
   [count :usize]]
  (and (ak/== (az/field (az/deref reader) error_code) parser-ok)
       (<= count (- (az/field (az/deref reader) length)
                    (az/field (az/deref reader) cursor)))))

(az/defn read-u8!
  {:export false}
  :-
  :u8
  [[reader [:* Reader]]]
  (if (can-read reader 1)
    (let [value (az/index (az/field (az/deref reader) bytes)
                          (az/field (az/deref reader) cursor))]
      (set! (az/field (az/deref reader) cursor)
            (+ (az/field (az/deref reader) cursor) 1))
      value)
    (do
      (when (ak/== (az/field (az/deref reader) error_code) parser-ok)
        (set! (az/field (az/deref reader) error_code) parser-truncated))
      0)))

(az/defn read-u32!
  {:export false :implicit-return true}
  :-
  :u32
  [[reader [:* Reader]]]
  (let [b0 (ak/as :u32 (read-u8! reader))
        b1 (ak/as :u32 (read-u8! reader))
        b2 (ak/as :u32 (read-u8! reader))
        b3 (ak/as :u32 (read-u8! reader))]
    (+ b0 (* b1 256) (* b2 65536) (* b3 16777216))))

(az/defn read-u64!
  {:export false :implicit-return true}
  :-
  :u64
  [[reader [:* Reader]]]
  (let [low (ak/as :u64 (read-u32! reader))
        high (ak/as :u64 (read-u32! reader))]
    (+ low (* high 4294967296))))

(az/defn skip-bytes!
  {:export false}
  :-
  :void
  [[reader [:* Reader]]
   [count :u64]]
  (if (and (<= count (ak/as :u64 (az/field (az/deref reader) length)))
           (can-read reader (ak/intCast count)))
    (set! (az/field (az/deref reader) cursor)
          (+ (az/field (az/deref reader) cursor) (ak/as :usize (ak/intCast count))))
    (set! (az/field (az/deref reader) error_code) parser-truncated)))

(az/defn read-string-view!
  {:export false}
  :-
  StringView
  [[reader [:* Reader]]]
  (let [length (read-u64! reader)
        start (az/field (az/deref reader) cursor)]
    (skip-bytes! reader length)
    (StringView {:start start :length (ak/intCast length)})))

(az/defn skip-string!
  {:export false}
  :-
  :void
  [[reader [:* Reader]]]
  (set! _ (read-string-view! reader)))

(az/defn scalar-byte-size
  {:export false :implicit-return true}
  :-
  :u8
  [[value-type :u32]]
  (cond
    (or (ak/== value-type 0) (ak/== value-type 1) (ak/== value-type 7)) 1
    (or (ak/== value-type 2) (ak/== value-type 3)) 2
    (or (ak/== value-type 4) (ak/== value-type 5) (ak/== value-type 6)) 4
    (or (ak/== value-type 10) (ak/== value-type 11) (ak/== value-type 12)) 8
    :else 0))

(az/defn skip-value!
  {:export false}
  :-
  :void
  [[reader [:* Reader]]
   [value-type :u32]
   [depth :u8]]
  (cond
    (ak/== value-type 8)
    (skip-string! reader)

    (ak/== value-type 9)
    (if (>= depth 4)
      (set! (az/field (az/deref reader) error_code) parser-limit)
      (let [element-type (read-u32! reader)
            count (read-u64! reader)]
        (if (> count gguf-max-array-elements)
          (set! (az/field (az/deref reader) error_code) parser-limit)
          (dotimes [_ count]
            (skip-value! reader element-type (+ depth 1))))))

    :else
    (let [width (scalar-byte-size value-type)]
      (if (ak/== width 0)
        (set! (az/field (az/deref reader) error_code) parser-unsupported-type)
        (skip-bytes! reader width)))))

(az/defn parse-gguf
  {:attrs #{:public :implicit-return}}
  :-
  GgufSummary
  [[bytes [:c-pointer :u8]]
   [length :usize]]
  (let [^{:var true :zig/type Reader}
        reader (Reader {:bytes bytes :length length :cursor 0 :error_code parser-ok})
        magic (read-u32! (ak/& reader))
        version (read-u32! (ak/& reader))
        tensor-count (read-u64! (ak/& reader))
        metadata-count (read-u64! (ak/& reader))
        ^{:var true :zig/type :u32} f32-count 0
        ^{:var true :zig/type :u32} q4-count 0
        ^{:var true :zig/type :u32} q6-count 0]
    (set! tensor-catalog-count 0)
    (set! metadata-catalog-count 0)
    (when (ak/!= magic gguf-magic)
      (set! (az/field reader error_code) parser-bad-magic))
    (when (or (ak/== version 0) (> version gguf-max-version))
      (set! (az/field reader error_code) parser-bad-version))
    (when (or (> tensor-count gguf-max-tensors)
              (> metadata-count gguf-max-metadata))
      (set! (az/field reader error_code) parser-limit))
    (dotimes [metadata-index metadata-count]
      (let [key (read-string-view! (ak/& reader))
            value-type (read-u32! (ak/& reader))]
        (if (ak/== value-type 9)
          (let [element-type (read-u32! (ak/& reader))
                element-count (read-u64! (ak/& reader))
                value-start (az/field reader cursor)]
            (when (< metadata-index metadata-not-found)
              (set! (az/index metadata-catalog (ak/intCast metadata-index))
                    (MetadataInfo
                     {:key_start (az/field key start)
                      :key_length (az/field key length)
                      :value_type value-type
                      :element_type element-type
                      :value_start value-start
                      :element_count element-count})))
            (if (> element-count gguf-max-array-elements)
              (set! (az/field reader error_code) parser-limit)
              (dotimes [_ element-count]
                (skip-value! (ak/& reader) element-type 1))))
          (let [value-start (az/field reader cursor)]
            (when (< metadata-index metadata-not-found)
              (set! (az/index metadata-catalog (ak/intCast metadata-index))
                    (MetadataInfo
                     {:key_start (az/field key start)
                      :key_length (az/field key length)
                      :value_type value-type
                      :element_type 0
                      :value_start value-start
                      :element_count 1})))
            (skip-value! (ak/& reader) value-type 0)))))
    (dotimes [tensor-index tensor-count]
      (let [name (read-string-view! (ak/& reader))
            dimensions (read-u32! (ak/& reader))
            ^{:var true :zig/type [:array 4 :u64]}
            shape (az/array-init [:array 4 :u64] [0 0 0 0])]
        (if (> dimensions 4)
          (set! (az/field reader error_code) parser-limit)
          (dotimes [dimension dimensions]
            (set! (az/index shape dimension) (read-u64! (ak/& reader)))))
        (let [ggml-type (read-u32! (ak/& reader))
              relative-offset (read-u64! (ak/& reader))]
          (when (ak/== ggml-type 0) (set! f32-count (+ f32-count 1)))
          (when (ak/== ggml-type 2) (set! q4-count (+ q4-count 1)))
          (when (ak/== ggml-type 14) (set! q6-count (+ q6-count 1)))
          (when (< tensor-index gguf-max-tensors)
            (set! (az/index tensor-catalog (ak/intCast tensor-index))
                  (TensorInfo {:name_start (az/field name start)
                               :name_length (az/field name length)
                               :dimension_count (ak/intCast dimensions)
                               :dimensions shape
                               :ggml_type ggml-type
                               :relative_offset relative-offset
                               :data_address 0}))))))
    (let [descriptor-end (az/field reader cursor)
          data-offset (* (/ (+ descriptor-end 31) 32) 32)
          valid (and (ak/== (az/field reader error_code) parser-ok)
                     (<= data-offset length))]
      (when valid
        (set! metadata-catalog-count (ak/intCast metadata-count))
        (set! tensor-catalog-count (ak/intCast tensor-count))
        (dotimes [tensor-index tensor-catalog-count]
          (set! (az/field (az/index tensor-catalog tensor-index) data_address)
                (+ (ak/intFromPtr bytes)
                   data-offset
                   (ak/as :usize
                          (ak/intCast
                           (az/field (az/index tensor-catalog tensor-index)
                                     relative_offset)))))))
      (GgufSummary {:loaded true :valid valid
                    :error_code (az/field reader error_code)
                    :version version :tensor_count tensor-count
                    :metadata_count metadata-count
                    :f32_tensors f32-count :q4_0_tensors q4-count
                    :q6_k_tensors q6-count
                    :descriptor_end descriptor-end
                    :data_offset data-offset :file_size length}))))

(az/defn action-head-header-u32
  {:export false :implicit-return true}
  :-
  :u32
  [[header [:pointer {:size :c :const? true} :u8]]
   [offset :usize]]
  (+ (ak/as :u32 (az/index header offset))
     (* (ak/as :u32 (az/index header (+ offset 1))) 256)
     (* (ak/as :u32 (az/index header (+ offset 2))) 65536)
     (* (ak/as :u32 (az/index header (+ offset 3))) 16777216)))

(az/defn unload-action-head!
  "Disable the racing-specific head without touching the shared base model."
  {:attrs #{:public}}
  :-
  :void
  []
  (set! action-head-summary
        (ActionHeadSummary
         {:loaded false :valid false :error_code 1 :version 0
          :input_count 0 :output_count 0
          :observation_schema 0 :action_schema 0
          :weight_count 0 :file_size 0})))

(az/defn load-action-head!
  "Load the verified fixed-layout A-H action head. The file contains a
  32-byte little-endian header, 8x768 row-major f32 weights, and 8 f32 biases."
  {:attrs #{:public :implicit-return}}
  :-
  ActionHeadSummary
  [[path [:pointer {:size :c :const? true} :u8]]]
  (do
    (unload-action-head!)
    (let [file (runtime/fopen path "rb")]
      (if (ak/== file null)
        action-head-summary
        (do
          (set! _ (runtime/fseek file 0 2))
          (let [signed-size (runtime/ftell file)]
            (set! _ (runtime/fseek file 0 0))
            (if (<= signed-size 0)
              (set! action-head-summary
                    (ActionHeadSummary
                     {:loaded true :valid false :error_code 2 :version 0
                      :input_count 0 :output_count 0
                      :observation_schema 0 :action_schema 0
                      :weight_count 0 :file_size 0}))
              (let [size (ak/as :usize (ak/intCast signed-size))
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
                  (set! action-head-summary
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
                    (set! action-head-summary
                          (ActionHeadSummary
                           {:loaded true :valid valid
                            :error_code (if valid 0 4)
                            :version version :input_count input-count
                            :output_count output-count
                            :observation_schema observation-schema
                            :action_schema action-schema
                            :weight_count weight-count :file_size size})))))))
          (set! _ (runtime/fclose file))
          action-head-summary)))))

(az/defn action-head-status
  {:attrs #{:public :implicit-return}}
  :-
  ActionHeadSummary
  []
  action-head-summary)

(az/defn unload-model!
  :-
  :void
  []
  (free-sequences!)
  (unload-action-head!)
  (when (ak/!= model-bytes null)
    (runtime/free (az/cast (az/unwrap model-bytes) [:* :anyopaque])))
  (set! model-bytes null)
  (set! model-byte-count 0)
  (set! tensor-catalog-count 0)
  (set! metadata-catalog-count 0)
  (set! model-summary
        (GgufSummary {:loaded false :valid false :error_code parser-truncated
                      :version 0 :tensor_count 0 :metadata_count 0
                      :f32_tensors 0 :q4_0_tensors 0 :q6_k_tensors 0
                      :descriptor_end 0 :data_offset 0 :file_size 0})))

(az/defn tensor-info
  "Inspect one parsed tensor descriptor by stable GGUF order."
  :-
  TensorInfo
  [[index :usize]]
  (if (< index tensor-catalog-count)
    (az/index tensor-catalog index)
    (TensorInfo {:name_start 0 :name_length 0 :dimension_count 0
                 :dimensions (az/array-init [:array 4 :u64] [0 0 0 0])
                 :ggml_type 0 :relative_offset 0 :data_address 0})))

(az/defn metadata-info
  "Inspect one parsed GGUF metadata descriptor by stable file order."
  {:attrs #{:public :implicit-return}}
  :-
  MetadataInfo
  [[index :usize]]
  (if (< index metadata-catalog-count)
    (az/index metadata-catalog index)
    (MetadataInfo {:key_start 0 :key_length 0 :value_type 0
                   :element_type 0 :value_start 0 :element_count 0})))

(az/defn metadata-name-byte
  "Return one UTF-8 byte from a metadata key."
  {:attrs #{:public :implicit-return}}
  :-
  :u8
  [[metadata-index :usize]
   [byte-index :usize]]
  (if (or (ak/== model-bytes null)
          (>= metadata-index metadata-catalog-count)
          (>= byte-index
              (az/field (az/index metadata-catalog metadata-index) key_length)))
    0
    (az/index (az/unwrap model-bytes)
              (+ (az/field (az/index metadata-catalog metadata-index) key_start)
                 byte-index))))

(az/defn metadata-name-equals
  {:export false :implicit-return true}
  :-
  :bool
  [[metadata-index :usize]
   [expected [:pointer {:size :c :const? true} :u8]]]
  (if (or (ak/== model-bytes null)
          (>= metadata-index metadata-catalog-count))
    false
    (let [length (az/field (az/index metadata-catalog metadata-index) key_length)
          ^{:var true :zig/type :bool} equal true]
      (dotimes [byte-index length]
        (when (ak/!= (metadata-name-byte metadata-index byte-index)
                     (az/index expected byte-index))
          (set! equal false)))
      (and equal (ak/== (az/index expected length) 0)))))

(az/defn find-metadata
  "Resolve a GGUF metadata entry by its exact zero-terminated key."
  {:attrs #{:public :implicit-return}}
  :-
  :usize
  [[expected [:pointer {:size :c :const? true} :u8]]]
  (let [^{:var true :zig/type :usize} found metadata-not-found]
    (dotimes [metadata-index metadata-catalog-count]
      (when (and (ak/== found metadata-not-found)
                 (metadata-name-equals metadata-index expected))
        (set! found metadata-index)))
    found))

(az/defn model-u32-at
  {:export false :implicit-return true}
  :-
  :u32
  [[offset :usize]]
  (if (or (ak/== model-bytes null) (> (+ offset 4) model-byte-count))
    0
    (+ (ak/as :u32 (az/index (az/unwrap model-bytes) offset))
       (* (ak/as :u32 (az/index (az/unwrap model-bytes) (+ offset 1))) 256)
       (* (ak/as :u32 (az/index (az/unwrap model-bytes) (+ offset 2))) 65536)
       (* (ak/as :u32 (az/index (az/unwrap model-bytes) (+ offset 3))) 16777216))))

(az/defn metadata-u32
  "Read a scalar u32 metadata value, returning `fallback` on type mismatch."
  {:attrs #{:public :implicit-return}}
  :-
  :u32
  [[metadata-index :usize]
   [fallback :u32]]
  (if (and (< metadata-index metadata-catalog-count)
           (ak/== (az/field (az/index metadata-catalog metadata-index) value_type) 4))
    (model-u32-at
     (az/field (az/index metadata-catalog metadata-index) value_start))
    fallback))

(az/defn metadata-f32
  "Read a scalar f32 metadata value, returning `fallback` on type mismatch."
  {:attrs #{:public :implicit-return}}
  :-
  :f32
  [[metadata-index :usize]
   [fallback :f32]]
  (if (and (< metadata-index metadata-catalog-count)
           (ak/== (az/field (az/index metadata-catalog metadata-index) value_type) 6))
    (ak/as :f32
           (ak/bitCast
            (model-u32-at
             (az/field (az/index metadata-catalog metadata-index) value_start))))
    fallback))

(az/defn ascii-byte-token
  "Map one ASCII byte to the model's exact GPT-2 base-byte token id."
  {:export false :implicit-return true}
  :-
  :u32
  [[byte :u8]]
  (cond
    (and (>= byte 33) (<= byte 126)) (- (ak/as :u32 byte) 33)
    (ak/== byte 32) 220
    (ak/== byte 10) 198
    (ak/== byte 9) 197
    (ak/== byte 13) 201
    :else 100269))

(az/defn tokenize-compact-ascii
  "Encode a compact ASCII race prompt without allocation. This exact byte-level
  encoding is intentionally valid before the optimized BPE merger is added."
  {:attrs #{:public :implicit-return}}
  :-
  TokenizationReport
  [[bytes [:pointer {:size :c :const? true} :u8]]
   [length :usize]]
  (let [count (ak/min length tokenizer-capacity)
        ^{:var true :zig/type :bool} valid true
        ^{:var true :zig/type :u16} unsupported (ak/intCast length)
        ^{:var true :zig/type [:array 128 :u32]}
        tokens (std-mem/zeroes (az/type [:array 128 :u32]))]
    (dotimes [index count]
      (let [byte (az/index bytes index)
            token (ascii-byte-token byte)]
        (set! (az/index tokens index) token)
        (when (and valid (ak/== token 100269))
          (set! valid false)
          (set! unsupported (ak/intCast index)))))
    (TokenizationReport
     {:valid valid
      :truncated (> length tokenizer-capacity)
      :byte_count (ak/intCast length)
      :token_count (ak/intCast count)
      :unsupported_index unsupported
      :reserved 0
      :tokens tokens})))

(az/defn tensor-name-byte
  "Return one UTF-8 byte from a tensor name for JVM/native inspection."
  {:attrs #{:public :implicit-return}}
  :-
  :u8
  [[tensor-index :usize]
   [byte-index :usize]]
  (if (or (ak/== model-bytes null)
          (>= tensor-index tensor-catalog-count)
          (>= byte-index
              (az/field (az/index tensor-catalog tensor-index) name_length)))
    0
    (az/index (az/unwrap model-bytes)
              (+ (az/field (az/index tensor-catalog tensor-index) name_start)
                 byte-index))))

(az/defn tensor-name-equals
  {:export false :implicit-return true}
  :-
  :bool
  [[tensor-index :usize]
   [expected [:pointer {:size :c :const? true} :u8]]]
  (if (or (ak/== model-bytes null)
          (>= tensor-index tensor-catalog-count))
    false
    (let [length (az/field (az/index tensor-catalog tensor-index) name_length)
          ^{:var true :zig/type :bool} equal true]
      (dotimes [byte-index length]
        (when (ak/!= (tensor-name-byte tensor-index byte-index)
                     (az/index expected byte-index))
          (set! equal false)))
      (and equal (ak/== (az/index expected length) 0)))))

(az/defn find-tensor
  "Resolve a GGUF tensor by its exact zero-terminated name."
  {:attrs #{:public :implicit-return}}
  :-
  :usize
  [[expected [:pointer {:size :c :const? true} :u8]]]
  (let [^{:var true :zig/type :usize} found tensor-not-found]
    (dotimes [tensor-index tensor-catalog-count]
      (when (and (ak/== found tensor-not-found)
                 (tensor-name-equals tensor-index expected))
        (set! found tensor-index)))
    found))

(az/defn load-model!
  "Read and validate the pinned GGUF into native memory. Inference owns it."
  :-
  GgufSummary
  [[path [:pointer {:size :c :const? true} :u8]]]
  (unload-model!)
  (let [file (runtime/fopen path "rb")]
    (if (ak/== file null)
      model-summary
      (do
        (set! _ (runtime/fseek file 0 2))
        (let [signed-size (runtime/ftell file)]
          (set! _ (runtime/fseek file 0 0))
          (when (> signed-size 0)
            (let [size (ak/as :usize (ak/intCast signed-size))
                  allocation (runtime/malloc size)]
              (when (ak/!= allocation null)
                (let [bytes (az/cast allocation [:c-pointer :u8])
                      count (runtime/fread bytes 1 size file)]
                  (when (ak/== count size)
                    (set! model-bytes bytes)
                    (set! model-byte-count size)
                    (set! model-summary (parse-gguf bytes size)))
                  (when (ak/!= count size)
                    (runtime/free allocation)))))))
        (set! _ (runtime/fclose file))
        model-summary))))

(az/defn inference-summary
  {:attrs #{:public :implicit-return}}
  :-
  GgufSummary
  []
  model-summary)

(az/defn q4-0-dot
  "SIMD Q4_0 dot product for one GGML block of 32 values. The packed low
  and high nibbles become two 16-lane vectors, preserving GGML's layout while
  allowing Zig to use the host's native vector instructions."
  {:export false :public false :implicit-return true}
  :-
  :f32
  [[block [:pointer {:size :c :const? true} :u8]]
   [input [:pointer {:size :c :const? true} :f32]]]
  (let [scale-bits (+ (ak/as :u16 (az/index block 0))
                      (* (ak/as :u16 (az/index block 1)) 256))
        scale (ak/as :f32 (ak/floatCast (ak/as :f16 (ak/bitCast scale-bits))))
        ^{:zig/type [:vector 16 :u8]}
        packed-bytes (ak/bitCast
                      (az/deref
                       (az/cast (+ block 2)
                                [:pointer {:size :one :const? true}
                                 [:array 16 :u8]])))
        ^{:zig/type [:vector 16 :u8]}
        low-unsigned
        (ak/& packed-bytes
              (ak/as (az/type [:vector 16 :u8]) (ak/splat 15)))
        ^{:zig/type [:vector 16 :u8]}
        high-unsigned
        (ak/>> packed-bytes
               (ak/as (az/type [:vector 16 :u8]) (ak/splat 4)))
        ^{:zig/type [:vector 16 :i16]}
        low-signed
        (- (ak/as (az/type [:vector 16 :i16])
                  (ak/intCast low-unsigned))
           (ak/as (az/type [:vector 16 :i16]) (ak/splat 8)))
        ^{:zig/type [:vector 16 :i16]}
        high-signed
        (- (ak/as (az/type [:vector 16 :i16])
                  (ak/intCast high-unsigned))
           (ak/as (az/type [:vector 16 :i16]) (ak/splat 8)))
        ^{:zig/type [:vector 16 :f32]}
        low-values
        (ak/as (az/type [:vector 16 :f32])
               (ak/floatFromInt low-signed))
        ^{:zig/type [:vector 16 :f32]}
        high-values
        (ak/as (az/type [:vector 16 :f32])
               (ak/floatFromInt high-signed))
        ^{:zig/type [:vector 16 :f32]}
        low-input (ak/bitCast
                   (az/deref
                    (az/cast input
                             [:pointer {:size :one :const? true}
                              [:array 16 :f32]])))
        ^{:zig/type [:vector 16 :f32]}
        high-input (ak/bitCast
                    (az/deref
                     (az/cast (+ input 16)
                              [:pointer {:size :one :const? true}
                               [:array 16 :f32]])))]
    (* scale
       (ak/reduce :.Add
                  (+ (* low-values low-input)
                     (* high-values high-input))))))

(az/defn q4-0-value
  "Decode one value from a GGML Q4_0 block without allocating."
  {:export false :public false :implicit-return true}
  :-
  :f32
  [[block [:pointer {:size :c :const? true} :u8]]
   [index :usize]]
  (if (>= index 32)
    0.0
    (let [scale-bits (+ (ak/as :u16 (az/index block 0))
                        (* (ak/as :u16 (az/index block 1)) 256))
          scale (ak/as :f32
                       (ak/floatCast (ak/as :f16 (ak/bitCast scale-bits))))
          packed-byte (az/index block (+ 2 (mod index 16)))
          quantized (if (< index 16)
                      (mod packed-byte 16)
                      (/ packed-byte 16))]
      (* scale
         (ak/as :f32
                (ak/floatFromInt (- (ak/as :i16 quantized) 8)))))))

(az/defn rms-norm!
  "Allocation-free RMSNorm over one dense activation vector."
  :-
  :void
  [[output [:c-pointer :f32]]
   [input [:pointer {:size :c :const? true} :f32]]
   [weights [:pointer {:size :c :const? true} :f32]]
   [length :usize]
   [epsilon :f32]]
  (let [^{:var true :zig/type :f32} square-sum 0.0]
    (dotimes [index length]
      (set! square-sum (+ square-sum (* (az/index input index)
                                       (az/index input index)))))
    (let [inverse-rms (/ 1.0 (std-math/sqrt (+ (/ square-sum
                                                   (ak/as :f32 (ak/floatFromInt length)))
                                                epsilon)))]
      (dotimes [index length]
        (set! (az/index output index)
              (* (az/index input index) inverse-rms (az/index weights index)))))))

(az/defn softmax!
  "Stable in-place softmax used by the attention reference path."
  :-
  :void
  [[values [:c-pointer :f32]]
   [length :usize]]
  (when (> length 0)
    (let [^{:var true :zig/type :f32} maximum (az/index values 0)
          ^{:var true :zig/type :f32} total 0.0]
      (dotimes [index length]
        (set! maximum (ak/max maximum (az/index values index))))
      (dotimes [index length]
        (let [value (std-math/exp (- (az/index values index) maximum))]
          (set! (az/index values index) value)
          (set! total (+ total value))))
      (dotimes [index length]
        (set! (az/index values index) (/ (az/index values index) total))))))

(az/defn softplus
  {:export false :public false :implicit-return true}
  :-
  :f32
  [[value :f32]]
  (if (> value 20.0)
    value
    (std-math/log1p (std-math/exp value))))

(az/defn mamba-selective-step!
  "One exact recurrent Mamba-2 selective-state update for the model's
  single-group layout. State is `[head][head-component][ssm-component]`."
  {:attrs #{:public}}
  :-
  :void
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
              ^{:var true :zig/type :f32} total 0.0]
          (dotimes [state-component state-size]
            (let [state-index (+ (* hidden-index state-size) state-component)
                  next-state (+ (* (az/index state state-index) decay)
                                (* delta (az/index b state-component)
                                   hidden-value))]
              (set! (az/index state state-index) next-state)
              (set! total (+ total
                             (* next-state (az/index c state-component))))))
          (set! (az/index output hidden-index)
                (+ total (* hidden-value (az/index d head)))))))))

(az/defn tensor-rms-norm-gated!
  "Apply Granite's SiLU gate before weighted RMS normalization."
  {:attrs #{:public :implicit-return}}
  :-
  :bool
  [[output [:c-pointer :f32]]
   [hidden [:pointer {:size :c :const? true} :f32]]
   [gate [:pointer {:size :c :const? true} :f32]]
   [weights-index :usize]
   [length :usize]
   [epsilon :f32]]
  (if (or (>= weights-index tensor-catalog-count)
          (ak/!= (az/field (az/index tensor-catalog weights-index) ggml_type) 0))
    false
    (let [^{:var true :zig/type :f32} square-sum 0.0]
      (dotimes [index length]
        (let [value (* (az/index hidden index)
                       (silu (az/index gate index)))]
          (set! (az/index output index) value)
          (set! square-sum (+ square-sum (* value value)))))
      (let [inverse-rms (/ 1.0
                           (std-math/sqrt
                            (+ (/ square-sum
                                  (ak/as :f32 (ak/floatFromInt length)))
                               epsilon)))]
        (dotimes [index length]
          (set! (az/index output index)
                (* (az/index output index) inverse-rms
                   (tensor-element weights-index index)))))
      true)))

(az/defn attention-layer?
  "Whether one Granite hybrid layer uses causal GQA instead of Mamba-2."
  {:attrs #{:public :implicit-return}}
  :-
  :bool
  [[layer :usize]]
  (or (ak/== layer 10)
      (ak/== layer 13)
      (ak/== layer 17)
      (ak/== layer 27)))

(az/defn attention-layer-slot
  "Map model layer 10/13/17/27 to compact KV slot 0/1/2/3."
  {:attrs #{:public :implicit-return}}
  :-
  :usize
  [[layer :usize]]
  (cond
    (ak/== layer 10) 0
    (ak/== layer 13) 1
    (ak/== layer 17) 2
    (ak/== layer 27) 3
    :else model-attention-layer-count))

(az/defn attention-layers-before
  {:export false :implicit-return true}
  :-
  :usize
  [[layer :usize]]
  (cond
    (< layer 11) 0
    (< layer 14) 1
    (< layer 18) 2
    (< layer 28) 3
    :else 4))

(az/defn layer-base-index
  "Resolve the first tensor in a pinned Granite layer without name scans."
  {:attrs #{:public :implicit-return}}
  :-
  :usize
  [[layer :usize]]
  (if (>= layer model-layer-count)
    tensor-not-found
    (- (+ 2 (* layer 13)) (* (attention-layers-before layer) 4))))

(az/defn layer-attention-norm-index
  {:export false :implicit-return true}
  :-
  :usize
  [[layer :usize]]
  (let [base (layer-base-index layer)]
    (if (ak/== base tensor-not-found)
      tensor-not-found
      (+ base (if (attention-layer? layer) 1 0)))))

(az/defn layer-ffn-down-index
  {:export false :implicit-return true}
  :-
  :usize
  [[layer :usize]]
  (let [base (layer-base-index layer)]
    (if (ak/== base tensor-not-found)
      tensor-not-found
      (+ base (if (attention-layer? layer)
                (ak/as :usize 5)
                (ak/as :usize 1))))))

(az/defn layer-ffn-gate-index
  {:export false :implicit-return true}
  :-
  :usize
  [[layer :usize]]
  (let [base (layer-base-index layer)]
    (if (ak/== base tensor-not-found)
      tensor-not-found
      (+ base (if (attention-layer? layer)
                (ak/as :usize 6)
                (ak/as :usize 2))))))

(az/defn layer-ffn-norm-index
  {:export false :implicit-return true}
  :-
  :usize
  [[layer :usize]]
  (let [base (layer-base-index layer)]
    (if (ak/== base tensor-not-found)
      tensor-not-found
      (+ base (if (attention-layer? layer)
                (ak/as :usize 7)
                (ak/as :usize 3))))))

(az/defn layer-ffn-up-index
  {:export false :implicit-return true}
  :-
  :usize
  [[layer :usize]]
  (let [base (layer-base-index layer)]
    (if (ak/== base tensor-not-found)
      tensor-not-found
      (+ base (if (attention-layer? layer)
                (ak/as :usize 8)
                (ak/as :usize 4))))))

(az/defn free-sequences!
  "Release all native racer cognition state while leaving shared weights loaded."
  {:attrs #{:public}}
  :-
  :void
  []
  (when (ak/!= sequence-memory null)
    (runtime/free
     (az/cast (az/unwrap sequence-memory) [:* :anyopaque])))
  (set! sequence-memory null)
  (set! sequence-memory-floats 0)
  (dotimes [racer sequence-racer-count]
    (set! (az/index sequence-positions racer) 0)))

(az/defn reset-all-sequences!
  "Clear every recurrent, convolution, and KV state in-place."
  {:attrs #{:public :implicit-return}}
  :-
  :bool
  []
  (let [initialized (ak/!= sequence-memory null)]
    (when initialized
      (let [memory (az/unwrap sequence-memory)]
        (dotimes [index sequence-memory-floats]
          (set! (az/index memory index) 0.0)))
      (dotimes [racer sequence-racer-count]
        (set! (az/index sequence-positions racer) 0))
      (dotimes [index (* sequence-racer-count action-head-input-count)]
        (set! (az/index action-head-inputs index) 0.0)))
    initialized))

(az/defn initialize-sequences!
  "Own one shared allocation containing eight isolated model sequence states."
  {:attrs #{:public :implicit-return}}
  :-
  :bool
  []
  (free-sequences!)
  (let [allocation (runtime/malloc sequence-total-bytes)
        ^{:var true :zig/type :bool} initialized false]
    (when (ak/!= allocation null)
      (set! sequence-memory (az/cast allocation [:c-pointer :f32]))
      (set! sequence-memory-floats sequence-total-floats)
      (set! initialized (reset-all-sequences!)))
    initialized))

(az/defn reset-sequence!
  "Clear one racer's independent recurrent and KV history."
  {:attrs #{:public :implicit-return}}
  :-
  :bool
  [[racer :usize]]
  (let [valid (and (< racer sequence-racer-count)
                   (ak/!= sequence-memory null))]
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
        (dotimes [index mamba-count]
          (set! (az/index memory (+ mamba-start index)) 0.0))
        (dotimes [index conv-count]
          (set! (az/index memory (+ conv-start index)) 0.0))
        (dotimes [index kv-count]
          (set! (az/index memory (+ key-start index)) 0.0)
          (set! (az/index memory (+ value-start index)) 0.0))
        (dotimes [index action-head-input-count]
          (set! (az/index action-head-inputs
                          (+ (* racer action-head-input-count) index))
                0.0))
        (set! (az/index sequence-positions racer) 0)))
    valid))

(az/defn copy-last-hidden!
  "Copy one racer's most recent final normalized hidden state for offline
  training or inspection. The caller owns `output` and at least 768 floats."
  {:attrs #{:public :implicit-return}}
  :-
  :bool
  [[racer :usize]
   [output [:c-pointer :f32]]]
  (let [valid (and (< racer sequence-racer-count)
                   (> (az/index sequence-positions racer) 0))]
    (when valid
      (dotimes [index model-hidden-size]
        (set! (az/index output index)
              (az/index action-head-inputs
                        (+ (* racer action-head-input-count)
                           (* (ak/as :usize
                                     (ak/min
                                      (- (az/index sequence-positions racer) 1)
                                      (ak/as :u16 7)))
                              model-hidden-size)
                           index)))))
    valid))

(az/defn copy-action-features!
  "Copy all eight per-token hidden states used by the racing action head."
  {:attrs #{:public :implicit-return}}
  :-
  :bool
  [[racer :usize]
   [output [:c-pointer :f32]]]
  (let [valid (and (< racer sequence-racer-count)
                   (>= (az/index sequence-positions racer)
                       action-head-token-count))]
    (when valid
      (dotimes [index action-head-input-count]
        (set! (az/index output index)
              (az/index action-head-inputs
                        (+ (* racer action-head-input-count) index)))))
    valid))

(az/defn sequence-summary
  "Return allocation size and each racer's independent token position."
  {:attrs #{:public :implicit-return}}
  :-
  SequenceSummary
  []
  (SequenceSummary
   {:initialized (ak/!= sequence-memory null)
    :racer_count (ak/intCast sequence-racer-count)
    :capacity (ak/intCast sequence-capacity)
    :state_bytes (if (ak/== sequence-memory null) 0 sequence-total-bytes)
    :positions sequence-positions}))

(az/defn sequence-mamba-state
  {:export false :implicit-return true}
  :-
  [:optional [:c-pointer :f32]]
  [[racer :usize]
   [layer :usize]]
  (if (or (ak/== sequence-memory null)
          (>= racer sequence-racer-count)
          (>= layer model-layer-count)
          (attention-layer? layer))
    null
    (+ (az/unwrap sequence-memory)
       (* racer model-mamba-layer-count model-mamba-recurrent-size)
       (* (- layer (attention-layers-before layer))
          model-mamba-recurrent-size))))

(az/defn sequence-conv-state
  {:export false :implicit-return true}
  :-
  [:optional [:c-pointer :f32]]
  [[racer :usize]
   [layer :usize]]
  (if (or (ak/== sequence-memory null)
          (>= racer sequence-racer-count)
          (>= layer model-layer-count)
          (attention-layer? layer))
    null
    (+ (az/unwrap sequence-memory)
       sequence-mamba-floats
       (* racer model-mamba-layer-count model-mamba-conv-state-size)
       (* (- layer (attention-layers-before layer))
          model-mamba-conv-state-size))))

(az/defn sequence-key-cache
  {:export false :implicit-return true}
  :-
  [:optional [:c-pointer :f32]]
  [[racer :usize]
   [layer :usize]]
  (let [slot (attention-layer-slot layer)]
    (if (or (ak/== sequence-memory null)
            (>= racer sequence-racer-count)
            (>= slot model-attention-layer-count))
      null
      (+ (az/unwrap sequence-memory)
         sequence-mamba-floats sequence-conv-floats
         (* racer model-attention-layer-count sequence-capacity
            model-attention-kv-size)
         (* slot sequence-capacity model-attention-kv-size)))))

(az/defn sequence-value-cache
  {:export false :implicit-return true}
  :-
  [:optional [:c-pointer :f32]]
  [[racer :usize]
   [layer :usize]]
  (let [slot (attention-layer-slot layer)]
    (if (or (ak/== sequence-memory null)
            (>= racer sequence-racer-count)
            (>= slot model-attention-layer-count))
      null
      (+ (az/unwrap sequence-memory)
         sequence-mamba-floats sequence-conv-floats sequence-kv-floats
         (* racer model-attention-layer-count sequence-capacity
            model-attention-kv-size)
         (* slot sequence-capacity model-attention-kv-size)))))

(az/defn layer-ffn!
  "Execute one Granite SwiGLU FFN and install its scaled residual."
  {:attrs #{:public :implicit-return}}
  :-
  :bool
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
        ^{:var true :zig/type :bool}
        valid (ak/! (or (ak/== norm-index tensor-not-found)
                        (ak/== gate-index tensor-not-found)
                        (ak/== up-index tensor-not-found)
                        (ak/== down-index tensor-not-found)))]
    (when valid
      (set! valid
            (tensor-rms-norm! normalized hidden norm-index
                              model-hidden-size model-rms-epsilon)))
    (when valid
      (set! valid (tensor-matvec! gate model-ffn-size gate-index normalized)))
    (when valid
      (set! valid (tensor-matvec! up model-ffn-size up-index normalized)))
    (when valid
      (dotimes [index model-ffn-size]
        (set! (az/index activated index)
              (* (silu (az/index gate index)) (az/index up index))))
      (set! valid
            (tensor-matvec! output model-hidden-size down-index activated)))
    (when valid
      (dotimes [index model-hidden-size]
        (set! (az/index hidden index)
              (+ (az/index hidden index)
                 (* model-residual-multiplier (az/index output index))))))
    valid))

(az/defn mamba-layer-step!
  "Execute one streaming Granite Mamba-2 layer and install its scaled residual.
  Recurrent and convolution state belong to exactly one racer and one layer."
  {:attrs #{:public :implicit-return}}
  :-
  :bool
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
        ^{:var true :zig/type [:array 48 :f32]}
        a (std-mem/zeroes (az/type [:array 48 :f32]))
        ^{:var true :zig/type [:array 48 :f32]}
        d (std-mem/zeroes (az/type [:array 48 :f32]))
        ^{:var true :zig/type [:array 48 :f32]}
        dt-bias (std-mem/zeroes (az/type [:array 48 :f32]))
        ^{:var true :zig/type :bool}
        valid (and (< layer model-layer-count)
                   (ak/! (attention-layer? layer))
                   (ak/!= base tensor-not-found))]
    (when valid
      (set! valid
            (tensor-rms-norm! normalized hidden norm-index
                              model-hidden-size model-rms-epsilon)))
    (when valid
      (set! valid (tensor-matvec! projected 3376 in-index normalized)))
    (when valid
      (dotimes [channel model-mamba-conv-size]
        (let [state-start (* channel 3)
              weight-start (* channel 4)
              current (az/index projected (+ model-mamba-inner-size channel))
              ^{:var true :zig/type :f32}
              total (tensor-element conv-bias-index channel)]
          (dotimes [tap 3]
            (set! total
                  (+ total
                     (* (az/index conv-state (+ state-start tap))
                        (tensor-element conv-weight-index
                                        (+ weight-start tap))))))
          (set! total
                (+ total
                   (* current
                      (tensor-element conv-weight-index (+ weight-start 3)))))
          (set! (az/index conv-state state-start)
                (az/index conv-state (+ state-start 1)))
          (set! (az/index conv-state (+ state-start 1))
                (az/index conv-state (+ state-start 2)))
          (set! (az/index conv-state (+ state-start 2)) current)
          (set! (az/index convolved channel) (silu total))))
      (dotimes [head model-mamba-head-count]
        (set! (az/index a head)
              (- 0.0 (std-math/exp (tensor-element a-index head))))
        (set! (az/index d head) (tensor-element d-index head))
        (set! (az/index dt-bias head) (tensor-element dt-bias-index head)))
      (mamba-selective-step!
       scan-output recurrent-state convolved
       (+ projected 3328)
       (ak/& (az/index a 0))
       (+ convolved 1536)
       (+ convolved 1664)
       (ak/& (az/index d 0))
       (ak/& (az/index dt-bias 0))
       model-mamba-head-count model-mamba-head-size
       model-mamba-state-size)
      (set! valid
            (tensor-rms-norm-gated!
             gated-output scan-output projected mamba-norm-index
             model-mamba-inner-size model-rms-epsilon)))
    (when valid
      (set! valid
            (tensor-matvec! branch-output model-hidden-size out-index
                            gated-output)))
    (when valid
      (dotimes [index model-hidden-size]
        (set! (az/index hidden index)
              (+ (az/index hidden index)
                 (* model-residual-multiplier
                    (az/index branch-output index))))))
    valid))

(az/defn mamba-ffn-layer!
  "Execute a complete recurrent Granite block for one token."
  {:attrs #{:public :implicit-return}}
  :-
  :bool
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

(az/defn attention-layer-step!
  "Execute one causal NoPE grouped-query-attention layer for a single token."
  {:attrs #{:public :implicit-return}}
  :-
  :bool
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
        ^{:var true :zig/type :bool}
        valid (and (< position sequence-capacity)
                   (attention-layer? layer)
                   (ak/!= base tensor-not-found))]
    (when valid
      (set! valid
            (tensor-rms-norm! normalized hidden norm-index
                              model-hidden-size model-rms-epsilon)))
    (when valid
      (set! valid
            (tensor-matvec! query model-hidden-size query-index normalized)))
    (when valid
      (set! valid
            (tensor-matvec! key model-attention-kv-size key-index normalized)))
    (when valid
      (set! valid
            (tensor-matvec! value model-attention-kv-size value-index normalized)))
    (when valid
      (dotimes [component model-attention-kv-size]
        (set! (az/index key-cache
                        (+ (* position model-attention-kv-size) component))
              (az/index key component))
        (set! (az/index value-cache
                        (+ (* position model-attention-kv-size) component))
              (az/index value component)))
      (dotimes [query-head model-attention-head-count]
        (let [kv-head (/ query-head 3)
              query-start (* query-head model-attention-head-size)
              kv-start (* kv-head model-attention-head-size)]
          (dotimes [token (+ position 1)]
            (let [cache-start (+ (* token model-attention-kv-size) kv-start)
                  ^{:var true :zig/type :f32} score 0.0]
              (dotimes [component model-attention-head-size]
                (set! score
                      (+ score
                         (* (az/index query (+ query-start component))
                            (az/index key-cache
                                      (+ cache-start component))))))
              (set! (az/index scores token) (* score model-attention-scale))))
          (softmax! scores (+ position 1))
          (dotimes [component model-attention-head-size]
            (let [^{:var true :zig/type :f32} total 0.0]
              (dotimes [token (+ position 1)]
                (set! total
                      (+ total
                         (* (az/index scores token)
                            (az/index value-cache
                                      (+ (* token model-attention-kv-size)
                                         kv-start component))))))
              (set! (az/index attention-output (+ query-start component))
                    total)))))
      (set! valid
            (tensor-matvec! branch-output model-hidden-size out-index
                            attention-output)))
    (when valid
      (dotimes [index model-hidden-size]
        (set! (az/index hidden index)
              (+ (az/index hidden index)
                 (* model-residual-multiplier
                    (az/index branch-output index))))))
    valid))

(az/defn attention-ffn-layer!
  "Execute a complete causal attention Granite block for one token."
  {:attrs #{:public :implicit-return}}
  :-
  :bool
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

(az/defn attention-layer-probe
  "Execute one isolated attention+FFN layer with an empty single-token cache."
  {:attrs #{:public :implicit-return}}
  :-
  :f32
  [[layer :usize]
   [token :usize]
   [component :usize]]
  (if (or (ak/! (attention-layer? layer))
          (>= component model-hidden-size))
    0.0
    (let [^{:var true :zig/type [:array 768 :f32]}
          hidden (std-mem/zeroes (az/type [:array 768 :f32]))
          ^{:var true :zig/type [:array 768 :f32]}
          normalized (std-mem/zeroes (az/type [:array 768 :f32]))
          ^{:var true :zig/type [:array 768 :f32]}
          query (std-mem/zeroes (az/type [:array 768 :f32]))
          ^{:var true :zig/type [:array 256 :f32]}
          key (std-mem/zeroes (az/type [:array 256 :f32]))
          ^{:var true :zig/type [:array 256 :f32]}
          value (std-mem/zeroes (az/type [:array 256 :f32]))
          ^{:var true :zig/type [:array 160 :f32]}
          scores (std-mem/zeroes (az/type [:array 160 :f32]))
          ^{:var true :zig/type [:array 768 :f32]}
          attention-output (std-mem/zeroes (az/type [:array 768 :f32]))
          ^{:var true :zig/type [:array 768 :f32]}
          branch-output (std-mem/zeroes (az/type [:array 768 :f32]))
          ^{:var true :zig/type [:array 2048 :f32]}
          ffn-gate (std-mem/zeroes (az/type [:array 2048 :f32]))
          ^{:var true :zig/type [:array 2048 :f32]}
          ffn-up (std-mem/zeroes (az/type [:array 2048 :f32]))
          ^{:var true :zig/type [:array 2048 :f32]}
          ffn-activated (std-mem/zeroes (az/type [:array 2048 :f32]))
          ^{:var true :zig/type [:array 768 :f32]}
          ffn-output (std-mem/zeroes (az/type [:array 768 :f32]))
          cache-elements (* sequence-capacity model-attention-kv-size)
          cache-allocation
          (runtime/malloc (* 2 cache-elements (ak/sizeOf :f32)))]
      (if (ak/== cache-allocation null)
        0.0
        (do
          (defer (runtime/free cache-allocation))
          (let [key-cache (az/cast cache-allocation [:c-pointer :f32])
                value-cache (+ key-cache cache-elements)]
            (dotimes [index (* 2 cache-elements)]
              (set! (az/index key-cache index) 0.0))
            (dotimes [index model-hidden-size]
              (set! (az/index hidden index)
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

(az/defn mamba-layer-zero-probe
  "Execute the first token through Granite layer 0's normalized Mamba branch
  and residual connection. The recurrent and convolution states start at zero."
  {:attrs #{:public :implicit-return}}
  :-
  :f32
  [[token :usize]
   [component :usize]]
  (let [^{:var true :zig/type [:array 768 :f32]}
        residual (std-mem/zeroes (az/type [:array 768 :f32]))
        ^{:var true :zig/type [:array 768 :f32]}
        normalized (std-mem/zeroes (az/type [:array 768 :f32]))
        ^{:var true :zig/type [:array 3376 :f32]}
        projected (std-mem/zeroes (az/type [:array 3376 :f32]))
        ^{:var true :zig/type [:array 1792 :f32]}
        convolved (std-mem/zeroes (az/type [:array 1792 :f32]))
        ^{:var true :zig/type [:array 1536 :f32]}
        scan-output (std-mem/zeroes (az/type [:array 1536 :f32]))
        ^{:var true :zig/type [:array 1536 :f32]}
        gated-output (std-mem/zeroes (az/type [:array 1536 :f32]))
        ^{:var true :zig/type [:array 768 :f32]}
        branch-output (std-mem/zeroes (az/type [:array 768 :f32]))
        ^{:var true :zig/type [:array 48 :f32]}
        a (std-mem/zeroes (az/type [:array 48 :f32]))
        ^{:var true :zig/type [:array 48 :f32]}
        d (std-mem/zeroes (az/type [:array 48 :f32]))
        ^{:var true :zig/type [:array 48 :f32]}
        dt-bias (std-mem/zeroes (az/type [:array 48 :f32]))
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
        (if (ak/== state-allocation null)
          0.0
          (do
            (defer (runtime/free state-allocation))
            (let [recurrent-state (az/cast state-allocation [:c-pointer :f32])]
              (dotimes [index 196608]
                (set! (az/index recurrent-state index) 0.0))
              (dotimes [index 768]
                (set! (az/index residual index)
                      (* 12.0 (embedding-value-kernel token index))))
              (set! _ (tensor-rms-norm!
                       (ak/& (az/index normalized 0))
                       (ak/& (az/index residual 0)) norm-index 768 0.00001))
              (set! _ (tensor-matvec!
                       (ak/& (az/index projected 0)) 3376 in-index
                       (ak/& (az/index normalized 0))))
              (dotimes [channel 1792]
                (set! (az/index convolved channel)
                      (silu (+ (* (az/index projected (+ 1536 channel))
                                  (tensor-element conv-weight-index
                                                  (+ (* channel 4) 3)))
                               (tensor-element conv-bias-index channel)))))
              (dotimes [head 48]
                (set! (az/index a head)
                      (- 0.0 (std-math/exp (tensor-element a-index head))))
                (set! (az/index d head) (tensor-element d-index head))
                (set! (az/index dt-bias head)
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
              (set! _ (tensor-rms-norm-gated!
                       (ak/& (az/index gated-output 0))
                       (ak/& (az/index scan-output 0))
                       (ak/& (az/index projected 0))
                       mamba-norm-index 1536 0.00001))
              (set! _ (tensor-matvec!
                       (ak/& (az/index branch-output 0)) 768 out-index
                       (ak/& (az/index gated-output 0))))
              (+ (az/index residual component)
                 (* 0.246 (az/index branch-output component))))))))))

(az/defn mamba-layer-zero-full-probe
  "Execute the first token through the complete layer-0 Mamba and FFN block."
  {:attrs #{:public :implicit-return}}
  :-
  :f32
  [[token :usize]
   [component :usize]]
  (if (>= component model-hidden-size)
    0.0
    (let [^{:var true :zig/type [:array 768 :f32]}
          hidden (std-mem/zeroes (az/type [:array 768 :f32]))
          ^{:var true :zig/type [:array 768 :f32]}
          normalized (std-mem/zeroes (az/type [:array 768 :f32]))
          ^{:var true :zig/type [:array 3376 :f32]}
          projected (std-mem/zeroes (az/type [:array 3376 :f32]))
          ^{:var true :zig/type [:array 1792 :f32]}
          convolved (std-mem/zeroes (az/type [:array 1792 :f32]))
          ^{:var true :zig/type [:array 1536 :f32]}
          scan-output (std-mem/zeroes (az/type [:array 1536 :f32]))
          ^{:var true :zig/type [:array 1536 :f32]}
          gated-output (std-mem/zeroes (az/type [:array 1536 :f32]))
          ^{:var true :zig/type [:array 768 :f32]}
          branch-output (std-mem/zeroes (az/type [:array 768 :f32]))
          ^{:var true :zig/type [:array 2048 :f32]}
          ffn-gate (std-mem/zeroes (az/type [:array 2048 :f32]))
          ^{:var true :zig/type [:array 2048 :f32]}
          ffn-up (std-mem/zeroes (az/type [:array 2048 :f32]))
          ^{:var true :zig/type [:array 2048 :f32]}
          ffn-activated (std-mem/zeroes (az/type [:array 2048 :f32]))
          ^{:var true :zig/type [:array 768 :f32]}
          ffn-output (std-mem/zeroes (az/type [:array 768 :f32]))
          ^{:var true :zig/type [:array 5376 :f32]}
          conv-state (std-mem/zeroes (az/type [:array 5376 :f32]))
          state-allocation
          (runtime/malloc (* model-mamba-recurrent-size (ak/sizeOf :f32)))]
      (if (ak/== state-allocation null)
        0.0
        (do
          (defer (runtime/free state-allocation))
          (let [recurrent-state (az/cast state-allocation [:c-pointer :f32])]
            (dotimes [index model-mamba-recurrent-size]
              (set! (az/index recurrent-state index) 0.0))
            (dotimes [index model-hidden-size]
              (set! (az/index hidden index)
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

(az/defn q6-k-value
  "Decode one value from a GGML Q6_K block without allocating."
  {:export false :public false :implicit-return true}
  :-
  :f32
  [[block [:pointer {:size :c :const? true} :u8]]
   [index :usize]]
  (if (>= index 256)
    0.0
    (let [sub-block (/ index 32)
          within (mod index 32)
          low-index (+ (* (/ sub-block 4) 64)
                       (* (mod sub-block 2) 32)
                       within)
          ^{:zig/type :u3} low-shift (if (>= (mod sub-block 4) 2) 4 0)
          high-index (+ 128 (* (/ sub-block 4) 32) within)
          ^{:zig/type :u3} high-shift (ak/intCast (* (mod sub-block 4) 2))
          low (ak/& (ak/>> (az/index block low-index) low-shift) 15)
          high (ak/& (ak/>> (az/index block high-index) high-shift) 3)
          quantized (- (ak/as :i16 (+ low (* high 16))) 32)
          scale-byte (az/index block (+ 192 (/ index 16)))
          scale (ak/as :i8 (ak/bitCast scale-byte))
          delta-bits (+ (ak/as :u16 (az/index block 208))
                        (* (ak/as :u16 (az/index block 209)) 256))
          delta (ak/as :f32
                       (ak/floatCast (ak/as :f16 (ak/bitCast delta-bits))))]
      (* delta
         (ak/as :f32 (ak/floatFromInt scale))
         (ak/as :f32 (ak/floatFromInt quantized))))))

(az/defn q6-k-dot
  "Scalar reference dot product for one 256-value GGML Q6_K block."
  {:export false :public false :implicit-return true}
  :-
  :f32
  [[block [:pointer {:size :c :const? true} :u8]]
   [input [:pointer {:size :c :const? true} :f32]]]
  (let [^{:var true :zig/type :f32} total 0.0]
    (dotimes [index 256]
      (set! total (+ total (* (q6-k-value block index)
                             (az/index input index)))))
    total))

(az/defn embedding-value-kernel
  "Read one value from the model's Q6_K token embedding tensor."
  {:export false :public false :implicit-return true}
  :-
  :f32
  [[token :usize]
   [component :usize]]
  (if (or (ak/== (az/field model-summary valid) false)
          (< tensor-catalog-count 2)
          (>= token 100352)
          (>= component 768))
    0.0
    (let [tensor (az/index tensor-catalog 1)
          ^{:zig/type [:c-pointer :u8]}
          bytes (ak/ptrFromInt (az/field tensor data_address))
          row-offset (* token 630)
          block-offset (* (/ component 256) 210)]
      (q6-k-value (+ bytes row-offset block-offset)
                  (mod component 256)))))

(az/defn embedding-value
  "Inspectable wrapper around the private hot-loop embedding decoder."
  {:attrs #{:public :implicit-return}}
  :-
  :f32
  [[token :usize]
   [component :usize]]
  (embedding-value-kernel token component))

(az/defn embedding-row-dot
  "Dot one tied Q6_K token-embedding row with a normalized hidden vector."
  {:attrs #{:public :implicit-return}}
  :-
  :f32
  [[token :usize]
   [input [:pointer {:size :c :const? true} :f32]]]
  (if (or (ak/== (az/field model-summary valid) false)
          (< tensor-catalog-count 2)
          (>= token 100352))
    0.0
    (let [tensor (az/index tensor-catalog 1)
          ^{:zig/type [:c-pointer :u8]}
          bytes (ak/ptrFromInt (az/field tensor data_address))
          row-offset (* token 630)
          ^{:var true :zig/type :f32} total 0.0]
      (dotimes [block 3]
        (set! total
              (+ total
                 (q6-k-dot (+ bytes row-offset (* block 210))
                           (+ input (* block 256))))))
      total)))

(az/defn tensor-element
  "Decode one logical flat tensor element for every supported model layout."
  {:export false :public false :implicit-return true}
  :-
  :f32
  [[tensor-index :usize]
   [element-index :usize]]
  (if (or (>= tensor-index tensor-catalog-count)
          (ak/== (az/field model-summary valid) false))
    0.0
    (let [tensor (az/index tensor-catalog tensor-index)
          ^{:zig/type [:c-pointer :u8]}
          bytes (ak/ptrFromInt (az/field tensor data_address))]
      (cond
        (ak/== (az/field tensor ggml_type) 0)
        (let [^{:zig/type [:c-pointer :f32]}
              values (ak/ptrFromInt (az/field tensor data_address))]
          (az/index values element-index))

        (ak/== (az/field tensor ggml_type) 2)
        (q4-0-value (+ bytes (* (/ element-index 32) 18))
                    (mod element-index 32))

        (ak/== (az/field tensor ggml_type) 14)
        (q6-k-value (+ bytes (* (/ element-index 256) 210))
                    (mod element-index 256))

        :else 0.0))))

(az/defn tensor-row-dot-kernel
  "Allocation-free scalar reference matvec row for F32, Q4_0, or Q6_K."
  {:export false :public false :implicit-return true}
  :-
  :f32
  [[tensor-index :usize]
   [row :usize]
   [input [:pointer {:size :c :const? true} :f32]]]
  (if (or (>= tensor-index tensor-catalog-count)
          (ak/== (az/field model-summary valid) false))
    0.0
    (let [tensor (az/index tensor-catalog tensor-index)
          input-size (ak/as :usize
                            (ak/intCast (az/index (az/field tensor dimensions) 0)))
          output-size (if (> (az/field tensor dimension_count) 1)
                        (ak/as :usize
                               (ak/intCast
                                (az/index (az/field tensor dimensions) 1)))
                        1)
          ^{:zig/type [:c-pointer :u8]}
          bytes (ak/ptrFromInt (az/field tensor data_address))
          ^{:var true :zig/type :f32} total 0.0]
      (if (>= row output-size)
        0.0
        (do
          (cond
            (ak/== (az/field tensor ggml_type) 0)
            (let [^{:zig/type [:c-pointer :f32]}
                  values (ak/ptrFromInt (az/field tensor data_address))
                  row-start (* row input-size)]
              (dotimes [index input-size]
                (set! total (+ total
                               (* (az/index values (+ row-start index))
                                  (az/index input index))))))

            (ak/== (az/field tensor ggml_type) 2)
            (let [block-count (/ input-size 32)
                  row-start (* row block-count 18)]
              (dotimes [block-index block-count]
                (set! total (+ total
                               (q4-0-dot (+ bytes row-start (* block-index 18))
                                         (+ input (* block-index 32)))))))

            (ak/== (az/field tensor ggml_type) 14)
            (let [block-count (/ input-size 256)
                  row-start (* row block-count 210)]
              (dotimes [block-index block-count]
                (set! total (+ total
                               (q6-k-dot (+ bytes row-start (* block-index 210))
                                         (+ input (* block-index 256))))))))
          total)))))

(az/defn tensor-row-dot
  "Inspectable wrapper around the private matrix-row hot loop."
  {:attrs #{:public :implicit-return}}
  :-
  :f32
  [[tensor-index :usize]
   [row :usize]
   [input [:pointer {:size :c :const? true} :f32]]]
  (tensor-row-dot-kernel tensor-index row input))

(az/defn tensor-matvec!
  "Apply one supported two-dimensional tensor to a dense input vector."
  {:attrs #{:public :implicit-return}}
  :-
  :bool
  [[output [:c-pointer :f32]]
   [output-count :usize]
   [tensor-index :usize]
   [input [:pointer {:size :c :const? true} :f32]]]
  (if (or (>= tensor-index tensor-catalog-count)
          (< (az/field (az/index tensor-catalog tensor-index) dimension_count) 2)
          (ak/!= output-count
                 (ak/as :usize
                        (ak/intCast
                         (az/index
                          (az/field (az/index tensor-catalog tensor-index) dimensions)
                          1)))))
    false
    (do
      (dotimes [row output-count]
        (set! (az/index output row)
              (tensor-row-dot-kernel tensor-index row input)))
      true)))

(az/defn tensor-rms-norm!
  "RMS-normalize through a named F32 model weight vector."
  {:attrs #{:public :implicit-return}}
  :-
  :bool
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
    (let [^{:var true :zig/type :f32} square-sum 0.0]
      (dotimes [index length]
        (set! square-sum (+ square-sum
                            (* (az/index input index)
                               (az/index input index)))))
      (let [inverse-rms (/ 1.0
                           (std-math/sqrt
                            (+ (/ square-sum
                                  (ak/as :f32 (ak/floatFromInt length)))
                               epsilon)))]
        (dotimes [index length]
          (set! (az/index output index)
                (* (az/index input index)
                   inverse-rms
                   (tensor-element weights-index index)))))
      true)))

(az/defn silu
  {:export false :public false :implicit-return true}
  :-
  :f32
  [[value :f32]]
  (/ value (+ 1.0 (std-math/exp (- 0.0 value)))))

(az/defn layer-zero-mlp-probe
  "Run the pinned model's first dense FFN on one real token embedding."
  {:attrs #{:public :implicit-return}}
  :-
  :f32
  [[token :usize]]
  (let [^{:var true :zig/type [:array 768 :f32]}
        hidden (std-mem/zeroes (az/type [:array 768 :f32]))
        ^{:var true :zig/type [:array 768 :f32]}
        normalized (std-mem/zeroes (az/type [:array 768 :f32]))
        ^{:var true :zig/type [:array 2048 :f32]}
        gate (std-mem/zeroes (az/type [:array 2048 :f32]))
        ^{:var true :zig/type [:array 2048 :f32]}
        up (std-mem/zeroes (az/type [:array 2048 :f32]))
        ^{:var true :zig/type [:array 2048 :f32]}
        activated (std-mem/zeroes (az/type [:array 2048 :f32]))
        ^{:var true :zig/type [:array 768 :f32]}
        output (std-mem/zeroes (az/type [:array 768 :f32]))
        norm-index (find-tensor "blk.0.ffn_norm.weight")
        gate-index (find-tensor "blk.0.ffn_gate.weight")
        up-index (find-tensor "blk.0.ffn_up.weight")
        down-index (find-tensor "blk.0.ffn_down.weight")]
    (dotimes [index 768]
      (set! (az/index hidden index)
            (* 12.0 (embedding-value-kernel token index))))
    (if (or (ak/== norm-index tensor-not-found)
            (ak/== gate-index tensor-not-found)
            (ak/== up-index tensor-not-found)
            (ak/== down-index tensor-not-found))
      0.0
      (do
        (set! _ (tensor-rms-norm! (ak/& (az/index normalized 0))
                                   (ak/& (az/index hidden 0))
                                   norm-index 768 0.00001))
        (set! _ (tensor-matvec! (ak/& (az/index gate 0)) 2048 gate-index
                                (ak/& (az/index normalized 0))))
        (set! _ (tensor-matvec! (ak/& (az/index up 0)) 2048 up-index
                                (ak/& (az/index normalized 0))))
        (dotimes [index 2048]
          (set! (az/index activated index)
                (* (silu (az/index gate index)) (az/index up index))))
        (set! _ (tensor-matvec! (ak/& (az/index output 0)) 768 down-index
                                (ak/& (az/index activated 0))))
        (az/index output 0)))))

(az/defn action-head-logit
  "Evaluate one racing action row over Granite's normalized final state."
  {:attrs #{:public :implicit-return}}
  :-
  :f32
  [[action :usize]
   [hidden [:pointer {:size :c :const? true} :f32]]]
  (if (or (ak/! (az/field action-head-summary valid))
          (>= action action-head-output-count))
    0.0
    (let [row-start (* action action-head-input-count)
          ^{:var true :zig/type :f32}
          total (az/index action-head-biases action)]
      (dotimes [index action-head-input-count]
        (set! total
              (+ total
                 (* (az/index action-head-weights (+ row-start index))
                    (az/index hidden index)))))
      total)))

(az/defn forward-token!
  "Run one token through all 32 layers for one independent racer sequence.
  The first constrained vocabulary is the eight one-byte action codes A-H."
  {:attrs #{:public :implicit-return}}
  :-
  ForwardReport
  [[racer :usize]
   [token :usize]]
  (let [^{:var true :zig/type [:array 768 :f32]}
        hidden (std-mem/zeroes (az/type [:array 768 :f32]))
        ^{:var true :zig/type [:array 768 :f32]}
        normalized (std-mem/zeroes (az/type [:array 768 :f32]))
        ^{:var true :zig/type [:array 3376 :f32]}
        projected (std-mem/zeroes (az/type [:array 3376 :f32]))
        ^{:var true :zig/type [:array 1792 :f32]}
        convolved (std-mem/zeroes (az/type [:array 1792 :f32]))
        ^{:var true :zig/type [:array 1536 :f32]}
        scan-output (std-mem/zeroes (az/type [:array 1536 :f32]))
        ^{:var true :zig/type [:array 1536 :f32]}
        gated-output (std-mem/zeroes (az/type [:array 1536 :f32]))
        ^{:var true :zig/type [:array 768 :f32]}
        branch-output (std-mem/zeroes (az/type [:array 768 :f32]))
        ^{:var true :zig/type [:array 2048 :f32]}
        ffn-gate (std-mem/zeroes (az/type [:array 2048 :f32]))
        ^{:var true :zig/type [:array 2048 :f32]}
        ffn-up (std-mem/zeroes (az/type [:array 2048 :f32]))
        ^{:var true :zig/type [:array 2048 :f32]}
        ffn-activated (std-mem/zeroes (az/type [:array 2048 :f32]))
        ^{:var true :zig/type [:array 768 :f32]}
        ffn-output (std-mem/zeroes (az/type [:array 768 :f32]))
        ^{:var true :zig/type [:array 768 :f32]}
        query (std-mem/zeroes (az/type [:array 768 :f32]))
        ^{:var true :zig/type [:array 256 :f32]}
        key (std-mem/zeroes (az/type [:array 256 :f32]))
        ^{:var true :zig/type [:array 256 :f32]}
        value (std-mem/zeroes (az/type [:array 256 :f32]))
        ^{:var true :zig/type [:array 160 :f32]}
        scores (std-mem/zeroes (az/type [:array 160 :f32]))
        ^{:var true :zig/type [:array 768 :f32]}
        attention-output (std-mem/zeroes (az/type [:array 768 :f32]))
        candidate-tokens
        (az/array-init [:array 8 :u32] [32 33 34 35 36 37 38 39])
        ^{:var true :zig/type [:array 8 :f32]}
        candidate-logits (std-mem/zeroes (az/type [:array 8 :f32]))
        ^{:zig/type :usize}
        position (if (< racer sequence-racer-count)
                   (ak/as :usize (az/index sequence-positions racer))
                   sequence-capacity)
        ^{:var true :zig/type :bool}
        valid (and (az/field model-summary valid)
                   (ak/!= sequence-memory null)
                   (< racer sequence-racer-count)
                   (< position sequence-capacity)
                   (< token 100352))
        ^{:var true :zig/type :f32} checksum 0.0
        ^{:var true :zig/type :u32} best-token 32
        ^{:var true :zig/type :f32} best-logit -3.4e38]
    (when valid
      (dotimes [index model-hidden-size]
        (set! (az/index hidden index)
              (* 12.0 (embedding-value-kernel token index))))
      (dotimes [layer model-layer-count]
        (when valid
          (if (attention-layer? layer)
            (let [key-cache (sequence-key-cache racer layer)
                  value-cache (sequence-value-cache racer layer)]
              (if (or (ak/== key-cache null) (ak/== value-cache null))
                (set! valid false)
                (set! valid
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
              (if (or (ak/== recurrent-state null) (ak/== conv-state null))
                (set! valid false)
                (set! valid
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
        (set! valid
              (tensor-rms-norm! (ak/& (az/index normalized 0))
                                (ak/& (az/index hidden 0))
                                0 model-hidden-size model-rms-epsilon)))
      (when valid
        (dotimes [index model-hidden-size]
          (let [hidden-value (az/index normalized index)]
            (set! checksum (+ checksum hidden-value))
            (when (< position action-head-token-count)
              (set! (az/index action-head-inputs
                              (+ (* racer action-head-input-count)
                                 (* position model-hidden-size)
                                 index))
                    hidden-value))))
        (dotimes [candidate 8]
          (let [candidate-token (az/index candidate-tokens candidate)
                logit
                (if (az/field action-head-summary valid)
                  (action-head-logit
                   candidate
                   (ak/& (az/index action-head-inputs
                                   (* racer action-head-input-count))))
                  (/ (embedding-row-dot
                      (ak/as :usize candidate-token)
                      (ak/& (az/index normalized 0)))
                     3.0))]
            (set! (az/index candidate-logits candidate) logit)
            (when (> logit best-logit)
              (set! best-logit logit)
              (set! best-token candidate-token))))
        (set! (az/index sequence-positions racer) (ak/intCast (+ position 1)))))
    (ForwardReport
     {:valid valid
      :racer (ak/intCast racer)
      :position (ak/intCast position)
      :input_token (ak/intCast token)
      :best_token best-token
      :best_logit best-logit
      :hidden_first (az/index normalized 0)
      :hidden_checksum checksum
      :candidate_tokens candidate-tokens
      :candidate_logits candidate-logits})))

(az/defn empty-forward-report
  {:export false :implicit-return true}
  :-
  ForwardReport
  []
  (ForwardReport
   {:valid false :racer 0 :position 0 :input_token 0
    :best_token 0 :best_logit 0.0 :hidden_first 0.0 :hidden_checksum 0.0
    :candidate_tokens (std-mem/zeroes (az/type [:array 8 :u32]))
    :candidate_logits (std-mem/zeroes (az/type [:array 8 :f32]))}))

(az/defn forward-compact-prompt!
  "Tokenize and run one bounded compact ASCII observation entirely in native
  code. Resetting is explicit so callers can retain or replace agent memory."
  {:attrs #{:public :implicit-return}}
  :-
  ForwardReport
  [[racer :usize]
   [bytes [:pointer {:size :c :const? true} :u8]]
   [length :usize]
   [reset :bool]]
  (let [tokenized (tokenize-compact-ascii bytes length)
        ^{:var true :zig/type ForwardReport} report (empty-forward-report)
        ^{:var true :zig/type :bool}
        valid (and (az/field tokenized valid)
                   (ak/! (az/field tokenized truncated))
                   (> (az/field tokenized token_count) 0))]
    (when (and valid reset)
      (set! valid (reset-sequence! racer)))
    (when valid
      (dotimes [index (az/field tokenized token_count)]
        (when valid
          (set! report
                (forward-token!
                 racer
                 (ak/as :usize
                        (az/index (az/field tokenized tokens) index))))
          (set! valid (az/field report valid)))))
    (when (ak/! valid)
      (set! (az/field report valid) false))
    report))

(az/defn kernel-self-test
  "Execute deterministic native fixtures without allocating or invoking a server."
  :-
  KernelReport
  []
  (let [block (az/array-init [:array 18 :u8]
                             [0 60 153 153 153 153 153 153 153 153
                              153 153 153 153 153 153 153 153])
        input (az/array-init [:array 32 :f32]
                             [1.0 1.0 1.0 1.0 1.0 1.0 1.0 1.0
                              1.0 1.0 1.0 1.0 1.0 1.0 1.0 1.0
                              1.0 1.0 1.0 1.0 1.0 1.0 1.0 1.0
                              1.0 1.0 1.0 1.0 1.0 1.0 1.0 1.0])
        norm-input (az/array-init [:array 4 :f32] [1.0 2.0 3.0 4.0])
        weights (az/array-init [:array 4 :f32] [1.0 1.0 1.0 1.0])
        ^{:var true :zig/type [:array 4 :f32]}
        norm-output (az/array-init [:array 4 :f32] [0.0 0.0 0.0 0.0])
        ^{:var true :zig/type [:array 4 :f32]}
        probabilities (az/array-init [:array 4 :f32] [1.0 2.0 3.0 4.0])]
    (rms-norm! (ak/& (az/index norm-output 0)) (ak/& (az/index norm-input 0))
               (ak/& (az/index weights 0)) 4 0.00001)
    (softmax! (ak/& (az/index probabilities 0)) 4)
    (KernelReport
     {:q4_dot (q4-0-dot (ak/& (az/index block 0)) (ak/& (az/index input 0)))
      :rms_first (az/index norm-output 0)
      :rms_last (az/index norm-output 3)
      :softmax_sum (+ (az/index probabilities 0) (az/index probabilities 1)
                      (az/index probabilities 2) (az/index probabilities 3))})))
