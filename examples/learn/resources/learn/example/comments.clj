(ns learn.example.comments
  (:require [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void
  []
  ;; Comments in Zig start with "//" and end at the next LF byte (end of line).
  ;; The line below is a comment and won't be executed.

  ;;print("Hello?", .{});

  (debug/print "Hello, world!\n" [])) ; another comment

(comment
  (main))
