(ns learn.example.test-arrays
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.std.mem :as mem]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

;; array literal
(a/defconst message
  (a/array [\h \e \l \l \o] :u8))

;; alternative initialization using result location
(a/defconst alt-message [:array 5 :u8] [\h \e \l \l \o])

(a/defcomptime matching-initializers
  (debug/assert (mem/eql :u8 (k/& message) (k/& alt-message))))

;; get the size of an array
(a/defcomptime message-length
  (debug/assert (k/== (:len message) 5)))

;; A string literal is a single-item pointer to an array.
(a/defconst same-message "hello")

(a/defcomptime matching-string
  (debug/assert (mem/eql :u8 (k/& message) same-message)))

(a/deftest iterate-over-an-array
  (let [sum (k/var 0 :usize)]
    (k/for [byte message]
      (k/+= sum byte))
    (try (testing/expectEqual (k/+ \h \e (k/* \l 2) \o) sum))))

;; modifiable array
(a/defvar some-integers [:array 100 :i32] k/undefined)

(a/deftest modify-an-array
  (k/for [(k/* item) (k/& some-integers)
          i (a/range 0)]
    (k/= @item (k/intCast i)))
  (try (testing/expectEqual 10 (a/get some-integers 10)))
  (try (testing/expectEqual 99 (a/get some-integers 99))))

;; array concatenation works if the values are known
;; at compile time
(a/defconst part-one (a/array [1 2 3 4] :i32))
(a/defconst part-two (a/array [5 6 7 8] :i32))
(a/defconst all-of-it (k/++ part-one part-two))

(a/defcomptime concatenated-array
  (debug/assert
   (mem/eql :i32 (k/& all-of-it)
            (k/& (a/array [1 2 3 4 5 6 7 8] :i32)))))

;; remember that string literals are arrays
(a/defconst hello "hello")
(a/defconst world "world")
(a/defconst hello-world (k/++ hello " " world))

(a/defcomptime concatenated-string
  (debug/assert (mem/eql :u8 hello-world "hello world")))

;; initialize an array to zero
(a/defconst all-zero [:array 10 :u16] (k/splat 0))

(a/defcomptime zero-initialization
  (debug/assert (k/== (:len all-zero) 10))
  (debug/assert (k/== (a/get all-zero 5) 0)))

(a/defstruct Point
  [[:x :i32]
   [:y :i32]])

;; use compile-time code to initialize an array
(a/defvar fancy-array
  (a/with-block :init
    (let [initial-value (k/var k/undefined [:array 10 Point])]
      (k/for [(k/* pt) (k/& initial-value)
              i (a/range 0)]
        (k/= @pt (Point {:x (k/intCast i)
                         :y (k/intCast (k/* i 2))})))
      (k/break :init initial-value))))

(a/deftest compile-time-array-initialization
  (try (testing/expectEqual 4 (a/get-in fancy-array [4 :x])))
  (try (testing/expectEqual 8 (a/get-in fancy-array [4 :y]))))

(a/defn- make-point Point
  [[x :i32]]
  (Point {:x x :y (k/* x 2)}))

;; call a function to initialize an array
(a/defvar more-points [:array 10 Point] (k/splat (make-point 3)))

(a/deftest array-initialization-with-function-calls
  (try (testing/expectEqual 3 (a/get-in more-points [4 :x])))
  (try (testing/expectEqual 6 (a/get-in more-points [4 :y])))
  (try (testing/expectEqual 10 (:len more-points))))

(comment
  (iterate-over-an-array)
  (modify-an-array)
  (compile-time-array-initialization)
  (array-initialization-with-function-calls))
