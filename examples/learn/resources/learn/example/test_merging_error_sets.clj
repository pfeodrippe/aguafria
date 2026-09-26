(ns learn.example.test-merging-error-sets
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defconst A
  (az/type [:error-set [:NotDir
                       ;; A doc comment
                        :PathNotFound]]))

(az/defconst B
  (az/type [:error-set [:OutOfMemory
                       ;; B doc comment
                        :PathNotFound]]))

(az/defconst C (k/|| A B))

(az/defn- foo [:error-union C :void] []
  (az/error-value :NotDir))

(az/deftest merge-error-sets
  (az/if-capture-stmt {:error [err]} (foo)
                      (k/panic "unexpected")
                      (az/switch-stmt err
                                      (case [(az/error-value :OutOfMemory)] (k/panic "unexpected"))
                                      (case [(az/error-value :PathNotFound)] (k/panic "unexpected"))
                                      (case [(az/error-value :NotDir)] (az/block)))))

(comment
  (merge-error-sets))
