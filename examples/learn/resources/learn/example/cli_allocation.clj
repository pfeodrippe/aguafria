(ns learn.example.cli-allocation
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.std.heap :as heap]
            [aguafria.zig :as az]))

(az/defn main [:error-union :void] []
  (let [arena (k/var ((:init heap/ArenaAllocator) heap/page_allocator))]
    (k/defer ((:deinit arena)))
    (let [allocator ((:allocator arena))
          ptr (try ((:create allocator) :i32))]
      (debug/print "ptr={*}\n" [ptr]))))

(comment
  (main))
