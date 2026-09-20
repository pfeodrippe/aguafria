(ns learn.example.hello
  (:require [aguafria.keyword :as ak]
            [aguafria.std.Io.File :as std-file]
            [aguafria.std.process :as process]
            [aguafria.std.process.Init :as process-init]
            [aguafria.zig :as az]))

(az/defn main :!void
  [[process-init process/Init]]
  (try
    (-> (std-file/stdout)
        (std-file/writeStreamingAll
         (process-init/-io process-init)
         "Hello, World!\n"))))

(comment
  (main))
