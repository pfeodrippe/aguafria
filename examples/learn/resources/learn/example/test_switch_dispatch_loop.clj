(ns learn.example.test-switch-dispatch-loop
  (:require [aguafria.keyword :as k]
            [aguafria.std :as std]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defenum Instruction
  [:add
   :mul
   :end])

(a/defn- evaluate [:error-union :i32]
  [[initial-stack [:slice-const :i32]] [code [:slice-const Instruction]]]
  (let [buffer (k/var k/undefined [:array 8 :i32])
        stack (k/var ((:initBuffer (std/ArrayList :i32)) (k/& buffer)))
        ip (k/var 0 :usize)]
    (try ((:appendSliceBounded stack) initial-stack))
    (a/labeled-switch vm (a/get code ip)
      ;; Because all code after `continue` is unreachable, this branch does
      ;; not provide a result.
                      (case [:.add]
                        (a/block
                         (try ((:appendBounded stack)
                               (k/+ (a/unwrap ((:pop stack))) (a/unwrap ((:pop stack))))))
                         (k/+= ip 1)
                         (k/continue vm (a/get code ip))))
                      (case [:.mul]
                        (a/block
                         (try ((:appendBounded stack)
                               (k/* (a/unwrap ((:pop stack))) (a/unwrap ((:pop stack))))))
                         (k/+= ip 1)
                         (k/continue vm (a/get code ip))))
                      (case [:.end] (a/unwrap ((:pop stack)))))))

(a/deftest evaluate-test
  (let [result (try (evaluate (k/& [7 2 -3]) (k/& [:.mul :.add :.end])))]
    (try (testing/expectEqual 1 result))))

(comment
  (evaluate-test))
