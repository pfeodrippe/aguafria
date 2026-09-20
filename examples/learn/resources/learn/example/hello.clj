(ns learn.example.hello
  (:require [aguafria.keyword :as ak]
            [aguafria.std.Io.File :as std-file]
            [aguafria.std.process :as process]
            [aguafria.zig :as az]))

(az/defn main :!void
  [[process-init process/Init]]
  (try
    (-> (std-file/stdout)
        (std-file/writeStreamingAll
         (az/field process-init :io)
         "Hello, World!\n"))))

(comment
  (main))
