(ns aguafria.zig.jvm-variadic-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.c :as libc]
            [aguafria.zig :as a]))

(a/defn add :c_int
  {:zig/qualifiers "callconv(.c)"}
  [[count :c_int] [... {:zig/variadic true} _]]
  (let [ap (k/var (cVaStart))]
    (defer (cVaEnd (k/& ap)))
    (let [i (k/var 0 :usize)
          sum (k/var 0 :c_int)]
      (a/while-loop {:continue (a/assign-expr "+=" i 1)}
                    (< i count)
                    (+= sum (cVaArg (k/& ap) :c_int)))
      sum)))

(a/defn echo-C T
  [[T {:attrs #{comptime}} :type] [value T]]
  value)

(a/deftest never-run
  (= :_ (add 0))
  (= :_ (add 1 (as 1 :c_int)))
  (= :_ (add 2 (as 1 :c_int) (as 2 :c_int)))
  (= :_ (libc/printf ""))
  (= :_ (libc/printf "%d\n" (i32 12)))
  (= :_ (libc/printf "%s=%d\n" "value" (i32 42)))
  (= :_ (echo-C :c_short 12))
  (= :_ (echo-C :c_long 42))
  (= :_ (echo-C :c_ulonglong 99))
  (unreachable))
