(ns aguafria.native-source-collector
  "Allocation-free batch lexical facts from Zig's tokenizer. Developer prototype."
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
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
    (while (<= steps length)
      (let [token (tokenizer/next (k/& lexer))
            tag (:tag token)
            start (:start (:loc token))
            end (:end (:loc token))
            text (a/slice source start end)]
        (when (== tag :eof) (return 0))
        (when (== tag :invalid)
          (= (:invalid_count facts) 1)
          (return 4))
        (cond
          (== phase 1)
          (if (== tag :keyword_fn)
            (= phase 2)
            (do (= (:external_exports facts) 1) (= phase 0)))

          (== phase 2)
          (let [index (:export_count facts)]
            (when (>= index max-exports) (return 3))
            (= (a/get (:starts facts) index) (intCast start))
            (= (a/get (:ends facts) index) (intCast end))
            (+= (:export_count facts) 1)
            (when (or (!= tag :identifier)
                      (! (mem/startsWith :u8 text "__aguafria_")))
              (= (:external_exports facts) 1))
            (= phase 0)))
        (when (== tag :keyword_export) (= phase 1))
        (when (== tag :keyword_extern) (= (:external_syntax facts) 1))
        (when (== tag :builtin)
          (when (mem/eql :u8 text "@export") (= (:dynamic_exports facts) 1))
          (when (or (mem/eql :u8 text "@import") (mem/eql :u8 text "@embedFile"))
            (+= (:import_count facts) 1))))
      (+= steps 1))
    ;; Every token consumes input, so exhausting this bound indicates invalid input.
    5))

(a/defn collect_batch :u32 {:attrs #{export}}
  [[buffer [:many-const :u8]]
   [buffer_len :u32]
   [offsets [:many-const :u32]]
   [lengths [:many-const :u32]]
   [file_count :u32]
   [output [:many Facts]]
   [rounds :u32]]
  (when (or (> file_count max-files) (> buffer_len max-bytes)
            (== rounds 0) (> rounds 1000))
    (return 1))
  (let [round (k/var 0 :u32)]
    (while (< round rounds)
      (let [file (k/var 0 :u32)]
        (while (< file file_count)
          (let [offset (a/get offsets file)
                length (a/get lengths file)
                end (+ (u64 offset) (u64 length))]
            (when (or (>= end buffer_len) (!= (a/get buffer end) 0))
              (return 2))
            (let [status (collect-file (+ buffer offset) length
                                       (k/& (a/get output file)))]
              (when (!= status 0) (return status))))
          (+= file 1)))
      (+= round 1)))
  0)

(a/defn facts_size :u32 {:attrs #{export}} [] (sizeOf Facts))
