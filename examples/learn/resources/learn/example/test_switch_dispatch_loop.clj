(ns learn.example.test-switch-dispatch-loop
  (:require [aguafria.keyword :as k]
            [aguafria.std :as std]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defenum Instruction
  [:add
   :mul
   :end])

(az/defn- evaluate [:error-union :i32]
  [[initial-stack [:slice-const :i32]] [code [:slice-const Instruction]]]
  (let [buffer (k/var k/undefined [:array 8 :i32])
        stack (k/var ((az/field (std/ArrayList :i32) :initBuffer) (k/& buffer)))
        instruction-pointer (k/var 0 :usize)]
    (try ((az/field stack :appendSliceBounded) initial-stack))
    (az/labeled-switch vm (az/index code instruction-pointer)
      (case [:.add]
        (az/block
          (let [right (az/unwrap ((az/field stack :pop)))
                left (az/unwrap ((az/field stack :pop)))]
            (try ((az/field stack :appendBounded) (k/+ left right))))
          (k/+= instruction-pointer 1)
          (k/continue vm (az/index code instruction-pointer))))
      (case [:.mul]
        (az/block
          (let [right (az/unwrap ((az/field stack :pop)))
                left (az/unwrap ((az/field stack :pop)))]
            (try ((az/field stack :appendBounded) (k/* left right))))
          (k/+= instruction-pointer 1)
          (k/continue vm (az/index code instruction-pointer))))
      (case [:.end] (az/unwrap ((az/field stack :pop)))))))

(az/deftest stack-machine-test
  (let [result (try (evaluate (k/& [7 2 -3]) (k/& [:.mul :.add :.end])))]
    (try (testing/expectEqual 1 result))))

(comment
  (stack-machine-test))
