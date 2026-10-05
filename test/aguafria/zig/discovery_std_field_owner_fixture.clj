(ns aguafria.zig.discovery-std-field-owner-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.std.lang.SourceLocation :as source]
            [aguafria.zig :as a]))

(a/defn source-line :u32 []
  (let [location (k/src)]
    (source/-line location)))

(a/defstruct Other
  [[:line :i32]])

(a/defn other-line :i32 [[value Other]]
  (source/-line value))
