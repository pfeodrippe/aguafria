(ns learn.example.identifiers
  (:require [aguafria.std.c :as c]
            [aguafria.zig :as a]))

(a/defconst identifier-with-spaces-in-it
  {:zig/name "@\"identifier with spaces in it\""}
  0xff)
(a/defconst one-small-step4-man
  {:zig/name "@\"1SmallStep4Man\""}
  112358)

(a/defextern error :void
  {:zig/prefix "pub extern \"c\""}
  [])
(a/defextern fstat-inode64 :c_int
  {:zig/name "@\"fstat$INODE64\"" :zig/prefix "pub extern \"c\""}
  [[fd c/fd_t] [buf [:* c/Stat]]])

(a/defenum Color
  [:red
   [:really-red {:zig/name "@\"really red\""}]])

(a/defconst color Color (a/enum-literal ".@\"really red\""))
