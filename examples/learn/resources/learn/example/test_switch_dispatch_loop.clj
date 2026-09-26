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
        ip (k/var 0 :usize)]
    (try ((:appendSliceBounded stack) initial-stack))
    (az/labeled-switch vm (az/get code ip)
      ;; Because all code after `continue` is unreachable, this branch does
      ;; not provide a result.
                       (case [:.add]
                         (az/block
                          (try ((:appendBounded stack)
                                (k/+ (az/unwrap ((:pop stack))) (az/unwrap ((:pop stack))))))
                          (k/+= ip 1)
                          (k/continue vm (az/get code ip))))
                       (case [:.mul]
                         (az/block
                          (try ((:appendBounded stack)
                                (k/* (az/unwrap ((:pop stack))) (az/unwrap ((:pop stack))))))
                          (k/+= ip 1)
                          (k/continue vm (az/get code ip))))
                       (case [:.end] (az/unwrap ((:pop stack)))))))

(az/deftest evaluate-test
  (let [result (try (evaluate (k/& [7 2 -3]) (k/& [:.mul :.add :.end])))]
    (try (testing/expectEqual 1 result))))

(comment
  (evaluate-test))
