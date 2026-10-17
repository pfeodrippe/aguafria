(ns aguafria.zig.enum-literal-prepared-driver
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(defn conversions []
  (let [color (var-get (requiring-resolve 'aguafria.zig.enum-literal-prepared-fixture/Color))]
    ;; These are ordinary JVM calls, not a replacement enclosing native scope.
    ;; Compare the same initializer evaluated by Zig in the declaration.
    (mapv (fn [literal constant]
            (let [converted (k/as literal color)
                  declared (var-get (requiring-resolve constant))]
              [(a/value literal) (a/value converted) (a/value declared)]))
          [(a/enum-literal ".red")
           (a/enum-literal ".@\"really red\"")
           (a/enum-literal ".i32")]
          ['aguafria.zig.enum-literal-prepared-fixture/red
           'aguafria.zig.enum-literal-prepared-fixture/spaced
           'aguafria.zig.enum-literal-prepared-fixture/primitive-name])))
