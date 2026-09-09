(ns la-professeure.recording-tool
  "Native development tooling, authored in Aguafria Zig. The game doesn't call this entry point."
  (:require [aguafria.std] [aguafria.std.mem :as mem]
            [aguafria.std.process :as process]
            [aguafria.std.process.Args.Iterator :as args]
            [aguafria.std.debug :as debug]
            [aguafria.keyword :as ak] [aguafria.zig :as az]))

;; Bitwig owns .bwproject serialization. This tool owns the Markdown → track plan.
(az/defconst c (ak/cImport (do (ak/cInclude "stdio.h") (ak/cInclude "stdlib.h"))))
(az/defconst fopen (az/field c fopen))
(az/defconst fclose (az/field c fclose))
(az/defconst fread (az/field c fread))
(az/defconst fwrite (az/field c fwrite))
(az/defconst rename-file (az/field c rename))
(az/defconst FILE (az/field c FILE))

(az/defstruct Node {:layout :extern}
  [[:kind :u32] [:parent :u32] [:scene :u32] [:line :u32] [:indent :u32]
   [:speaker :u8] [:id_len :u8] [:padding :u16] [:revision :u32]
   [:offset :u32] [:length :u32] [:hash :u64] [:context :u64]
   [:id [:array 64 :u8]]])

(az/defconst no-parent :u32 4294967295)
(az/defconst max-nodes :usize 1024)
(az/defvar nodes [:array 1024 Node] (mem/zeroes (az/type [:array 1024 Node])))
(az/defvar text-arena [:array 262144 :u8] ak/undefined)
(az/defvar source-buffer [:array 262144 :u8] ak/undefined)
(az/defvar normalized-buffer [:array 262144 :u8] ak/undefined)
(az/defvar count-nodes :u32 0)
(az/defvar text-used :u32 0)
(az/defvar error-line :u32 0)
(az/defvar error-code :u32 0)

(az/defn- reject :- :bool [[line :u32] [code :u32]]
  (set! error-line line) (set! error-code code) false)

