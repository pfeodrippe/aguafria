(ns la-professeure.recording-tool
  "Native development tooling, authored in Aguafria Zig. The game doesn't call this entry point."
  (:refer-clojure :only [])
  (:require [aguafria.c :as ac]
            [clojure.java.io :as io]
            [aguafria.std]
            [aguafria.std.mem :as mem]
            [aguafria.std.process :as process]
            [aguafria.std.process.Args.Iterator :as args]
            [aguafria.std.debug :as debug]
            [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

;; Bitwig owns .bwproject serialization. This tool owns the Markdown → track plan.
(a/defconst c
  (import (a/clj! (ac/import! "la_professeure_recording_io"
                              (io/file (io/resource "native/recording_tool.h"))
                              {:args ["-lc"]}))))

(a/defconst fopen (:fopen c))

(a/defconst fclose (:fclose c))

(a/defconst fread (:fread c))

(a/defconst fwrite (:fwrite c))

(a/defconst rename-file (:rename c))

(a/defconst FILE (:FILE c))

(a/defstruct Node {:layout :extern}
             [[:kind :u32] [:parent :u32] [:scene :u32] [:line :u32] [:indent :u32]
              [:speaker :u8] [:id_len :u8] [:padding :u16] [:revision :u32]
              [:offset :u32] [:length :u32] [:hash :u64] [:context :u64]
              [:id [:array 64 :u8]]])

(a/defconst no-parent :u32 4294967295)

(a/defconst max-nodes :usize 1024)

(a/defvar nodes [:array 1024 Node] (mem/zeroes [:array 1024 Node]))

(a/defvar text-arena [:array 262144 :u8] undefined)

(a/defvar source-buffer [:array 262144 :u8] undefined)

(a/defvar normalized-buffer [:array 262144 :u8] undefined)

(a/defvar count-nodes :u32 0)

(a/defvar text-used :u32 0)

(a/defvar error-line :u32 0)

(a/defvar error-code :u32 0)

(a/defn- reject :bool [[line :u32] [code :u32]]
  (= error-line line) (= error-code code) false)

(a/defn text-hash :u64 [[text [:slice-const :u8]]]
  (let [h (k/var (u64 14695981039346656037))]
    (dotimes [i (:len text)] (= h (*% (bit-xor h (a/get text i)) 1099511628211)))
    h))

(a/defn node-at Node [[index :u32]]
  (if (< index count-nodes) (a/get nodes index) (mem/zeroes Node)))

(a/defn node-text [:slice-const :u8] [[index :u32]]
  (when (>= index count-nodes) (return ""))
  (let [n (a/get nodes index)]
    (a/slice text-arena (:offset n) (+ (:offset n) (:length n)))))

(a/defn node-id [:slice-const :u8] [[index :u32]]
  (when (>= index count-nodes) (return ""))
  (a/slice (:id (a/get nodes index)) 0 (:id_len (a/get nodes index))))

(a/defn- append-text! :bool [[value [:slice-const :u8]]]
  (when (> (+ text-used (:len value)) (:len text-arena))
    (return (reject error-line 2)))
  (memcpy (a/slice text-arena text-used (+ text-used (:len value))) value)
  (= text-used (intCast (+ text-used (:len value)))) true)

(a/defn- id-character? :bool [[b :u8]]
  (or (and (>= b 65) (<= b 90)) (and (>= b 97) (<= b 122))
      (and (>= b 48) (<= b 57)) (== b 45) (== b 95)))

(a/defn set-recording-id! :bool [[index :u32] [id [:slice-const :u8]] [revision :u32]]
  (when (or (>= index count-nodes) (== (:len id) 0) (> (:len id) 63))
    (return false))
  (dotimes [i (:len id)] (when (! (id-character? (a/get id i))) (return false)))
  (let [node (k/& (a/get nodes index))]
    (memcpy (a/slice (:id node) 0 (:len id)) id)
    (= (:id_len node) (intCast (:len id)))
    (= (:revision node) revision)) true)

(a/defn- parse-normalized! :bool
  "Native Markdown parser. Errors leave diagnostics; caller publishes only after success.
  1=syntax, 2=capacity, 3=unknown voice, 4=indentation, 5=identity conflict."
  [[source [:slice-const :u8]]]
  (= count-nodes 0) (= text-used 0) (= error-line 0) (= error-code 0)
  (when (> (:len source) 262144) (return (reject 1 2)))
  (let [start (k/var (usize 0)) line (k/var (u32 0))
        scene (k/var (u32 no-parent)) join (k/var false)
        stack (k/var (as undefined [:array 64 :u32])) depth (k/var (usize 0))]
    (while (< start (:len source))
      (= line (+ line 1))
      (let [end (k/var (usize start))]
        (while (and (< end (:len source)) (!= (a/get source end) 10))
          (= end (+ end 1)))
        (let [raw (a/slice source start end)
              indent (k/var (usize 0))]
          (= start (+ end 1))
          (while (and (< indent (:len raw)) (== (a/get raw indent) 32))
            (= indent (+ indent 1)))
          (let [body (k/var (mem/trim :u8 (a/slice raw indent) " \r"))]
            (when (== (:len body) 0) (= join false) (continue))
            (when (>= count-nodes max-nodes) (return (reject line 2)))
            (let [n (k/var (as (mem/zeroes Node) Node))]
              (= (:line n) line) (= (:indent n) (intCast indent))
              (= (:kind n) 2)
              ;; IDs are metadata at the end, never visible/spoken text.
              (let [id-start (k/var (usize (:len body)))
                    id-end (k/var (usize (:len body)))
                    text-end (k/var (usize (:len body)))
                    j (k/var (usize 0))]
                (while (< j (:len body))
                  (when (and (> j 0) (== (a/get body (- j 1)) 32))
                    (cond
                      (== (a/get body j) 94)
                      (do (= id-start (+ j 1)) (= text-end (- j 1)))
                      (and (mem/startsWith (a/type :u8) (a/slice body j) "[id:")
                           (== (a/get body (- (:len body) 1)) 93))
                      (do (= id-start (+ j 4)) (= id-end (- (:len body) 1))
                          (= text-end (- j 1)))))
                  (= j (+ j 1)))
                (when (< id-start (:len body))
                  (let [id (a/slice body id-start id-end)]
                    (when (or (== (:len id) 0) (> (:len id) 63))
                      (return (reject line 5)))
                    (dotimes [i (:len id)]
                      (when (! (id-character? (a/get id i))) (return (reject line 5))))
                    (memcpy (a/slice (:id n) 0 (:len id)) id)
                    (= (:id_len n) (intCast (:len id)))
                    (= body (mem/trim :u8 (a/slice body 0 text-end) " ")))))
              (cond
                (mem/startsWith (a/type :u8) body ":: ")
                (do (= (:kind n) 1) (= body (a/slice body 3)))
                (mem/startsWith (a/type :u8) body "::") (return (reject line 1))
                (mem/startsWith (a/type :u8) body "#")
                (let [tag-end (k/var (usize 1))]
                  (while (and (< tag-end (:len body)) (== (a/get body tag-end) 35))
                    (= tag-end (+ tag-end 1)))
                  (if (and (< tag-end (:len body)) (== (a/get body tag-end) 32))
                    (do (= (:kind n) 0) (= body (a/slice body (+ tag-end 1))))
                    (do
                      (= tag-end 1)
                      (when (mem/startsWith (a/type :u8) (a/slice body 1) "∆") (= tag-end 4))
                      (when (mem/startsWith (a/type :u8) (a/slice body 1) "Δ") (= tag-end 3))
                      (when (or (>= (+ tag-end 1) (:len body))
                                (!= (a/get body (+ tag-end 1)) 32)) (return (reject line 1)))
                      (= (:speaker n) (a/get body tag-end))
                      (when (and (!= (:speaker n) 86) (!= (:speaker n) 77))
                        (return (reject line 3)))
                      (= body (a/slice body (+ tag-end 2)))))))
              (= body (mem/trim :u8 body " "))
              (when (== (:len body) 0) (return (reject line 1)))
              (if (== (:kind n) 0)
                (do (= scene count-nodes) (= depth 0) (= (:parent n) no-parent))
                (do
                  (when (== scene no-parent) (return (reject line 1)))
                  (while (and (> depth 0)
                              (>= (:indent (a/get nodes (a/get stack (- depth 1)))) indent))
                    (= depth (- depth 1)))
                  (when (and (> indent 0) (== depth 0)) (return (reject line 4)))
                  (= (:parent n) (if (> depth 0) (a/get stack (- depth 1)) scene))))
              (= (:scene n) scene)
              (when (and join (> count-nodes 0) (== (:kind n) 2)
                         (== (:speaker n) 0) (== (:id_len n) 0))
                (let [p (k/& (a/get nodes (- count-nodes 1)))]
                  (when (and (== (:parent p) (:parent n))
                             (== (:indent p) indent))
                    (when (! (append-text! " ")) (return false))
                    (when (! (append-text! body)) (return false))
                    (= (:length p) (- text-used (:offset p)))
                    (= (:hash p) (text-hash (node-text (- count-nodes 1))))
                    (continue))))
              (= (:offset n) text-used)
              (= (:length n) (intCast (:len body)))
              (= (:hash n) (text-hash body))
              (= (:context n)
                 (if (== (:parent n) no-parent) 0
                     (bit-xor (:hash (a/get nodes (:parent n)))
                              (:context (a/get nodes (:parent n))))))
              (when (! (append-text! body)) (return false))
              (= (a/get nodes count-nodes) n)
              (when (== (:kind n) 1)
                (when (>= depth 64) (return (reject line 2)))
                (= (a/get stack depth) count-nodes) (= depth (+ depth 1)))
              (= count-nodes (+ count-nodes 1))
              (= join (== (:kind n) 2)))))))
    (when (== count-nodes 0) (return (reject 1 1)))
    (dotimes [i count-nodes]
      (when (and (== (:kind (a/get nodes i)) 0)
                 (== (:id_len (a/get nodes i)) 0))
        (dotimes [j i]
          (when (and (== (:kind (a/get nodes j)) 0)
                     (== (:id_len (a/get nodes j)) 0)
                     (mem/eql :u8 (node-text (intCast i)) (node-text (intCast j))))
            (return (reject (:line (a/get nodes i)) 7)))))
      (when (> (:id_len (a/get nodes i)) 0)
        (dotimes [j i]
          (when (mem/eql :u8 (node-id (intCast i)) (node-id (intCast j)))
            (return (reject (:line (a/get nodes i)) 5))))))
    true))

(a/defn parse! :bool
  "Normalize every tab to four spaces before parsing; the source is never rewritten." [[source [:slice-const :u8]]]
  (= count-nodes 0) (= text-used 0) (= error-line 0) (= error-code 0)
  (let [length (k/var (usize 0)) line (k/var (u32 1))]
    (dotimes [i (:len source)]
      (let [b (a/get source i) width (as (if (== b 9) 4 1) :usize)]
        (when (> (+ length width) (:len normalized-buffer)) (return (reject line 2)))
        (dotimes [j width]
          (= (a/get normalized-buffer (+ length j)) (if (== b 9) 32 b)))
        (= length (+ length width))
        (when (== b 10) (= line (+ line 1)))))
    (parse-normalized! (a/slice normalized-buffer 0 length))))

(a/defn parse-file! :bool [[path [:slice-const :u8]]]
  (when (>= (:len path) 4096) (return (reject 0 6)))
  (let [name (k/var (as (mem/zeroes [:array 4096 :u8]) [:array 4096 :u8]))]
    (memcpy (a/slice name 0 (:len path)) path)
    (let [file (fopen (k/& name) "rb")]
      (when (== file null) (return (reject 0 6)))
      (defer (= :_ (fclose file)))
      (let [n (fread (k/& source-buffer) 1 (:len source-buffer) file)]
        (when (or (!= ((:ferror c) file) 0)
                  (!= ((:fgetc c) file) (:EOF c)))
          (return (reject 0 2)))
        (parse! (a/slice source-buffer 0 n))))))

(a/defn write-document! :bool
  "Publish a complete native dialogue asset by rename. No Bitwig project or audio is touched." [[path [:slice-const :u8]]]
  (when (> (:len path) 4090) (return (reject 0 6)))
  (let [name (k/var (as (mem/zeroes [:array 4096 :u8]) [:array 4096 :u8]))
        temporary (k/var (as (mem/zeroes [:array 4096 :u8]) [:array 4096 :u8]))]
    (memcpy (a/slice name 0 (:len path)) path)
    (memcpy (a/slice temporary 0 (:len path)) path)
    (memcpy (a/slice temporary (:len path) (+ (:len path) 4)) ".tmp")
    (let [file (fopen (k/& temporary) "wb")]
      (when (== file null) (return (reject 0 6)))
      (let [ok (and (== (fwrite "LPDIAG01" 1 8 file) 8)
                    (== (fwrite (k/& count-nodes) 4 1 file) 1)
                    (== (fwrite (k/& text-used) 4 1 file) 1)
                    (== (fwrite (k/& nodes) (sizeOf Node) count-nodes file) count-nodes)
                    (== (fwrite (k/& text-arena) 1 text-used file) text-used))
            closed (== (fclose file) 0)]
        (when (or (! ok) (! closed)) (return (reject 0 6)))
        (== (rename-file (k/& temporary) (k/& name)) 0)))))

(a/defn main :!void [[init process/Init]]
  (let [iterator (k/var (k/try (args/initAllocator (:args (:minimal init)) (:gpa init))))]
    (defer (args/deinit (k/& iterator)))
    (= :_ (args/next (k/& iterator)))
    (let [source (args/next (k/& iterator)) output (args/next (k/& iterator))]
      (when (or (== source null) (== output null))
        (debug/print "Usage: dialogue-tool source.md output.lpdialogue\n" [])
        ((:exit c) 2))
      (when (or (! (parse-file! (a/unwrap source)))
                (! (write-document! (a/unwrap output))))
        (debug/print "Dialogue line {d}: error {d}\n" [error-line error-code])
        ((:exit c) 1))
      (debug/print "Compiled {d} dialogue nodes.\n" [count-nodes]))))
