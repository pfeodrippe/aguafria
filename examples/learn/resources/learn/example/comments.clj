(ns learn.example.comments
  (:refer-clojure :only [])
  (:require [aguafria.std.debug :as debug]
            [aguafria.zig :as a]
            [aguafria.keyword :as k :refer :all]))

(a/defn main :void
  []
  ;; Comments in Zig start with "//" and end at the next LF byte (end of line).
  ;; The line below is a comment and won't be executed.

  ;;print("Hello?", .{});

  (debug/print "Hello, world!\n" [])) ; another comment

(comment
  (main))
