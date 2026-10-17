(ns aguafria.native-tools.source-scanner
  "Bounded lexical facts and rewrite spans from Zig's tokenizer."
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.mem :as mem]
            [aguafria.std.zig.Tokenizer :as tokenizer]
            [aguafria.zig :as a]))

(a/defconst max_files :u32 16)
(a/defconst max_bytes :u32 33554432)
(a/defconst max_exports :u32 64)
(a/defconst max_imports :u32 1024)
(a/defconst max_identifiers :u32 16384)

(a/defstruct Span {:layout :extern} [[:start :u32] [:end :u32]])

(a/defstruct Import {:layout :extern}
             [[:start :u32] [:end :u32] [:kind :u32]])

(a/defstruct Facts {:layout :extern}
             [[:export_count :u32]
              [:external_exports :u32]
              [:dynamic_exports :u32]
              [:external_syntax :u32]
              [:import_count :u32]
              [:identifier_count :u32]
              [:exports [:array 64 Span]]
              [:imports [:array 1024 Import]]
              [:identifiers [:array 16384 Span]]])

(a/defstruct State
  [[:export_phase :u32]
   [:import_phase :u32]
   [:import_kind :u32]
   [:argument_start :u32]
   [:argument_end :u32]])

(a/defn- append_import :u32
  [[facts [:* Facts]] [state [:* State]] [complete :bool]]
  (let [index (:import_count facts)]
    (when (>= index max_imports) (return 6))
    (= (a/get (:imports facts) index)
       (Import {:start (if complete (:argument_start state) 0)
                :end (if complete (:argument_end state) 0)
                :kind (:import_kind state)}))
    (+= (:import_count facts) 1)
    (= (:import_phase state) 0))
  0)

(a/defn- collect_import :u32
  [[text [:slice-const :u8]] [token :anytype] [facts [:* Facts]] [state [:* State]]]
  ;; Phases follow the call grammar: builtin, opening paren, argument, close,
  ;; and optional trailing comma. Incomplete/dynamic arguments retain no span.
  (let [tag (:tag token)
        status (k/var 0 :u32)]
    (cond
      (== (:import_phase state) 1)
      (if (== tag :l_paren)
        (= (:import_phase state) 2)
        (= status (append_import facts state false)))

      (== (:import_phase state) 2)
      (if (== tag :builtin)
        (= status (append_import facts state false))
        (a/merge! state {:argument_start (intCast (:start (:loc token)))
                         :argument_end (intCast (:end (:loc token)))
                         :import_phase 3}))

      (== (:import_phase state) 3)
      (if (== tag :comma)
        (= (:import_phase state) 4)
        (= status (append_import facts state (== tag :r_paren))))

      (== (:import_phase state) 4)
      (= status (append_import facts state (== tag :r_paren))))
    (when (!= status 0) (return status))
    (when (== tag :builtin)
      (when (or (mem/eql :u8 text "@import") (mem/eql :u8 text "@embedFile"))
        (a/merge! state {:import_phase 1
                         :import_kind (if (mem/eql :u8 text "@import") 1 2)}))))
  0)

(a/defn- collect_export :u32
  [[text [:slice-const :u8]] [token :anytype] [facts [:* Facts]] [state [:* State]]]
  (let [tag (:tag token)]
    (cond
      (== (:export_phase state) 1)
      (if (== tag :keyword_fn)
        (= (:export_phase state) 2)
        (do (= (:external_exports facts) 1)
            (= (:export_phase state) 0)))

      (== (:export_phase state) 2)
      (let [index (:export_count facts)]
        (when (>= index max_exports) (return 3))
        (= (a/get (:exports facts) index)
           (Span {:start (intCast (:start (:loc token)))
                  :end (intCast (:end (:loc token)))}))
        (+= (:export_count facts) 1)
        (when (or (!= tag :identifier) (<= (:len text) 11)
                  (! (mem/startsWith :u8 text "__aguafria_")))
          (= (:external_exports facts) 1))
        (= (:export_phase state) 0)))
    (when (== tag :keyword_export) (= (:export_phase state) 1))
    (when (== tag :keyword_extern) (= (:external_syntax facts) 1))
    (when (and (== tag :builtin) (mem/eql :u8 text "@export"))
      (= (:dynamic_exports facts) 1)))
  0)

(a/defn- collect_identifier :u32
  [[text [:slice-const :u8]] [token :anytype] [facts [:* Facts]]]
  (when (and (== (:tag token) :identifier)
             (mem/startsWith :u8 text "__aguafria_"))
    (let [index (:identifier_count facts)]
      (when (>= index max_identifiers) (return 7))
      (= (a/get (:identifiers facts) index)
         (Span {:start (intCast (:start (:loc token)))
                :end (intCast (:end (:loc token)))}))
      (+= (:identifier_count facts) 1)))
  0)

(a/defn- collect_file :u32
  [[buffer [:many-const :u8]] [length :u32] [facts [:* Facts]]]
  (let [source (a/slice-sentinel buffer 0 length 0)
        lexer (k/var (tokenizer/init source))
        state (k/var (State {:export_phase 0
                             :import_phase 0
                             :import_kind 0
                             :argument_start 0
                             :argument_end 0}))
        steps (k/var 0 :u32)]
    (a/merge! facts {:export_count 0
                     :external_exports 0
                     :dynamic_exports 0
                     :external_syntax 0
                     :import_count 0
                     :identifier_count 0})
    (while (<= steps length)
      (let [token (tokenizer/next (k/& lexer))
            tag (:tag token)
            text (a/slice source (:start (:loc token)) (:end (:loc token)))]
        (when (== tag :eof)
          (if (!= (:import_phase state) 0)
            (return (append_import facts (k/& state) false))
            (return 0)))
        (when (== tag :invalid) (return 4))
        (when (and (!= tag :doc_comment) (!= tag :container_doc_comment))
          (let [export_status (collect_export text token facts (k/& state))
                import_status (collect_import text token facts (k/& state))
                identifier_status (collect_identifier text token facts)]
            (when (!= export_status 0) (return export_status))
            (when (!= import_status 0) (return import_status))
            (when (!= identifier_status 0) (return identifier_status)))))
      (+= steps 1)))
  5)

(a/defn collect_batch :u32 {:attrs #{export}}
  [[buffer [:many-const :u8]] [buffer_len :u32]
   [offsets [:many-const :u32]] [lengths [:many-const :u32]]
   [file_count :u32] [output [:many Facts]]]
  (when (or (> file_count max_files) (> buffer_len max_bytes)) (return 1))
  (let [file (k/var 0 :u32)]
    (while (< file file_count)
      (let [offset (a/get offsets file)
            length (a/get lengths file)
            end (+ (u64 offset) (u64 length))]
        (when (or (>= end buffer_len) (!= (a/get buffer end) 0)) (return 2))
        (let [status (collect_file (+ buffer offset) length (k/& (a/get output file)))]
          (when (!= status 0) (return status))))
      (+= file 1)))
  0)

(a/defn facts_size :u32 {:attrs #{export}} [] (sizeOf Facts))
