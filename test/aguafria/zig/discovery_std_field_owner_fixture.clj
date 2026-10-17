(ns aguafria.zig.discovery-std-field-owner-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.lang.SourceLocation :as source]
            [aguafria.zig :as a]))

(a/defn source-line :u32 []
  (let [location (src)]
    (source/-line location)))

(a/defstruct Other
  [[:line :i32]])

(a/defn other-line :i32 [[value Other]]
  (source/-line value))
