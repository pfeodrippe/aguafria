(ns learn.snippet.errdefer-example
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defn- create-foo [:error-union Foo] [[parameter :i32]]
  (let [foo (try (try-to-allocate-foo))]
    ;; Ownership transfers to the caller only when this function succeeds.
    (ak/errdefer (deallocate-foo foo))
    (let [temporary-buffer (orelse (allocate-tmp-buffer)
                                  (ak/return (az/error-value :OutOfMemory)))]
      ;; Temporary storage is released on either exit path.
      (ak/defer (deallocate-tmp-buffer temporary-buffer))
      (when (> parameter 1337)
        (ak/return (az/error-value :InvalidParam)))
      foo)))

(comment
  ;; Contextual excerpt: evaluate the declarations above with the surrounding definitions.
  )
