(ns learn.example.identifiers
  (:require [aguafria.std.c :as c]
            [aguafria.zig :as az]))

;; Friendly Clojure names can retain exact, quoted Zig identifiers.
(az/defconst identifier-with-spaces
  {:zig/name "@\"identifier with spaces in it\""}
  0xff)
(az/defconst one-small-step
  {:zig/name "@\"1SmallStep4Man\""}
  112358)

(az/defextern error
  {:zig/prefix "pub extern \"c\""}
  :- :void
  [])
(az/defextern fstat-inode64
  {:zig/name "@\"fstat$INODE64\"" :zig/prefix "pub extern \"c\""}
  :- :c_int
  [[fd c/fd_t] [buf [:* c/Stat]]])

(az/defconst Color
  (az/container {:kind :enum}
    (az/enum-field-decl :red)
    (az/enum-field-decl :really-red {:zig/name "@\"really red\""})))

(az/defconst color Color (az/enum-literal ".@\"really red\""))

(comment
  ;; This excerpt has no standalone entry point; evaluate its declarations above in their documented context.
  )
