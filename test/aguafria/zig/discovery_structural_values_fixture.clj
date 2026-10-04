(ns aguafria.zig.discovery-structural-values-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/deftest do-not-execute
  (let [amount (k/var 10 :i32)]
    (a/assign-expr "+=" amount 2)
    (a/assign-expr "=" amount 20)
    (k/= :_ (k/+ amount 0)))
  (let [attempt (k/var 10 :u64)
        cursor (k/var 10 :usize)
        count (k/var 10 :u8)
        oldest (k/var nil [:optional :usize])
        index (k/usize 7)]
    (a/assign-expr "+=" attempt 2)
    (a/assign-expr "+=" cursor 2)
    (a/assign-expr "+=" count 2)
    (a/assign-expr "=" oldest index)
    (a/assign-expr "=" oldest nil))
  (let [sequence (k/var 10 [:optional :u64])]
    (k/= sequence nil))
  (k/= :_ @(a/multiline-string ["hello" "world"]))
  (k/unreachable))
