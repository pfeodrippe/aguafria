(ns learn.example.hello
  (:require [aguafria.keyword :as ak]
            [aguafria.std.Io.File :as std-file]
            [aguafria.std.process :as process]
            [aguafria.zig :as az]))

(az/defn main :!void
  [[process-init process/Init]]
  (try (std-file/writeStreamingAll
        (std-file/stdout)
        (az/field process-init :io)
        "Hello, World!\n")))

(comment
  (main))
