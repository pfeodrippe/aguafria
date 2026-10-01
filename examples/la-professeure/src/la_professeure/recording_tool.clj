(ns la-professeure.recording-tool
  "Native development tooling, authored in Aguafria Zig. The game doesn't call this entry point."
  (:require [aguafria.std] [aguafria.std.mem :as mem]
            [aguafria.std.process :as process]
            [aguafria.std.process.Args.Iterator :as args]
            [aguafria.std.debug :as debug]
            [aguafria.keyword :as k] [aguafria.zig :as az]))

;; Bitwig owns .bwproject serialization. This tool owns the Markdown → track plan.
(az/defconst c (k/cImport (do (k/cInclude "stdio.h") (k/cInclude "stdlib.h"))))

(az/defconst fopen (:fopen c))

(az/defconst fclose (:fclose c))

(az/defconst fread (:fread c))

(az/defconst fwrite (:fwrite c))

(az/defconst rename-file (:rename c))

(az/defconst FILE (:FILE c))

(az/defstruct Node {:layout :extern}
              [[:kind :u32] [:parent :u32] [:scene :u32] [:line :u32] [:indent :u32]
               [:speaker :u8] [:id_len :u8] [:padding :u16] [:revision :u32]
               [:offset :u32] [:length :u32] [:hash :u64] [:context :u64]
               [:id [:array 64 :u8]]])

(az/defconst no-parent :u32 4294967295)

(az/defconst max-nodes :usize 1024)

(az/defvar nodes [:array 1024 Node] (mem/zeroes [:array 1024 Node]))

(az/defvar text-arena [:array 262144 :u8] k/undefined)

(az/defvar source-buffer [:array 262144 :u8] k/undefined)

(az/defvar normalized-buffer [:array 262144 :u8] k/undefined)

(az/defvar count-nodes :u32 0)

(az/defvar text-used :u32 0)

(az/defvar error-line :u32 0)

(az/defvar error-code :u32 0)

(az/defn- reject :bool [[line :u32] [code :u32]]
  (k/= error-line line) (k/= error-code code) false)

