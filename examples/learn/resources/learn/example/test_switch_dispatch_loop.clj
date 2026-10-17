(ns learn.example.test-switch-dispatch-loop
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std :as std]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defenum Instruction
  [:add :mul :end])

(a/defn- evaluate [:error-union :i32]
  [[initial-stack [:slice-const :i32]]
   [code [:slice-const Instruction]]]
  (let [buffer (k/var undefined [:array 8 :i32])
        stack (k/var ((:initBuffer (std/ArrayList :i32)) (k/& buffer)))
        ip (k/var 0 :usize)]
    (k/try ((:appendSliceBounded stack) initial-stack))
    (switch [vm (a/get code ip)]
      ;; Because all code after `continue` is unreachable, this branch does
      ;; not provide a result.
      :add
      (do
        (k/try ((:appendBounded stack)
                (+ (a/unwrap ((:pop stack))) (a/unwrap ((:pop stack))))))
        (+= ip 1)
        (continue vm (a/get code ip)))

      :mul
      (do
        (k/try ((:appendBounded stack)
                (* (a/unwrap ((:pop stack))) (a/unwrap ((:pop stack))))))
        (+= ip 1)
        (continue vm (a/get code ip)))
      :end (a/unwrap ((:pop stack))))))

(a/deftest evaluate-test
  (let [result (k/try (evaluate (k/& [7 2 -3]) (k/& [:mul :add :end])))]
    (k/try (testing/expectEqual 1 result))))

(comment
  (evaluate-test))
