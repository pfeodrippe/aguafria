(ns pitoco-plugin.rewind
  "A compiled native plugin authored in Clojure. No JVM is needed to load it."
  (:require [aguafria.std]
            [aguafria.zig :as az]
            [aguafria.keyword :as ak]
            [aguafria.std.mem :as mem]
            [pitoco-plugin.abi]
            [pitoco-plugin.sdk :as sdk]))

(az/defvar host [:optional [:*const sdk/PitocoHostV1]] null)

(az/defn attach
  {:zig/qualifiers "callconv(.c)"}
  :- :u32
  [[api [:pointer {:size :c :const? true} sdk/PitocoHostV1]] [state [:c-pointer [:optional [:* :anyopaque]]]]]
  (when (or (ak/== api null) (ak/!= (az/field (az/index api 0) abi_version) 1)
            (< (az/field (az/index api 0) struct_size) (ak/sizeOf sdk/PitocoHostV1)))
    (ak/return 4))
  (az/set-many!
    host api
    (az/index state 0) null)
  0)

(az/defn detach
  {:zig/qualifiers "callconv(.c)"}
  :- :void
  [[state [:optional [:* :anyopaque]]]]
  (set! _ state)
  (set! host null))

(az/defn run-command
  {:zig/qualifiers "callconv(.c)"}
  :- :u32
  [[state [:optional [:* :anyopaque]]] [command [:pointer {:size :c :const? true} :u8]]]
  (set! _ state)
  (when (or (ak/== host null) (ak/== command null)) (ak/return 3))
  (when (ak/! (mem/eql :u8 (mem/span command) "rewind")) (ak/return 3))
  (let [request (sdk/PitocoCommandV1 {:abi_version 1 :struct_size (ak/sizeOf sdk/PitocoCommandV1)
                                      :operation 1 :reserved 0 :integer 0 :text null})
        ^{:var :u64} ticket 0
        submit (az/unwrap (az/field (az/unwrap host) submit))
        result (submit (ak/& request) (ak/& ticket))]
    (if (ak/== result 1) 0 result)))

(az/defconst descriptor
  sdk/PitocoPluginV1
  (sdk/PitocoPluginV1 {:abi_version 1 :struct_size (ak/sizeOf sdk/PitocoPluginV1)
                       :id "example.rewind" :on_load (ak/& attach) :on_unload (ak/& detach)
                       :on_command (ak/& run-command)}))

(az/defn pitoco_plugin_v1
  {:attrs #{:export}}
  :- [:*const sdk/PitocoPluginV1]
  []
  (ak/& descriptor))
