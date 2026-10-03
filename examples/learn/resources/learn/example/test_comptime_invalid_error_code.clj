(ns learn.example.test-comptime-invalid-error-code
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defcomptime reject-invalid-error-code
  (k/= :_ (k/errorFromInt 12345)))
