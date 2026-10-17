(ns learn.example.test-arrays
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
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
  (debug/assert (== (:len message) 5)))

;; A string literal is a single-item pointer to an array.
(a/defconst same-message "hello")

(a/defcomptime matching-string
  (debug/assert (mem/eql :u8 (k/& message) same-message)))

(a/deftest iterate-over-an-array
  (let [sum (k/var 0 :usize)]
    (for [byte message]
      (+= sum byte))
    (k/try (testing/expectEqual (+ \h \e (* \l 2) \o) sum))))

;; modifiable array
(a/defvar some-integers [:array 100 :i32] undefined)

(a/deftest modify-an-array
  (for [(* item) (k/& some-integers)
        i (a/range 0)]
    (= @item (intCast i)))
  (k/try (testing/expectEqual 10 (a/get some-integers 10)))
  (k/try (testing/expectEqual 99 (a/get some-integers 99))))

;; array concatenation works if the values are known
;; at compile time
(a/defconst part-one (a/array [1 2 3 4] :i32))
(a/defconst part-two (a/array [5 6 7 8] :i32))
(a/defconst all-of-it (++ part-one part-two))

(a/defcomptime concatenated-array
  (debug/assert
   (mem/eql :i32 (k/& all-of-it)
            (k/& (a/array [1 2 3 4 5 6 7 8] :i32)))))

;; remember that string literals are arrays
(a/defconst hello "hello")
(a/defconst world "world")
(a/defconst hello-world (++ hello " " world))

(a/defcomptime concatenated-string
  (debug/assert (mem/eql :u8 hello-world "hello world")))

;; initialize an array to zero
(a/defconst all-zero [:array 10 :u16] (splat 0))

(a/defcomptime zero-initialization
  (debug/assert (== (:len all-zero) 10))
  (debug/assert (== (a/get all-zero 5) 0)))

(a/defstruct Point
  [[:x :i32]
   [:y :i32]])

;; use compile-time code to initialize an array
(a/defvar fancy-array
  (a/with-block :init
    (let [initial-value (k/var undefined [:array 10 Point])]
      (for [(* pt) (k/& initial-value)
            i (a/range 0)]
        (= @pt (Point {:x (intCast i)
                       :y (intCast (* i 2))})))
      (break :init initial-value))))

(a/deftest compile-time-array-initialization
  (k/try (testing/expectEqual 4 (a/get-in fancy-array [4 :x])))
  (k/try (testing/expectEqual 8 (a/get-in fancy-array [4 :y]))))

(a/defn- make-point Point
  [[x :i32]]
  (Point {:x x :y (* x 2)}))

;; call a function to initialize an array
(a/defvar more-points [:array 10 Point] (splat (make-point 3)))

(a/deftest array-initialization-with-function-calls
  (k/try (testing/expectEqual 3 (a/get-in more-points [4 :x])))
  (k/try (testing/expectEqual 6 (a/get-in more-points [4 :y])))
  (k/try (testing/expectEqual 10 (:len more-points))))

(comment
  (iterate-over-an-array)
  (modify-an-array)
  (compile-time-array-initialization)
  (array-initialization-with-function-calls))
