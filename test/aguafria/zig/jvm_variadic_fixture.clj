(ns aguafria.zig.jvm-variadic-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.std.c :as c]
            [aguafria.zig :as a]))

(a/defn add :c_int
  {:zig/qualifiers "callconv(.c)"}
  [[count :c_int] [... {:zig/variadic true} _]]
  (let [ap (k/var (k/cVaStart))]
    (k/defer (k/cVaEnd (k/& ap)))
    (let [i (k/var 0 :usize)
          sum (k/var 0 :c_int)]
      (a/while-loop {:continue (a/assign-expr "+=" i 1)}
                    (k/< i count)
                    (k/+= sum (k/cVaArg (k/& ap) :c_int)))
      sum)))

(a/defn echo-C T
  [[T {:attrs #{k/comptime}} :type] [value T]]
  value)

(a/deftest never-run
  (k/= :_ (add 0))
  (k/= :_ (add 1 (k/as 1 :c_int)))
  (k/= :_ (add 2 (k/as 1 :c_int) (k/as 2 :c_int)))
  (k/= :_ (c/printf ""))
  (k/= :_ (c/printf "%d\n" (k/i32 12)))
  (k/= :_ (c/printf "%s=%d\n" "value" (k/i32 42)))
  (k/= :_ (echo-C :c_short 12))
  (k/= :_ (echo-C :c_long 42))
  (k/= :_ (echo-C :c_ulonglong 99))
  (k/unreachable))
