(ns aguafria.zig.enum-literal-prepared-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

;; An unused extern must not prevent preparing the namespace load image.
(a/defextern unused :void {:zig/prefix "pub extern \"c\""} [])
(a/defenum Color
  [:red [:really-red {:zig/name "@\"really red\""}] :i32])
(a/defconst red Color (a/enum-literal ".red"))
(a/defconst spaced Color (a/enum-literal ".@\"really red\""))
(a/defconst primitive-name Color (a/enum-literal ".i32"))
