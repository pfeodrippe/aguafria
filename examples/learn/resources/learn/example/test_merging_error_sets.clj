(ns learn.example.test-merging-error-sets
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defconst DirectoryError
  (az/type [:error-set [:NotDir
                       ;; A doc comment: this set describes missing paths.
                       :PathNotFound]]))

(az/defconst AllocationError
  (az/type [:error-set [:OutOfMemory
                       ;; B doc comment: the shared member merges by name.
                       :PathNotFound]]))

(az/defconst CombinedError (az/op "||" DirectoryError AllocationError))

(az/defn- fail-not-directory [:error-union CombinedError :void] []
  (ak/return (az/error-value :NotDir)))

(az/deftest merge-error-sets-test
  (az/if-capture-stmt {:error [error]} (fail-not-directory)
    (ak/panic "unexpected")
    (az/switch-stmt error
      (case [(az/error-value :OutOfMemory)] (ak/panic "unexpected"))
      (case [(az/error-value :PathNotFound)] (ak/panic "unexpected"))
      (case [(az/error-value :NotDir)] (az/block)))))
