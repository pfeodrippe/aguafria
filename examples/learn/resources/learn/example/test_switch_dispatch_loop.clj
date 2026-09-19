(ns learn.example.test-switch-dispatch-loop
  (:require [aguafria.keyword :as ak]
            [aguafria.std :as std]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defconst Instruction
  (az/container {:kind :enum}
    (az/enum-field-decl :add)
    (az/enum-field-decl :mul)
    (az/enum-field-decl :end)))

(az/defn- evaluate [:error-union :i32]
  [[initial-stack [:slice-const :i32]] [code [:slice-const Instruction]]]
  (let [^{:var [:array 8 :i32]} buffer ak/undefined
        ^:var stack ((az/field (std/ArrayList :i32) :initBuffer) (ak/& buffer))
        ^{:var :usize} instruction-pointer 0]
    (try ((az/field stack :appendSliceBounded) initial-stack))
    (az/labeled-switch vm (az/index code instruction-pointer)
      (case [:.add]
        (az/block
          (let [right (az/unwrap ((az/field stack :pop)))
                left (az/unwrap ((az/field stack :pop)))]
            (try ((az/field stack :appendBounded) (+ left right))))
          (ak/+= instruction-pointer 1)
          (ak/continue vm (az/index code instruction-pointer))))
      (case [:.mul]
        (az/block
          (let [right (az/unwrap ((az/field stack :pop)))
                left (az/unwrap ((az/field stack :pop)))]
            (try ((az/field stack :appendBounded) (* left right))))
          (ak/+= instruction-pointer 1)
          (ak/continue vm (az/index code instruction-pointer))))
      (case [:.end] (az/unwrap ((az/field stack :pop)))))))

(az/deftest stack-machine-test
  (let [result (try (evaluate (ak/& [7 2 -3]) (ak/& [:.mul :.add :.end])))]
    (try (testing/expectEqual 1 result))))
