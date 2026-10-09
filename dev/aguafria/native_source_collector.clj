(ns aguafria.native-source-collector
  "Allocation-free batch lexical facts from Zig's tokenizer. Developer prototype."
  (:require [aguafria.keyword :as k]
            [aguafria.std.mem :as mem]
            [aguafria.std.zig.Tokenizer :as tokenizer]
            [aguafria.zig :as a]))

(a/defconst max-files :u32 1024)
(a/defconst max-bytes :u32 33554432)
(a/defconst max-exports :u32 64)

(a/defstruct Facts {:layout :extern}
             [[:export_count :u32]
              [:external_exports :u32]
              [:dynamic_exports :u32]
              [:external_syntax :u32]
              [:import_count :u32]
              [:invalid_count :u32]
              [:starts [:array 64 :u32]]
              [:ends [:array 64 :u32]]])

(a/defn- collect-file :u32
  [[buffer [:many-const :u8]] [length :u32] [facts [:* Facts]]]
  (let [source (a/slice-sentinel buffer 0 length 0)
        lexer (k/var (tokenizer/init source))
        phase (k/var 0 :u32)
        steps (k/var 0 :u32)]
    (a/merge! facts {:export_count 0
                     :external_exports 0
                     :dynamic_exports 0
                     :external_syntax 0
                     :import_count 0
                     :invalid_count 0})
    (k/while (k/<= steps length)
      (let [token (tokenizer/next (k/& lexer))
            tag (:tag token)
            start (:start (:loc token))
            end (:end (:loc token))
            text (a/slice source start end)]
        (when (k/== tag :.eof) (k/return 0))
        (when (k/== tag :.invalid)
          (k/= (:invalid_count facts) 1)
          (k/return 4))
        (cond
          (k/== phase 1)
          (if (k/== tag :.keyword_fn)
            (k/= phase 2)
            (do (k/= (:external_exports facts) 1) (k/= phase 0)))

          (k/== phase 2)
          (let [index (:export_count facts)]
            (when (k/>= index max-exports) (k/return 3))
            (k/= (a/get (:starts facts) index) (k/intCast start))
            (k/= (a/get (:ends facts) index) (k/intCast end))
            (k/+= (:export_count facts) 1)
            (when (or (k/!= tag :.identifier)
                      (k/! (mem/startsWith :u8 text "__aguafria_")))
              (k/= (:external_exports facts) 1))
            (k/= phase 0)))
        (when (k/== tag :.keyword_export) (k/= phase 1))
        (when (k/== tag :.keyword_extern) (k/= (:external_syntax facts) 1))
        (when (k/== tag :.builtin)
          (when (mem/eql :u8 text "@export") (k/= (:dynamic_exports facts) 1))
          (when (or (mem/eql :u8 text "@import") (mem/eql :u8 text "@embedFile"))
            (k/+= (:import_count facts) 1))))
      (k/+= steps 1))
    ;; Every token consumes input, so exhausting this bound indicates invalid input.
    5))

(a/defn collect_batch :u32 {:attrs #{k/export}}
  [[buffer [:many-const :u8]]
   [buffer_len :u32]
   [offsets [:many-const :u32]]
   [lengths [:many-const :u32]]
   [file_count :u32]
   [output [:many Facts]]
   [rounds :u32]]
  (when (or (k/> file_count max-files) (k/> buffer_len max-bytes)
            (k/== rounds 0) (k/> rounds 1000))
    (k/return 1))
  (let [round (k/var 0 :u32)]
    (k/while (k/< round rounds)
      (let [file (k/var 0 :u32)]
        (k/while (k/< file file_count)
          (let [offset (a/get offsets file)
                length (a/get lengths file)
                end (k/+ (k/u64 offset) (k/u64 length))]
            (when (or (k/>= end buffer_len) (k/!= (a/get buffer end) 0))
              (k/return 2))
            (let [status (collect-file (k/+ buffer offset) length
                                       (k/& (a/get output file)))]
              (when (k/!= status 0) (k/return status))))
          (k/+= file 1)))
      (k/+= round 1)))
  0)

(a/defn facts_size :u32 {:attrs #{k/export}} [] (k/sizeOf Facts))
