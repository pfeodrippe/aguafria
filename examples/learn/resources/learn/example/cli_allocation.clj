(ns learn.example.cli-allocation
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.std.heap :as heap]
            [aguafria.zig :as az]))

(az/defn main [:error-union :void] []
  (let [arena (k/var ((az/field heap/ArenaAllocator :init) heap/page_allocator))]
    (k/defer ((az/field arena :deinit)))
    (let [allocator ((az/field arena :allocator))
          pointer (try ((az/field allocator :create) :i32))]
      (debug/print "ptr={*}\n" [pointer]))))

(comment
  (main))
