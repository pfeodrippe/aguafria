(ns learn.example.identifiers
  (:require [aguafria.std.c :as c]
            [aguafria.zig :as az]))

(az/defconst identifier-with-spaces-in-it
  {:zig/name "@\"identifier with spaces in it\""}
  0xff)
(az/defconst one-small-step4-man
  {:zig/name "@\"1SmallStep4Man\""}
  112358)

(az/defextern error :void
  {:zig/prefix "pub extern \"c\""}
  [])
(az/defextern fstat-inode64 :c_int
  {:zig/name "@\"fstat$INODE64\"" :zig/prefix "pub extern \"c\""}
  [[fd c/fd_t] [buf [:* c/Stat]]])

(az/defenum Color
  [:red
   [:really-red {:zig/name "@\"really red\""}]])

(az/defconst color Color (az/enum-literal ".@\"really red\""))
