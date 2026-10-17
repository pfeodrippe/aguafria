(ns learn.snippet.errdefer-example
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defn- create-foo [:error-union Foo] [[parameter :i32]]
  (let [foo (k/try (try-to-allocate-foo))]
    ;; Ownership transfers to the caller only when this function succeeds.
    (errdefer (deallocate-foo foo))
    (let [temporary-buffer (orelse (allocate-tmp-buffer)
                                   (return (a/error-value :OutOfMemory)))]
      ;; Temporary storage is released on either exit path.
      (defer (deallocate-tmp-buffer temporary-buffer))
      (when (> parameter 1337)
        (return (a/error-value :InvalidParam)))
      foo)))
