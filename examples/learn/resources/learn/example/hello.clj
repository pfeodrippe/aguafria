(ns learn.example.hello
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.Io.File :as std-file]
            [aguafria.std.process :as process]
            [aguafria.std.process.Init :as process-init]
            [aguafria.zig :as a]))

(a/defn main :!void
  [[process-init process/Init]]
  (k/try
    (-> (std-file/stdout)
        (std-file/writeStreamingAll
         (process-init/-io process-init)
         "Hello, World!\n"))))

(comment
  (main))
