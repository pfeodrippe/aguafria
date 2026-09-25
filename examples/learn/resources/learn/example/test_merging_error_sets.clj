(ns learn.example.test-merging-error-sets
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defconst A
  (az/type [:error-set [:NotDir
                       ;; A doc comment: this set describes missing paths.
                        :PathNotFound]]))

(az/defconst B
  (az/type [:error-set [:OutOfMemory
                       ;; B doc comment: the shared member merges by name.
                        :PathNotFound]]))

(az/defconst C (az/op "||" A B))

(az/defn- foo [:error-union C :void] []
  (az/error-value :NotDir))

(az/deftest merge-error-sets-test
  (az/if-capture-stmt {:error [error]} (foo)
                      (k/panic "unexpected")
                      (az/switch-stmt error
                        (case [(az/error-value :OutOfMemory)] (k/panic "unexpected"))
                        (case [(az/error-value :PathNotFound)] (k/panic "unexpected"))
                        (case [(az/error-value :NotDir)] (az/block)))))

(comment
  (merge-error-sets-test))
