(ns field-lab.host
  "Bounded native plugin registry and command transport. No JVM dependency."
  (:require [aguafria.c :as ac]
            [aguafria.zig :as az]
            [aguafria.keyword :as ak]
            [aguafria.std]
            [aguafria.std.mem :as mem]
            [aguafria.std.fmt :as fmt]
            [clojure.java.io :as io]))

(let [output (io/file "generated/field_lab/host_api.clj")]
  (ac/translate-header! (io/file "native/extension_host.h") output
                        {:namespace 'field-lab.host-api :overwrite? true})
  (ac/load-bindings! output))

(require '[field-lab.host-api :as api])

(az/defextern dlopen {:zig/prefix "pub extern"}
  :- [:optional [:* :anyopaque]] [[path [:pointer {:size :c :const? true} :u8]] [flags :c_int]])

(az/defextern dlsym {:zig/prefix "pub extern"}
  :- [:optional [:* :anyopaque]] [[handle [:optional [:* :anyopaque]]] [name [:pointer {:size :c :const? true} :u8]]])

(az/defextern dlclose {:zig/prefix "pub extern"}
  :- :c_int [[handle [:optional [:* :anyopaque]]]])

(az/defextern getenv {:zig/prefix "pub extern"}
  :- [:pointer {:size :c :const? true} :u8] [[name [:pointer {:size :c :const? true} :u8]]])

(az/defextern open {:zig/prefix "pub extern"}
  :- :c_int [[path [:pointer {:size :c :const? true} :u8]] [flags :c_int] [... {:zig/variadic true} _]])

(az/defextern close {:zig/prefix "pub extern"}
  :- :c_int [[descriptor :c_int]])

(az/defextern read {:zig/prefix "pub extern"}
  :- :isize [[descriptor :c_int] [buffer [:* :anyopaque]] [count :usize]])

(az/defextern write {:zig/prefix "pub extern"}
  :- :isize [[descriptor :c_int] [buffer [:*const :anyopaque]] [count :usize]])

(az/defextern rename {:zig/prefix "pub extern"}
  :- :c_int [[before [:pointer {:size :c :const? true} :u8]] [after [:pointer {:size :c :const? true} :u8]]])

(az/defextern unlink {:zig/prefix "pub extern"}
  :- :c_int [[path [:pointer {:size :c :const? true} :u8]]])

(az/defextern access {:zig/prefix "pub extern"}
  :- :c_int [[path [:pointer {:size :c :const? true} :u8]] [mode :c_int]])

(az/defextern opendir {:zig/prefix "pub extern"}
  :- [:optional [:* :anyopaque]] [[path [:pointer {:size :c :const? true} :u8]]])

(az/defextern closedir {:zig/prefix "pub extern"}
  :- :c_int [[directory [:optional [:* :anyopaque]]]])

(az/defextern flock {:zig/prefix "pub extern"}
  :- :c_int [[descriptor :c_int] [operation :c_int]])

(az/defextern sched_yield {:zig/prefix "pub extern"} :- :c_int [])

(az/defextern __error {:zig/prefix "pub extern"} :- [:* :c_int] [])

(az/defstruct Pending
  [[:operation :u32] [:integer :i64] [:ticket :u64] [:text [:array 4097 :u8]]])

(az/defstruct Plugin
  [[:library [:optional [:* :anyopaque]]] [:api [:c-pointer api/PitocoPluginV1]]
   [:state [:optional [:* :anyopaque]]] [:id [:array 65 :u8]]])

(az/defstruct Completion [[:ticket :u64] [:result :u32]])

(az/defvar mailbox :u8 0)

(az/defvar pending Pending (mem/zeroes (az/type Pending)))

(az/defvar occupied :bool false)

(az/defvar executing-ticket :u64 0)

(az/defvar next-ticket :u64 1)

(az/defvar completions [:array 64 Completion] (mem/zeroes (az/type [:array 64 Completion])))

(az/defvar snapshot api/PitocoStatusV1
  (api/PitocoStatusV1 {:abi_version 1 :struct_size (ak/sizeOf api/PitocoStatusV1)}))

(az/defvar plugins [:array 16 Plugin] (mem/zeroes (az/type [:array 16 Plugin])))

(az/defvar bridge-directory [:array 4097 :u8] (mem/zeroes (az/type [:array 4097 :u8])))

(az/defvar bridge-response [:array 512 :u8] (mem/zeroes (az/type [:array 512 :u8])))

(az/defvar bridge-response-size :usize 0)

(az/defvar bridge-lock :c_int -1)

(az/defvar checked-environment :bool false)

(az/defn lock!
  :- :void []
  ;; Critical sections only copy bounded data. Plugin callbacks and filesystem
  ;; operations are always outside this lock, including callback submissions.
  (while (ak/!= (ak/cmpxchgStrong :u8 (ak/& mailbox) 0 1 :.acquire :.monotonic) null)
    (set! _ (sched_yield))))

(az/defn unlock!
  :- :void []
  (ak/atomicStore :u8 (ak/& mailbox) 0 :.release))

(az/defn bounded-length
  :- :usize [[text [:pointer {:size :c :const? true} :u8]] [limit :usize]]
  (when (ak/== text null) (ak/return 0))
  (let [^{:var :usize} length 0]
    (while (and (< length limit) (ak/!= (az/index text length) 0)) (set! length (+ length 1)))
    length))

(az/defn copy-string!
  :- :void [[destination [:slice :u8]] [source [:pointer {:size :c :const? true} :u8]] [length :usize]]
  (dotimes [index length] (set! (az/index destination index) (az/index source index)))
  (set! (az/index destination length) 0))

(az/defn valid-id?
  :- :bool [[id [:pointer {:size :c :const? true} :u8]]]
  (let [length (bounded-length id 65)]
    (when (or (ak/== length 0) (> length 64)) (ak/return false))
    (dotimes [index length]
      (let [c (az/index id index)]
        (when (ak/! (or (and (>= c 97) (<= c 122)) (and (>= c 48) (<= c 57))
                        (ak/== c 45) (ak/== c 95) (ak/== c 46))) (ak/return false))))
    true))

(az/defn find-plugin
  :- [:optional [:* Plugin]] [[id [:slice-const :u8]]]
  (dotimes [index 16]
    (let [slot (ak/& (az/index plugins index))
          length (bounded-length (ak/& (az/index (az/field slot id) 0)) 65)]
      (when (and (ak/!= (az/field slot library) null)
                 (mem/eql :u8 id (az/slice (az/field slot id) 0 length)))
        (ak/return slot))))
  null)

(az/defn pitoco_submit_v1
  {:attrs #{:export}}
  :- :u32 [[command [:pointer {:size :c :const? true} api/PitocoCommandV1]] [ticket [:c-pointer :u64]]]
  (when (or (ak/== command null) (ak/== ticket null)) (ak/return 3))
  (set! (az/deref ticket) 0)
  (when (or (ak/!= (az/field (az/index command 0) abi_version) 1) (< (az/field (az/index command 0) struct_size) (ak/sizeOf api/PitocoCommandV1)))
    (ak/return 4))
  (when (or (ak/!= (az/field (az/index command 0) reserved) 0) (< (az/field (az/index command 0) operation) 1) (> (az/field (az/index command 0) operation) 9))
    (ak/return 3))
  (let [length (bounded-length (az/field (az/index command 0) text) 4097)]
    (when (> length 4096) (ak/return 3))
    (lock!)
    (defer (unlock!))
    (when (or occupied (ak/== next-ticket 0)) (ak/return 2))
    (az/set-many! (az/field pending operation) (az/field (az/index command 0) operation)
                  (az/field pending integer) (az/field (az/index command 0) integer)
                  (az/field pending ticket) next-ticket
                  next-ticket (ak/+% next-ticket 1))
    (copy-string! (ak/& (az/field pending text)) (az/field (az/index command 0) text) length)
    (az/set-many! (az/deref ticket) (az/field pending ticket)
                  occupied true)
    1))

(az/defn pitoco_status_v1
  {:attrs #{:export}}
  :- :u32 [[status [:c-pointer api/PitocoStatusV1]]]
  (when (ak/== status null) (ak/return 3))
  (when (or (ak/!= (az/field (az/index status 0) abi_version) 1) (< (az/field (az/index status 0) struct_size) (ak/sizeOf api/PitocoStatusV1)))
    (ak/return 4))
  (lock!)
  (defer (unlock!))
  (set! (az/index status 0) snapshot)
  0)

(az/defn pitoco_result_v1
  {:attrs #{:export}}
  :- :u32 [[ticket :u64]]
  (lock!)
  (defer (unlock!))
  (when (ak/== ticket 0) (ak/return 3))
  (let [completion (az/index completions (ak/mod ticket 64))]
    (when (ak/== (az/field completion ticket) ticket) (ak/return (az/field completion result))))
  (if (or (ak/== executing-ticket ticket) (and occupied (ak/== (az/field pending ticket) ticket))) 1 5))

(az/defconst host-api api/PitocoHostV1
  (api/PitocoHostV1 {:abi_version 1 :struct_size (ak/sizeOf api/PitocoHostV1)
                     :submit (ak/& pitoco_submit_v1) :status (ak/& pitoco_status_v1)}))

(az/defn load-plugin!
  :- :u32 [[path [:pointer {:size :c :const? true} :u8]]]
  (when (or (ak/== path null) (ak/!= (az/index path 0) 47)) (ak/return 3))
  (let [^{:var [:optional [:* Plugin]]} available null]
    (dotimes [index 16]
      (when (ak/== (az/field (az/index plugins index) library) null)
        (set! available (ak/& (az/index plugins index)))
        (ak/break)))
    (when (ak/== available null) (ak/return 2))
    ;; macOS RTLD_NOW | RTLD_LOCAL. The ABI is synchronous on the owning thread.
    (let [library (dlopen path 6)
          ^:var retained false]
      (when (ak/== library null) (ak/return 5))
      (defer (when (ak/! retained) (set! _ (dlclose library))))
      (let [symbol (dlsym library "pitoco_plugin_v1")]
        (when (ak/== symbol null) (ak/return 4))
        (let [entry (ak/as api/PitocoPluginEntryV1 (ak/ptrCast (ak/alignCast (az/unwrap symbol))))
              plugin ((az/unwrap entry))]
          (when (or (ak/== plugin null) (ak/!= (az/field (az/index plugin 0) abi_version) 1)
                     (< (az/field (az/index plugin 0) struct_size) (ak/sizeOf api/PitocoPluginV1))) (ak/return 4))
          (when (or (ak/! (valid-id? (az/field (az/index plugin 0) id)))
                     (ak/== (az/field (az/index plugin 0) on_load) null) (ak/== (az/field (az/index plugin 0) on_unload) null)
                     (ak/== (az/field (az/index plugin 0) on_command) null)) (ak/return 3))
          (let [length (bounded-length (az/field (az/index plugin 0) id) 65)]
            (when (ak/!= (find-plugin (az/slice (az/field (az/index plugin 0) id) 0 length)) null) (ak/return 2))
            (let [^{:var [:optional [:* :anyopaque]]} state null
                  result ((az/unwrap (az/field (az/index plugin 0) on_load)) (ak/& host-api) (ak/& state))]
              (when (ak/!= result 0)
                ((az/unwrap (az/field (az/index plugin 0) on_unload)) state)
                (ak/return 6))
              (let [slot (az/unwrap available)]
                (az/set-many! (az/field slot library) library
                              (az/field slot api) (ak/constCast plugin)
                              (az/field slot state) state)
                (copy-string! (ak/& (az/field slot id)) (az/field (az/index plugin 0) id) length)
                (set! retained true)
                0))))))))

(az/defn unload-plugin!
  :- :u32 [[id [:slice-const :u8]]]
  (let [slot (find-plugin id)]
    (when (ak/== slot null) (ak/return 5))
    (let [plugin (az/unwrap slot)]
      ((az/unwrap (az/field (az/index (az/field plugin api) 0) on_unload)) (az/field plugin state))
      (set! _ (dlclose (az/field plugin library)))
      (set! (az/deref plugin) (mem/zeroes (az/type Plugin))))
    0))

(az/defn pitoco_bridge_open_v1
  {:attrs #{:export}}
  :- :u32 [[directory [:pointer {:size :c :const? true} :u8]]]
  (set! checked-environment true)
  (let [length (bounded-length directory 4097)]
    (when (or (ak/== length 0) (> length 4096) (ak/!= (az/index directory 0) 47)) (ak/return 3))
    (let [dir (opendir directory)]
      (when (ak/== dir null) (ak/return 7))
      (set! _ (closedir dir)))
    (let [^{:var [:array 4120 :u8]} buffer ak/undefined
          path (catch (fmt/bufPrintZ (ak/& buffer) "{s}/.host.lock" [(az/slice directory 0 length)]) (ak/return 7))
          descriptor (open (az/field path ptr) 514 (ak/as :c_uint 384))]
      (when (< descriptor 0) (ak/return 7))
      (when (ak/!= (flock descriptor 6) 0)
        (set! _ (close descriptor))
        (ak/return 2))
      (when (>= bridge-lock 0) (set! _ (close bridge-lock)))
      (az/set-many! bridge-lock descriptor bridge-response-size 0)
      (copy-string! (ak/& bridge-directory) directory length)
      0)))

(az/defn pitoco_bridge_close_v1
  {:attrs #{:export}}
  :- :void []
  (az/set-many! checked-environment true (az/index bridge-directory 0) 0 bridge-response-size 0)
  (when (>= bridge-lock 0) (set! _ (close bridge-lock)))
  (set! bridge-lock -1))

(az/defn execute!
  :- :u32 [[request [:*const Pending]] [panel [:c-pointer api/LabPanel]]]
  (let [operation (az/field request operation)
        text (ak/as (az/type [:pointer {:size :c :const? true} :u8]) (ak/& (az/index (az/field request text) 0)))
        length (bounded-length text 4097)]
    (cond
      (ak/== operation 1)
      (do
        (when (ak/!= (az/field (az/index panel 0) baking) 0) (ak/return 2))
        (when (or (< (az/field request integer) 0) (>= (az/field request integer) (az/field (az/index panel 0) count))) (ak/return 3))
        (az/set-many! (az/field (az/index panel 0) cursor) (ak/intCast (az/field request integer))
                      (az/field (az/index panel 0) paused) 1 (az/field (az/index panel 0) action) 3)
        0)

      (ak/== operation 2) (do (set! (az/field (az/index panel 0) paused) 1) 0)
      (ak/== operation 3) (do (when (ak/!= (az/field (az/index panel 0) baking) 0) (ak/return 2)) (set! (az/field (az/index panel 0) paused) 0) 0)
      (ak/== operation 4) (do (when (ak/!= (az/field (az/index panel 0) baking) 0) (ak/return 2)) (set! (az/field (az/index panel 0) action) 4) 0)
      (ak/== operation 5) (do (set! (az/field (az/index panel 0) action) 8) 0)
      (ak/== operation 6) (load-plugin! text)
      (ak/== operation 7) (unload-plugin! (az/slice text 0 length))
      (ak/== operation 9) (pitoco_bridge_open_v1 text)
      (ak/== operation 8)
      (do
        (dotimes [index length]
          (when (ak/== (az/index text index) 9)
            (let [plugin (find-plugin (az/slice text 0 index))]
              (when (ak/== plugin null) (ak/return 5))
              (let [slot (az/unwrap plugin)]
                (ak/return ((az/unwrap (az/field (az/index (az/field slot api) 0) on_command))
                            (az/field slot state) (ak/& (az/index text (+ index 1)))))))))
        3)
      :else 3)))

(az/defn publish!
  :- :void [[panel [:c-pointer api/LabPanel]]]
  (lock!)
  (defer (unlock!))
  (az/set-many! (az/field snapshot frames) (ak/intCast (az/field (az/index panel 0) count))
                (az/field snapshot cursor) (ak/intCast (az/field (az/index panel 0) cursor))
                (az/field snapshot revision) (ak/intCast (az/field (az/index panel 0) revision))
                (az/field snapshot baking) (if (ak/!= (az/field (az/index panel 0) baking) 0) 1 0)
                (az/field snapshot paused) (if (ak/!= (az/field (az/index panel 0) paused) 0) 1 0)
                (az/field snapshot plugins) 0)
  (dotimes [index 16]
    (when (ak/!= (az/field (az/index plugins index) library) null)
      (set! (az/field snapshot plugins) (+ (az/field snapshot plugins) 1)))))

(az/defn parse-integer
  :- [:optional :i64] [[text [:slice-const :u8]]]
  (let [value (catch (fmt/parseInt :i64 text 10) (ak/return null))] value))

(az/defn response!
  :- :void [[bytes [:slice :u8]]]
  (let [^:var lines (mem/zeroes (az/type [:array 4 [:slice :u8]]))
        ^{:var :usize} offset 0
        ^:var valid (< (az/field bytes len) 8193)
        ^{:var :u32} result 3
        ^{:var :u64} ticket 0]
    (dotimes [line 4]
      (let [^:var end offset]
        (while (and (< end (az/field bytes len)) (ak/!= (az/index bytes end) 10))
          (when (ak/== (az/index bytes end) 0) (set! valid false))
          (set! end (+ end 1)))
        (when (ak/== end (az/field bytes len)) (set! valid false) (ak/break))
        (set! (az/index lines line) (az/slice bytes offset end))
        (set! (az/index bytes end) 0)
        (set! offset (+ end 1))))
    (set! valid (and valid (ak/== offset (az/field bytes len))
                    (mem/eql :u8 (az/index lines 0) "PITOCO/1")
                    (<= (az/field (az/index lines 3) len) 4096)))
    (when valid
      (cond
        (and (mem/eql :u8 (az/index lines 1) "status") (mem/eql :u8 (az/index lines 2) "0")
             (ak/== (az/field (az/index lines 3) len) 0))
        (set! result 0)

        (and (mem/eql :u8 (az/index lines 1) "result") (ak/== (az/field (az/index lines 3) len) 0))
        (do
          (set! ticket (catch (fmt/parseInt :u64 (az/index lines 2) 10) 0))
          (set! result (pitoco_result_v1 ticket)))

        :else
        (let [operation (catch (fmt/parseInt :u32 (az/index lines 1) 10) 0)
              integer (parse-integer (az/index lines 2))]
          (when (and (ak/!= integer null) (>= operation 1) (<= operation 9))
            (let [command (api/PitocoCommandV1
                            {:abi_version 1 :struct_size (ak/sizeOf api/PitocoCommandV1)
                             :operation operation :reserved 0 :integer (az/unwrap integer) :text (az/field (az/index lines 3) ptr)})]
              (set! result (pitoco_submit_v1 (ak/& command) (ak/& ticket))))))))
    (let [^:var status (api/PitocoStatusV1 {:abi_version 1 :struct_size (ak/sizeOf api/PitocoStatusV1)})]
      (set! _ (pitoco_status_v1 (ak/& status)))
      (let [text (catch (fmt/bufPrint (ak/& bridge-response)
                         "{{:protocol 1 :result {d} :ticket {d} :last-ticket {d} :last-result {d} :frames {d} :cursor {d} :revision {d} :baking? {s} :paused? {s} :plugins {d}}}\n"
                         [result ticket (az/field status last_ticket) (az/field status last_result)
                          (az/field status frames) (az/field status cursor) (az/field status revision)
                          (if (ak/!= (az/field status baking) 0) "true" "false")
                          (if (ak/!= (az/field status paused) 0) "true" "false") (az/field status plugins)])
                   (ak/return))]
        (set! bridge-response-size (az/field text len))))))

(az/defn poll-bridge!
  :- :void []
  (when (ak/== (az/index bridge-directory 0) 0) (ak/return))
  (let [directory (az/slice bridge-directory 0 (bounded-length (ak/& (az/index bridge-directory 0)) 4097))
        ^{:var [:array 4120 :u8]} request-buffer ak/undefined
        ^{:var [:array 4120 :u8]} reply-buffer ak/undefined
        ^{:var [:array 4120 :u8]} temporary-buffer ak/undefined
        request (catch (fmt/bufPrintZ (ak/& request-buffer) "{s}/request" [directory]) (ak/return))
        reply (catch (fmt/bufPrintZ (ak/& reply-buffer) "{s}/reply" [directory]) (ak/return))
        temporary (catch (fmt/bufPrintZ (ak/& temporary-buffer) "{s}/reply.tmp" [directory]) (ak/return))]
    (when (ak/== (access (az/field reply ptr) 0) 0) (ak/return))
    (when (ak/== bridge-response-size 0)
      (let [descriptor (open (az/field request ptr) 0)
            ^{:var [:array 8193 :u8]} bytes ak/undefined
            ^{:var :usize} count 0]
        (when (< descriptor 0) (ak/return))
        (defer (set! _ (close descriptor)))
        (while (< count 8193)
          (let [received (read descriptor (ak/& (az/index bytes count)) (- 8193 count))]
            (when (< received 0)
              (if (ak/== (az/deref (__error)) 4) (ak/continue) (ak/return)))
            (when (ak/== received 0) (ak/break))
            (set! count (+ count (ak/as :usize (ak/intCast received))))))
        (response! (az/slice bytes 0 count))))
    (when (ak/== bridge-response-size 0) (ak/return))
    (let [descriptor (open (az/field temporary ptr) 1537 (ak/as :c_uint 384))
          ^{:var :usize} count 0
          ^:var written true]
      (when (< descriptor 0) (ak/return))
      (while (< count bridge-response-size)
        (let [sent (write descriptor (ak/& (az/index bridge-response count)) (- bridge-response-size count))]
          (when (<= sent 0)
            (when (and (< sent 0) (ak/== (az/deref (__error)) 4)) (ak/continue))
            (set! written false)
            (ak/break))
          (set! count (+ count (ak/as :usize (ak/intCast sent))))))
      (let [closed (close descriptor)]
        (when (and written (ak/== closed 0)
                   (or (ak/== (unlink (az/field request ptr)) 0) (ak/== (az/deref (__error)) 2))
                   (ak/== (rename (az/field temporary ptr) (az/field reply ptr)) 0))
          (set! bridge-response-size 0))))))

(az/defn pitoco_tick_v1
  {:attrs #{:export}}
  :- :void [[panel [:c-pointer api/LabPanel]]]
  (when (ak/== panel null) (ak/return))
  (when (ak/! checked-environment)
    (set! checked-environment true)
    (let [directory (getenv "PITOCO_BRIDGE_DIR")]
      (when (ak/!= directory null) (set! _ (pitoco_bridge_open_v1 directory)))))
  (publish! panel)
  (poll-bridge!)
  (let [^:var request (mem/zeroes (az/type Pending))
        ^:var available false]
    (lock!)
    (when (and occupied (ak/== (az/field (az/index panel 0) action) 0))
      (az/set-many! request pending occupied false executing-ticket (az/field request ticket) available true))
    (unlock!)
    (when available
      (let [result (execute! (ak/& request) panel)]
        (lock!)
        (defer (unlock!))
        (az/set-many! (az/index completions (ak/mod (az/field request ticket) 64))
                      (Completion {:ticket (az/field request ticket) :result result})
                      executing-ticket 0
                      (az/field snapshot last_ticket) (az/field request ticket)
                      (az/field snapshot last_result) result))))
  (publish! panel))

(az/defn pitoco_shutdown_v1
  {:attrs #{:export}}
  :- :void []
  (dotimes [index 16]
    (let [plugin (ak/& (az/index plugins index))]
      (when (ak/!= (az/field plugin library) null)
        (set! _ (unload-plugin! (az/slice (az/field plugin id) 0
                                (bounded-length (ak/& (az/index (az/field plugin id) 0)) 65)))))))
  (pitoco_bridge_close_v1)
  (lock!)
  (defer (unlock!))
  (az/set-many! pending (mem/zeroes (az/type Pending)) occupied false (az/field snapshot plugins) 0))

;; Distinct application entries permit replacing the retired C++ host inside an
;; already-running process without binding its retained legacy SDK symbols.
(az/defn pitoco_aguafria_tick_v1
  {:attrs #{:export}}
  :- :void [[panel [:c-pointer api/LabPanel]]]
  (pitoco_tick_v1 panel))

(az/defn pitoco_aguafria_submit_v1
  {:attrs #{:export}}
  :- :u32 [[command [:pointer {:size :c :const? true} api/PitocoCommandV1]] [ticket [:c-pointer :u64]]]
  (pitoco_submit_v1 command ticket))

(az/defn pitoco_aguafria_shutdown_v1
  {:attrs #{:export}}
  :- :void []
  (pitoco_shutdown_v1))
