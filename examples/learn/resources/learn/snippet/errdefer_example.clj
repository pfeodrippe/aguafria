(ns learn.snippet.errdefer-example
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defn- create-foo [:error-union Foo] [[parameter :i32]]
  (let [foo (try (try-to-allocate-foo))]
    ;; Ownership transfers to the caller only when this function succeeds.
    (k/errdefer (deallocate-foo foo))
    (let [temporary-buffer (orelse (allocate-tmp-buffer)
                                   (k/return (a/error-value :OutOfMemory)))]
      ;; Temporary storage is released on either exit path.
      (k/defer (deallocate-tmp-buffer temporary-buffer))
      (when (k/> parameter 1337)
        (k/return (a/error-value :InvalidParam)))
      foo)))
