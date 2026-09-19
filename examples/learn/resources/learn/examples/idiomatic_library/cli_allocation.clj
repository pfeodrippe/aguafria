(ns learn.examples.idiomatic-library.cli-allocation
  "Converted from cli_allocation.zig"
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.std.heap :as heap]
            [aguafria.zig :as az]))

(az/defn main [:error-union :void] []
  (let [^:var arena ((az/field heap/ArenaAllocator :init) heap/page_allocator)]
    (ak/defer ((az/field arena :deinit)))
    (let [allocator ((az/field arena :allocator))
          pointer (try ((az/field allocator :create) :i32))]
      (debug/print "ptr={*}\n" [pointer]))))
