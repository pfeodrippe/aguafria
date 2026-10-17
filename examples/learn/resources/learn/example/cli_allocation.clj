(ns learn.example.cli-allocation
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.debug :as debug]
            [aguafria.std.heap :as heap]
            [aguafria.zig :as a]))

(a/defn main [:error-union :void] []
  (let [arena (k/var ((:init heap/ArenaAllocator) heap/page_allocator))]
    (defer ((:deinit arena)))
    (let [allocator ((:allocator arena))
          ptr (k/try ((:create allocator) :i32))]
      (debug/print "ptr={*}\n" [ptr]))))

(comment
  (main))
