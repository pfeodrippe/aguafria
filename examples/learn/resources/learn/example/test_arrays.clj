(ns learn.example.test-arrays
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.std.mem :as mem]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

;; Array literal.
(az/defconst message
  (az/array [\h \e \l \l \o] :u8))

;; Alternative initialization using result location.
(az/defconst alt-message [:array 5 :u8] [\h \e \l \l \o])

(az/defcomptime matching-initializers
  (debug/assert (mem/eql :u8 (k/& message) (k/& alt-message))))

;; Get the size of an array.
(az/defcomptime message-length
  (debug/assert (k/== (:len message) 5)))

;; A string literal is a single-item pointer to an array.
(az/defconst same-message "hello")

(az/defcomptime matching-string
  (debug/assert (mem/eql :u8 (k/& message) same-message)))

(az/deftest array-iteration-test
  (let [sum (k/var 0 :usize)]
    (k/for [byte message]
      (k/+= sum byte))
    (try (testing/expectEqual (k/+ \h \e (k/* \l 2) \o) sum))))

;; Modifiable array.
(az/defvar some-integers [:array 100 :i32] k/undefined)

(az/deftest array-mutation-test
  (k/for [(k/* item) (k/& some-integers)
          index (az/range 0)]
    (k/= @item (k/intCast index)))
  (try (testing/expectEqual 10 (az/get some-integers 10)))
  (try (testing/expectEqual 99 (az/get some-integers 99))))

;; Array concatenation works if the values are known at compile time.
(az/defconst part-one (az/array [1 2 3 4] :i32))
(az/defconst part-two (az/array [5 6 7 8] :i32))
(az/defconst all-of-it (k/++ part-one part-two))

(az/defcomptime concatenated-array
  (debug/assert
   (mem/eql :i32 (k/& all-of-it)
            (k/& (az/array [1 2 3 4 5 6 7 8] :i32)))))

;; Remember that string literals are arrays.
(az/defconst hello "hello")
(az/defconst world "world")
(az/defconst hello-world (k/++ hello " " world))

(az/defcomptime concatenated-string
  (debug/assert (mem/eql :u8 hello-world "hello world")))

;; ** does repeating patterns.
(az/defconst pattern (k/** "ab" 3))

(az/defcomptime repeated-string
  (debug/assert (mem/eql :u8 pattern "ababab")))

;; Initialize an array to zero.
(az/defconst all-zero (k/** (az/array [0] :u16) 10))

(az/defcomptime zero-initialization
  (debug/assert (k/== (:len all-zero) 10))
  (debug/assert (k/== (az/get all-zero 5) 0)))

(az/defstruct Point
  [[:x :i32]
   [:y :i32]])

;; Use compile-time code to initialize an array.
(az/defvar fancy-array
  (az/with-block :init
    (let [initial-value (k/var k/undefined [:array 10 Point])]
      (k/for [(k/* point) (k/& initial-value)
              index (az/range 0)]
        (k/= @point (Point {:x (k/intCast index)
                            :y (k/intCast (k/* index 2))})))
      (k/break :init initial-value))))

(az/deftest compile-time-array-test
  (try (testing/expectEqual 4 (az/get-in fancy-array [4 :x])))
  (try (testing/expectEqual 8 (az/get-in fancy-array [4 :y]))))

(az/defn- make-point Point
  [[x :i32]]
  (Point {:x x :y (k/* x 2)}))

;; Call a function to initialize an array.
(az/defvar more-points (k/** (az/array [(make-point 3)] Point) 10))

(az/deftest function-array-test
  (try (testing/expectEqual 3 (az/get-in more-points [4 :x])))
  (try (testing/expectEqual 6 (az/get-in more-points [4 :y])))
  (try (testing/expectEqual 10 (:len more-points))))

(comment
  (array-iteration-test)
  (array-mutation-test)
  (compile-time-array-test)
  (function-array-test))
