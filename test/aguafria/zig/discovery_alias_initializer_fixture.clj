(ns aguafria.zig.discovery-alias-initializer-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defn Box :type
  [[T {:attrs #{k/comptime}} :type]
   [buffer-capacity {:attrs #{k/comptime}} :usize]]
  (a/struct [[:items [:array buffer-capacity T]]]))

(a/defconst make-box Box)

(a/defstruct Owner
  [[:Buffer {:const (make-box :u32 4)} :type]])

(a/defstruct Wrapper
  [[:buffer (:Buffer Owner)]])

(a/defn buffer-length :usize
  [[wrapper Wrapper]]
  (:len (:items (:buffer wrapper))))
