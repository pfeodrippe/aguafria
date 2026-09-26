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
        stack (k/var ((:initBuffer (std/ArrayList :i32)) (k/& buffer)))
        instruction-pointer (k/var 0 :usize)]
    (try ((:appendSliceBounded stack) initial-stack))
    (az/labeled-switch vm (az/get code instruction-pointer)
      (case [:.add]
        (az/block
          (let [right (az/unwrap ((:pop stack)))
                left (az/unwrap ((:pop stack)))]
            (try ((:appendBounded stack) (k/+ left right))))
          (k/+= instruction-pointer 1)
          (k/continue vm (az/get code instruction-pointer))))
      (case [:.mul]
        (az/block
          (let [right (az/unwrap ((:pop stack)))
                left (az/unwrap ((:pop stack)))]
            (try ((:appendBounded stack) (k/* left right))))
          (k/+= instruction-pointer 1)
          (k/continue vm (az/get code instruction-pointer))))
      (case [:.end] (az/unwrap ((:pop stack)))))))

(az/deftest stack-machine-test
  (let [result (try (evaluate (k/& [7 2 -3]) (k/& [:.mul :.add :.end])))]
    (try (testing/expectEqual 1 result))))

(comment
  (stack-machine-test))