(az/defn text-hash :- :u64 [[text [:slice-const :u8]]]
  (let [^{:var :u64} h 14695981039346656037]
    (dotimes [i (az/field text len)] (set! h (ak/*% (ak/bit-xor h (az/index text i)) 1099511628211)))
    h))

(az/defn node-at :- Node [[index :u32]]
  (if (< index count-nodes) (az/index nodes index) (mem/zeroes (az/type Node))))

(az/defn node-text :- [:slice-const :u8] [[index :u32]]
  (when (>= index count-nodes) (ak/return ""))
  (let [n (az/index nodes index)]
    (az/slice text-arena (az/field n offset) (+ (az/field n offset) (az/field n length)))))

(az/defn node-id :- [:slice-const :u8] [[index :u32]]
  (when (>= index count-nodes) (ak/return ""))
  (az/slice (az/field (az/index nodes index) id) 0 (az/field (az/index nodes index) id_len)))

(az/defn- append-text! :- :bool [[value [:slice-const :u8]]]
  (when (> (+ text-used (az/field value len)) (az/field text-arena len))
    (ak/return (reject error-line 2)))
  (ak/memcpy (az/slice text-arena text-used (+ text-used (az/field value len))) value)
  (set! text-used (ak/intCast (+ text-used (az/field value len)))) true)

(az/defn- id-character? :- :bool [[b :u8]]
  (or (and (>= b 65) (<= b 90)) (and (>= b 97) (<= b 122))
      (and (>= b 48) (<= b 57)) (ak/== b 45) (ak/== b 95)))

(az/defn set-recording-id! :- :bool [[index :u32] [id [:slice-const :u8]] [revision :u32]]
  (when (or (>= index count-nodes) (ak/== (az/field id len) 0) (> (az/field id len) 63))
    (ak/return false))
  (dotimes [i (az/field id len)] (when (ak/! (id-character? (az/index id i))) (ak/return false)))
  (let [node (ak/& (az/index nodes index))]
    (ak/memcpy (az/slice (az/field node id) 0 (az/field id len)) id)
    (set! (az/field node id_len) (ak/intCast (az/field id len)))
    (set! (az/field node revision) revision)) true)

(az/defn- parse-normalized!
  "Native Markdown parser. Errors leave diagnostics; caller publishes only after success.
  1=syntax, 2=capacity, 3=unknown voice, 4=indentation, 5=identity conflict."
  :- :bool [[source [:slice-const :u8]]]
  (set! count-nodes 0) (set! text-used 0) (set! error-line 0) (set! error-code 0)
  (when (> (az/field source len) 262144) (ak/return (reject 1 2)))
  (let [^{:var :usize} start 0 ^{:var :u32} line 0
        ^{:var :u32} scene no-parent ^:var join false
        ^{:var [:array 64 :u32]} stack ak/undefined ^{:var :usize} depth 0]
    (ak/while (< start (az/field source len))
      (set! line (+ line 1))
      (let [^{:var :usize} end start]
        (ak/while (and (< end (az/field source len)) (ak/!= (az/index source end) 10))
          (set! end (+ end 1)))
        (let [raw (az/slice source start end)
              ^{:var :usize} indent 0]
          (set! start (+ end 1))
          (ak/while (and (< indent (az/field raw len)) (ak/== (az/index raw indent) 32))
            (set! indent (+ indent 1)))
          (let [^:var body (mem/trim (az/type :u8) (az/slice raw indent) " \r")]
            (when (ak/== (az/field body len) 0) (set! join false) (ak/continue))
            (when (>= count-nodes max-nodes) (ak/return (reject line 2)))
            (let [^{:var Node} n (mem/zeroes (az/type Node))]
              (set! (az/field n line) line) (set! (az/field n indent) (ak/intCast indent))
              (set! (az/field n kind) 2)
              ;; IDs are metadata at the end, never visible/spoken text.
              (let [^{:var :usize} id-start (az/field body len)
                    ^{:var :usize} id-end (az/field body len)
                    ^{:var :usize} text-end (az/field body len)
                    ^{:var :usize} j 0]
                (ak/while (< j (az/field body len))
                  (when (and (> j 0) (ak/== (az/index body (- j 1)) 32))
                    (cond
                      (ak/== (az/index body j) 94)
                      (do (set! id-start (+ j 1)) (set! text-end (- j 1)))
                      (and (mem/startsWith (az/type :u8) (az/slice body j) "[id:")
                           (ak/== (az/index body (- (az/field body len) 1)) 93))
                      (do (set! id-start (+ j 4)) (set! id-end (- (az/field body len) 1))
                          (set! text-end (- j 1)))))
                  (set! j (+ j 1)))
                (when (< id-start (az/field body len))
                  (let [id (az/slice body id-start id-end)]
                    (when (or (ak/== (az/field id len) 0) (> (az/field id len) 63))
                      (ak/return (reject line 5)))
                    (dotimes [i (az/field id len)]
                      (when (ak/! (id-character? (az/index id i))) (ak/return (reject line 5))))
                    (ak/memcpy (az/slice (az/field n id) 0 (az/field id len)) id)
                    (set! (az/field n id_len) (ak/intCast (az/field id len)))
                    (set! body (mem/trim (az/type :u8) (az/slice body 0 text-end) " ")))))
              (cond
                (mem/startsWith (az/type :u8) body ":: ")
                (do (set! (az/field n kind) 1) (set! body (az/slice body 3)))
                (mem/startsWith (az/type :u8) body "::") (ak/return (reject line 1))
                (mem/startsWith (az/type :u8) body "#")
                (let [^{:var :usize} tag-end 1]
                  (ak/while (and (< tag-end (az/field body len)) (ak/== (az/index body tag-end) 35))
                    (set! tag-end (+ tag-end 1)))
                  (if (and (< tag-end (az/field body len)) (ak/== (az/index body tag-end) 32))
                    (do (set! (az/field n kind) 0) (set! body (az/slice body (+ tag-end 1))))
                    (do
                      (set! tag-end 1)
                      (when (mem/startsWith (az/type :u8) (az/slice body 1) "∆") (set! tag-end 4))
                      (when (mem/startsWith (az/type :u8) (az/slice body 1) "Δ") (set! tag-end 3))
                      (when (or (>= (+ tag-end 1) (az/field body len))
                                (ak/!= (az/index body (+ tag-end 1)) 32)) (ak/return (reject line 1)))
                      (set! (az/field n speaker) (az/index body tag-end))
                      (when (and (ak/!= (az/field n speaker) 86) (ak/!= (az/field n speaker) 77))
                        (ak/return (reject line 3)))
                      (set! body (az/slice body (+ tag-end 2)))))))
              (set! body (mem/trim (az/type :u8) body " "))
              (when (ak/== (az/field body len) 0) (ak/return (reject line 1)))
              (if (ak/== (az/field n kind) 0)
                (do (set! scene count-nodes) (set! depth 0) (set! (az/field n parent) no-parent))
                (do
                  (when (ak/== scene no-parent) (ak/return (reject line 1)))
                  (ak/while (and (> depth 0)
                                (>= (az/field (az/index nodes (az/index stack (- depth 1))) indent) indent))
                    (set! depth (- depth 1)))
                  (when (and (> indent 0) (ak/== depth 0)) (ak/return (reject line 4)))
                  (set! (az/field n parent) (if (> depth 0) (az/index stack (- depth 1)) scene))))
              (set! (az/field n scene) scene)
              (when (and join (> count-nodes 0) (ak/== (az/field n kind) 2)
                         (ak/== (az/field n speaker) 0) (ak/== (az/field n id_len) 0))
                (let [p (ak/& (az/index nodes (- count-nodes 1)))]
                  (when (and (ak/== (az/field p parent) (az/field n parent))
                             (ak/== (az/field p indent) indent))
                    (when (ak/! (append-text! " ")) (ak/return false))
                    (when (ak/! (append-text! body)) (ak/return false))
                    (set! (az/field p length) (- text-used (az/field p offset)))
                    (set! (az/field p hash) (text-hash (node-text (- count-nodes 1))))
                    (ak/continue))))
              (set! (az/field n offset) text-used)
              (set! (az/field n length) (ak/intCast (az/field body len)))
              (set! (az/field n hash) (text-hash body))
              (set! (az/field n context)
                    (if (ak/== (az/field n parent) no-parent) 0
                      (ak/bit-xor (az/field (az/index nodes (az/field n parent)) hash)
                                  (az/field (az/index nodes (az/field n parent)) context))))
              (when (ak/! (append-text! body)) (ak/return false))
              (set! (az/index nodes count-nodes) n)
              (when (ak/== (az/field n kind) 1)
                (when (>= depth 64) (ak/return (reject line 2)))
                (set! (az/index stack depth) count-nodes) (set! depth (+ depth 1)))
              (set! count-nodes (+ count-nodes 1))
              (set! join (ak/== (az/field n kind) 2)))))))
    (when (ak/== count-nodes 0) (ak/return (reject 1 1)))
    (dotimes [i count-nodes]
      (when (and (ak/== (az/field (az/index nodes i) kind) 0)
                 (ak/== (az/field (az/index nodes i) id_len) 0))
        (dotimes [j i]
          (when (and (ak/== (az/field (az/index nodes j) kind) 0)
                     (ak/== (az/field (az/index nodes j) id_len) 0)
                     (mem/eql (az/type :u8) (node-text (ak/intCast i)) (node-text (ak/intCast j))))
            (ak/return (reject (az/field (az/index nodes i) line) 7)))))
      (when (> (az/field (az/index nodes i) id_len) 0)
        (dotimes [j i]
          (when (mem/eql (az/type :u8) (node-id (ak/intCast i)) (node-id (ak/intCast j)))
            (ak/return (reject (az/field (az/index nodes i) line) 5))))))
    true))

(az/defn parse!
  "Normalize every tab to four spaces before parsing; the source is never rewritten."
  :- :bool [[source [:slice-const :u8]]]
  (set! count-nodes 0) (set! text-used 0) (set! error-line 0) (set! error-code 0)
  (let [^{:var :usize} length 0 ^{:var :u32} line 1]
    (dotimes [i (az/field source len)]
      (let [b (az/index source i) width (ak/as :usize (if (ak/== b 9) 4 1))]
        (when (> (+ length width) (az/field normalized-buffer len)) (ak/return (reject line 2)))
        (dotimes [j width]
          (set! (az/index normalized-buffer (+ length j)) (if (ak/== b 9) 32 b)))
        (set! length (+ length width))
        (when (ak/== b 10) (set! line (+ line 1)))))
    (parse-normalized! (az/slice normalized-buffer 0 length))))

(az/defn parse-file! :- :bool [[path [:slice-const :u8]]]
  (when (>= (az/field path len) 4096) (ak/return (reject 0 6)))
  (let [^{:var [:array 4096 :u8]} name (mem/zeroes (az/type [:array 4096 :u8]))]
    (ak/memcpy (az/slice name 0 (az/field path len)) path)
    (let [file (fopen (ak/& name) "rb")]
      (when (ak/== file ak/null) (ak/return (reject 0 6)))
      (ak/defer (set! _ (fclose file)))
      (let [n (fread (ak/& source-buffer) 1 (az/field source-buffer len) file)]
        (when (or (ak/!= ((az/field c ferror) file) 0)
                  (ak/!= ((az/field c fgetc) file) (az/field c EOF)))
          (ak/return (reject 0 2)))
        (parse! (az/slice source-buffer 0 n))))))

(az/defn write-document!
  "Publish a complete native dialogue asset by rename. No Bitwig project or audio is touched."
  :- :bool [[path [:slice-const :u8]]]
  (when (> (az/field path len) 4090) (ak/return (reject 0 6)))
  (let [^{:var [:array 4096 :u8]} name (mem/zeroes (az/type [:array 4096 :u8]))
        ^{:var [:array 4096 :u8]} temporary (mem/zeroes (az/type [:array 4096 :u8]))]
    (ak/memcpy (az/slice name 0 (az/field path len)) path)
    (ak/memcpy (az/slice temporary 0 (az/field path len)) path)
    (ak/memcpy (az/slice temporary (az/field path len) (+ (az/field path len) 4)) ".tmp")
    (let [file (fopen (ak/& temporary) "wb")]
      (when (ak/== file ak/null) (ak/return (reject 0 6)))
      (let [ok (and (ak/== (fwrite "LPDIAG01" 1 8 file) 8)
                    (ak/== (fwrite (ak/& count-nodes) 4 1 file) 1)
                    (ak/== (fwrite (ak/& text-used) 4 1 file) 1)
                    (ak/== (fwrite (ak/& nodes) (ak/sizeOf Node) count-nodes file) count-nodes)
                    (ak/== (fwrite (ak/& text-arena) 1 text-used file) text-used))
            closed (ak/== (fclose file) 0)]
        (when (or (ak/! ok) (ak/! closed)) (ak/return (reject 0 6)))
        (ak/== (rename-file (ak/& temporary) (ak/& name)) 0)))))

(az/defn main {:zig/qualifiers "!"} :- :void [[init process/Init]]
  (let [^:var iterator (ak/try (args/initAllocator (az/field (az/field init minimal) args) (az/field init gpa)))]
    (ak/defer (args/deinit (ak/& iterator)))
    (set! _ (args/next (ak/& iterator)))
    (let [source (args/next (ak/& iterator)) output (args/next (ak/& iterator))]
      (when (or (ak/== source ak/null) (ak/== output ak/null))
        (debug/print "Usage: dialogue-tool source.md output.lpdialogue\n" [])
        ((az/field c exit) 2))
      (when (or (ak/! (parse-file! (az/unwrap source)))
                (ak/! (write-document! (az/unwrap output))))
        (debug/print "Dialogue line {d}: error {d}\n" [error-line error-code])
        ((az/field c exit) 1))
      (debug/print "Compiled {d} dialogue nodes.\n" [count-nodes]))))
