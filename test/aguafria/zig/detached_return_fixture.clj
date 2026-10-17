(ns aguafria.zig.detached-return-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defn bump :u32 [[target [:* :u32]]]
  (k/+= (a/deref target) 1)
  (k/return (a/deref target)))

(a/defn maybe-value [:error-union [:error-set [:Rejected]] :u32] [[fail? :bool]]
  (if fail?
    (k/return (a/error-value :Rejected))
    (k/return 11)))

(a/defn terminal-value :u32 [[input :u32]]
  (k/return input))

(a/deftest skip-value
  (k/return (a/error-value :SkipZigTest)))
