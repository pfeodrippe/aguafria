(ns aguafria.zig.precompile-eager-constants-fixture
  (:require [aguafria.zig :as a]))

(a/defconst selected :u32 11)
(a/defconst required-a 3)
(a/defconst required-b 7)
