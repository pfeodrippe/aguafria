(ns aguafria.native-tools.source-scanner
  "Bounded lexical facts and rewrite spans from Zig's tokenizer."
  (:require [aguafria.keyword :as k]
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
    (when (k/>= index max_imports) (k/return 6))
    (k/= (a/get (:imports facts) index)
         (Import {:start (if complete (:argument_start state) 0)
                  :end (if complete (:argument_end state) 0)
                  :kind (:import_kind state)}))
    (k/+= (:import_count facts) 1)
    (k/= (:import_phase state) 0))
  0)

(a/defn- collect_import :u32
  [[text [:slice-const :u8]] [token :anytype] [facts [:* Facts]] [state [:* State]]]
  ;; Phases follow the call grammar: builtin, opening paren, argument, close,
  ;; and optional trailing comma. Incomplete/dynamic arguments retain no span.
  (let [tag (:tag token)
        status (k/var 0 :u32)]
    (cond
      (k/== (:import_phase state) 1)
      (if (k/== tag :.l_paren)
        (k/= (:import_phase state) 2)
        (k/= status (append_import facts state false)))

      (k/== (:import_phase state) 2)
      (if (k/== tag :.builtin)
        (k/= status (append_import facts state false))
        (a/merge! state {:argument_start (k/intCast (:start (:loc token)))
                         :argument_end (k/intCast (:end (:loc token)))
                         :import_phase 3}))

      (k/== (:import_phase state) 3)
      (if (k/== tag :.comma)
        (k/= (:import_phase state) 4)
        (k/= status (append_import facts state (k/== tag :.r_paren))))

      (k/== (:import_phase state) 4)
      (k/= status (append_import facts state (k/== tag :.r_paren))))
    (when (k/!= status 0) (k/return status))
    (when (k/== tag :.builtin)
      (when (or (mem/eql :u8 text "@import") (mem/eql :u8 text "@embedFile"))
        (a/merge! state {:import_phase 1
                         :import_kind (if (mem/eql :u8 text "@import") 1 2)}))))
  0)

(a/defn- collect_export :u32
  [[text [:slice-const :u8]] [token :anytype] [facts [:* Facts]] [state [:* State]]]
  (let [tag (:tag token)]
    (cond
      (k/== (:export_phase state) 1)
      (if (k/== tag :.keyword_fn)
        (k/= (:export_phase state) 2)
        (do (k/= (:external_exports facts) 1)
            (k/= (:export_phase state) 0)))

      (k/== (:export_phase state) 2)
      (let [index (:export_count facts)]
        (when (k/>= index max_exports) (k/return 3))
        (k/= (a/get (:exports facts) index)
             (Span {:start (k/intCast (:start (:loc token)))
                    :end (k/intCast (:end (:loc token)))}))
        (k/+= (:export_count facts) 1)
        (when (or (k/!= tag :.identifier) (k/<= (:len text) 11)
                  (k/! (mem/startsWith :u8 text "__aguafria_")))
          (k/= (:external_exports facts) 1))
        (k/= (:export_phase state) 0)))
    (when (k/== tag :.keyword_export) (k/= (:export_phase state) 1))
    (when (k/== tag :.keyword_extern) (k/= (:external_syntax facts) 1))
    (when (and (k/== tag :.builtin) (mem/eql :u8 text "@export"))
      (k/= (:dynamic_exports facts) 1)))
  0)

(a/defn- collect_identifier :u32
  [[text [:slice-const :u8]] [token :anytype] [facts [:* Facts]]]
  (when (and (k/== (:tag token) :.identifier)
             (mem/startsWith :u8 text "__aguafria_"))
    (let [index (:identifier_count facts)]
      (when (k/>= index max_identifiers) (k/return 7))
      (k/= (a/get (:identifiers facts) index)
           (Span {:start (k/intCast (:start (:loc token)))
                  :end (k/intCast (:end (:loc token)))}))
      (k/+= (:identifier_count facts) 1)))
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
    (k/while (k/<= steps length)
      (let [token (tokenizer/next (k/& lexer))
            tag (:tag token)
            text (a/slice source (:start (:loc token)) (:end (:loc token)))]
        (when (k/== tag :.eof)
          (if (k/!= (:import_phase state) 0)
            (k/return (append_import facts (k/& state) false))
            (k/return 0)))
        (when (k/== tag :.invalid) (k/return 4))
        (when (and (k/!= tag :.doc_comment) (k/!= tag :.container_doc_comment))
          (let [export_status (collect_export text token facts (k/& state))
                import_status (collect_import text token facts (k/& state))
                identifier_status (collect_identifier text token facts)]
            (when (k/!= export_status 0) (k/return export_status))
            (when (k/!= import_status 0) (k/return import_status))
            (when (k/!= identifier_status 0) (k/return identifier_status)))))
      (k/+= steps 1)))
  5)

(a/defn collect_batch :u32 {:attrs #{k/export}}
  [[buffer [:many-const :u8]] [buffer_len :u32]
   [offsets [:many-const :u32]] [lengths [:many-const :u32]]
   [file_count :u32] [output [:many Facts]]]
  (when (or (k/> file_count max_files) (k/> buffer_len max_bytes)) (k/return 1))
  (let [file (k/var 0 :u32)]
    (k/while (k/< file file_count)
      (let [offset (a/get offsets file)
            length (a/get lengths file)
            end (k/+ (k/u64 offset) (k/u64 length))]
        (when (or (k/>= end buffer_len) (k/!= (a/get buffer end) 0)) (k/return 2))
        (let [status (collect_file (k/+ buffer offset) length (k/& (a/get output file)))]
          (when (k/!= status 0) (k/return status))))
      (k/+= file 1)))
  0)

(a/defn facts_size :u32 {:attrs #{k/export}} [] (k/sizeOf Facts))