(az/defn text-hash :u64 [[text [:slice-const :u8]]]
  (let [h (k/var (k/u64 14695981039346656037))]
    (dotimes [i (:len text)] (k/= h (k/*% (k/bit-xor h (az/get text i)) 1099511628211)))
    h))

(az/defn node-at Node [[index :u32]]
  (if (k/< index count-nodes) (az/get nodes index) (mem/zeroes Node)))

(az/defn node-text [:slice-const :u8] [[index :u32]]
  (when (k/>= index count-nodes) (k/return ""))
  (let [n (az/get nodes index)]
    (az/slice text-arena (:offset n) (k/+ (:offset n) (:length n)))))

(az/defn node-id [:slice-const :u8] [[index :u32]]
  (when (k/>= index count-nodes) (k/return ""))
  (az/slice (:id (az/get nodes index)) 0 (:id_len (az/get nodes index))))

(az/defn- append-text! :bool [[value [:slice-const :u8]]]
  (when (k/> (k/+ text-used (:len value)) (:len text-arena))
    (k/return (reject error-line 2)))
  (k/memcpy (az/slice text-arena text-used (k/+ text-used (:len value))) value)
  (k/= text-used (k/intCast (k/+ text-used (:len value)))) true)

(az/defn- id-character? :bool [[b :u8]]
  (or (and (k/>= b 65) (k/<= b 90)) (and (k/>= b 97) (k/<= b 122))
      (and (k/>= b 48) (k/<= b 57)) (k/== b 45) (k/== b 95)))

(az/defn set-recording-id! :bool [[index :u32] [id [:slice-const :u8]] [revision :u32]]
  (when (or (k/>= index count-nodes) (k/== (:len id) 0) (k/> (:len id) 63))
    (k/return false))
  (dotimes [i (:len id)] (when (k/! (id-character? (az/get id i))) (k/return false)))
  (let [node (k/& (az/get nodes index))]
    (k/memcpy (az/slice (:id node) 0 (:len id)) id)
    (k/= (:id_len node) (k/intCast (:len id)))
    (k/= (:revision node) revision)) true)

(az/defn- parse-normalized! :bool
  "Native Markdown parser. Errors leave diagnostics; caller publishes only after success.
  1=syntax, 2=capacity, 3=unknown voice, 4=indentation, 5=identity conflict." [[source [:slice-const :u8]]]
  (k/= count-nodes 0) (k/= text-used 0) (k/= error-line 0) (k/= error-code 0)
  (when (k/> (:len source) 262144) (k/return (reject 1 2)))
  (let [start (k/var (k/usize 0)) line (k/var (k/u32 0))
        scene (k/var (k/u32 no-parent)) join (k/var false)
        stack (k/var (k/as k/undefined [:array 64 :u32])) depth (k/var (k/usize 0))]
    (k/while (k/< start (:len source))
      (k/= line (k/+ line 1))
      (let [end (k/var (k/usize start))]
        (k/while (and (k/< end (:len source)) (k/!= (az/get source end) 10))
          (k/= end (k/+ end 1)))
        (let [raw (az/slice source start end)
              indent (k/var (k/usize 0))]
          (k/= start (k/+ end 1))
          (k/while (and (k/< indent (:len raw)) (k/== (az/get raw indent) 32))
            (k/= indent (k/+ indent 1)))
          (let [body (k/var (mem/trim :u8 (az/slice raw indent) " \r"))]
            (when (k/== (:len body) 0) (k/= join false) (k/continue))
            (when (k/>= count-nodes max-nodes) (k/return (reject line 2)))
            (let [n (k/var (k/as (mem/zeroes Node) Node))]
              (k/= (:line n) line) (k/= (:indent n) (k/intCast indent))
              (k/= (:kind n) 2)
              ;; IDs are metadata at the end, never visible/spoken text.
              (let [id-start (k/var (k/usize (:len body)))
                    id-end (k/var (k/usize (:len body)))
                    text-end (k/var (k/usize (:len body)))
                    j (k/var (k/usize 0))]
                (k/while (k/< j (:len body))
                  (when (and (k/> j 0) (k/== (az/get body (k/- j 1)) 32))
                    (cond
                      (k/== (az/get body j) 94)
                      (do (k/= id-start (k/+ j 1)) (k/= text-end (k/- j 1)))
                      (and (mem/startsWith (az/type :u8) (az/slice body j) "[id:")
                           (k/== (az/get body (k/- (:len body) 1)) 93))
                      (do (k/= id-start (k/+ j 4)) (k/= id-end (k/- (:len body) 1))
                          (k/= text-end (k/- j 1)))))
                  (k/= j (k/+ j 1)))
                (when (k/< id-start (:len body))
                  (let [id (az/slice body id-start id-end)]
                    (when (or (k/== (:len id) 0) (k/> (:len id) 63))
                      (k/return (reject line 5)))
                    (dotimes [i (:len id)]
                      (when (k/! (id-character? (az/get id i))) (k/return (reject line 5))))
                    (k/memcpy (az/slice (:id n) 0 (:len id)) id)
                    (k/= (:id_len n) (k/intCast (:len id)))
                    (k/= body (mem/trim :u8 (az/slice body 0 text-end) " ")))))
              (cond
                (mem/startsWith (az/type :u8) body ":: ")
                (do (k/= (:kind n) 1) (k/= body (az/slice body 3)))
                (mem/startsWith (az/type :u8) body "::") (k/return (reject line 1))
                (mem/startsWith (az/type :u8) body "#")
                (let [tag-end (k/var (k/usize 1))]
                  (k/while (and (k/< tag-end (:len body)) (k/== (az/get body tag-end) 35))
                    (k/= tag-end (k/+ tag-end 1)))
                  (if (and (k/< tag-end (:len body)) (k/== (az/get body tag-end) 32))
                    (do (k/= (:kind n) 0) (k/= body (az/slice body (k/+ tag-end 1))))
                    (do
                      (k/= tag-end 1)
                      (when (mem/startsWith (az/type :u8) (az/slice body 1) "∆") (k/= tag-end 4))
                      (when (mem/startsWith (az/type :u8) (az/slice body 1) "Δ") (k/= tag-end 3))
                      (when (or (k/>= (k/+ tag-end 1) (:len body))
                                (k/!= (az/get body (k/+ tag-end 1)) 32)) (k/return (reject line 1)))
                      (k/= (:speaker n) (az/get body tag-end))
                      (when (and (k/!= (:speaker n) 86) (k/!= (:speaker n) 77))
                        (k/return (reject line 3)))
                      (k/= body (az/slice body (k/+ tag-end 2)))))))
              (k/= body (mem/trim :u8 body " "))
              (when (k/== (:len body) 0) (k/return (reject line 1)))
              (if (k/== (:kind n) 0)
                (do (k/= scene count-nodes) (k/= depth 0) (k/= (:parent n) no-parent))
                (do
                  (when (k/== scene no-parent) (k/return (reject line 1)))
                  (k/while (and (k/> depth 0)
                                (k/>= (:indent (az/get nodes (az/get stack (k/- depth 1)))) indent))
                    (k/= depth (k/- depth 1)))
                  (when (and (k/> indent 0) (k/== depth 0)) (k/return (reject line 4)))
                  (k/= (:parent n) (if (k/> depth 0) (az/get stack (k/- depth 1)) scene))))
              (k/= (:scene n) scene)
              (when (and join (k/> count-nodes 0) (k/== (:kind n) 2)
                         (k/== (:speaker n) 0) (k/== (:id_len n) 0))
                (let [p (k/& (az/get nodes (k/- count-nodes 1)))]
                  (when (and (k/== (:parent p) (:parent n))
                             (k/== (:indent p) indent))
                    (when (k/! (append-text! " ")) (k/return false))
                    (when (k/! (append-text! body)) (k/return false))
                    (k/= (:length p) (k/- text-used (:offset p)))
                    (k/= (:hash p) (text-hash (node-text (k/- count-nodes 1))))
                    (k/continue))))
              (k/= (:offset n) text-used)
              (k/= (:length n) (k/intCast (:len body)))
              (k/= (:hash n) (text-hash body))
              (k/= (:context n)
                   (if (k/== (:parent n) no-parent) 0
                       (k/bit-xor (:hash (az/get nodes (:parent n)))
                                  (:context (az/get nodes (:parent n))))))
              (when (k/! (append-text! body)) (k/return false))
              (k/= (az/get nodes count-nodes) n)
              (when (k/== (:kind n) 1)
                (when (k/>= depth 64) (k/return (reject line 2)))
                (k/= (az/get stack depth) count-nodes) (k/= depth (k/+ depth 1)))
              (k/= count-nodes (k/+ count-nodes 1))
              (k/= join (k/== (:kind n) 2)))))))
    (when (k/== count-nodes 0) (k/return (reject 1 1)))
    (dotimes [i count-nodes]
      (when (and (k/== (:kind (az/get nodes i)) 0)
                 (k/== (:id_len (az/get nodes i)) 0))
        (dotimes [j i]
          (when (and (k/== (:kind (az/get nodes j)) 0)
                     (k/== (:id_len (az/get nodes j)) 0)
                     (mem/eql :u8 (node-text (k/intCast i)) (node-text (k/intCast j))))
            (k/return (reject (:line (az/get nodes i)) 7)))))
      (when (k/> (:id_len (az/get nodes i)) 0)
        (dotimes [j i]
          (when (mem/eql :u8 (node-id (k/intCast i)) (node-id (k/intCast j)))
            (k/return (reject (:line (az/get nodes i)) 5))))))
    true))

(az/defn parse! :bool
  "Normalize every tab to four spaces before parsing; the source is never rewritten." [[source [:slice-const :u8]]]
  (k/= count-nodes 0) (k/= text-used 0) (k/= error-line 0) (k/= error-code 0)
  (let [length (k/var (k/usize 0)) line (k/var (k/u32 1))]
    (dotimes [i (:len source)]
      (let [b (az/get source i) width (k/as (if (k/== b 9) 4 1) :usize)]
        (when (k/> (k/+ length width) (:len normalized-buffer)) (k/return (reject line 2)))
        (dotimes [j width]
          (k/= (az/get normalized-buffer (k/+ length j)) (if (k/== b 9) 32 b)))
        (k/= length (k/+ length width))
        (when (k/== b 10) (k/= line (k/+ line 1)))))
    (parse-normalized! (az/slice normalized-buffer 0 length))))

(az/defn parse-file! :bool [[path [:slice-const :u8]]]
  (when (k/>= (:len path) 4096) (k/return (reject 0 6)))
  (let [name (k/var (k/as (mem/zeroes [:array 4096 :u8]) [:array 4096 :u8]))]
    (k/memcpy (az/slice name 0 (:len path)) path)
    (let [file (fopen (k/& name) "rb")]
      (when (k/== file k/null) (k/return (reject 0 6)))
      (k/defer (k/= :_ (fclose file)))
      (let [n (fread (k/& source-buffer) 1 (:len source-buffer) file)]
        (when (or (k/!= ((:ferror c) file) 0)
                  (k/!= ((:fgetc c) file) (:EOF c)))
          (k/return (reject 0 2)))
        (parse! (az/slice source-buffer 0 n))))))

(az/defn write-document! :bool
  "Publish a complete native dialogue asset by rename. No Bitwig project or audio is touched." [[path [:slice-const :u8]]]
  (when (k/> (:len path) 4090) (k/return (reject 0 6)))
  (let [name (k/var (k/as (mem/zeroes [:array 4096 :u8]) [:array 4096 :u8]))
        temporary (k/var (k/as (mem/zeroes [:array 4096 :u8]) [:array 4096 :u8]))]
    (k/memcpy (az/slice name 0 (:len path)) path)
    (k/memcpy (az/slice temporary 0 (:len path)) path)
    (k/memcpy (az/slice temporary (:len path) (k/+ (:len path) 4)) ".tmp")
    (let [file (fopen (k/& temporary) "wb")]
      (when (k/== file k/null) (k/return (reject 0 6)))
      (let [ok (and (k/== (fwrite "LPDIAG01" 1 8 file) 8)
                    (k/== (fwrite (k/& count-nodes) 4 1 file) 1)
                    (k/== (fwrite (k/& text-used) 4 1 file) 1)
                    (k/== (fwrite (k/& nodes) (k/sizeOf Node) count-nodes file) count-nodes)
                    (k/== (fwrite (k/& text-arena) 1 text-used file) text-used))
            closed (k/== (fclose file) 0)]
        (when (or (k/! ok) (k/! closed)) (k/return (reject 0 6)))
        (k/== (rename-file (k/& temporary) (k/& name)) 0)))))

(az/defn main :!void [[init process/Init]]
  (let [iterator (k/var (k/try (args/initAllocator (:args (:minimal init)) (:gpa init))))]
    (k/defer (args/deinit (k/& iterator)))
    (k/= :_ (args/next (k/& iterator)))
    (let [source (args/next (k/& iterator)) output (args/next (k/& iterator))]
      (when (or (k/== source k/null) (k/== output k/null))
        (debug/print "Usage: dialogue-tool source.md output.lpdialogue\n" [])
        ((:exit c) 2))
      (when (or (k/! (parse-file! (az/unwrap source)))
                (k/! (write-document! (az/unwrap output))))
        (debug/print "Dialogue line {d}: error {d}\n" [error-line error-code])
        ((:exit c) 1))
      (debug/print "Compiled {d} dialogue nodes.\n" [count-nodes]))))
