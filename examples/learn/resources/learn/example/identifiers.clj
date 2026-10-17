(ns learn.example.identifiers
  (:refer-clojure :only [])
  (:require [aguafria.std.c :as libc]
            [aguafria.zig :as a]
            [aguafria.keyword :as k :refer :all]))

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
  [[fd libc/fd_t] [buf [:* libc/Stat]]])

(a/defenum Color
  [:red
   [:really-red {:zig/name "@\"really red\""}]])

(a/defconst color Color (a/enum-literal ".@\"really red\""))
