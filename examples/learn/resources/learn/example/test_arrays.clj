(ns learn.example.test-arrays
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.std.mem :as mem]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

;; Array literal.
(az/defconst message
  (az/array-init [\h \e \l \l \o] [:array :_ :u8]))

;; Alternative initialization using result location.
(az/defconst alt-message [:array 5 :u8] [\h \e \l \l \o])

(az/defcomptime matching-initializers
  (debug/assert (mem/eql :u8 (k/& message) (k/& alt-message))))

;; Get the size of an array.
(az/defcomptime message-length
  (debug/assert (k/== (az/field message :len) 5)))

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
  (k/for [[(az/pointer-capture item) (k/& some-integers)]
        [index (az/op ".." 0)]]
    (k/= @item (k/intCast index)))
  (try (testing/expectEqual 10 (az/index some-integers 10)))
  (try (testing/expectEqual 99 (az/index some-integers 99))))

;; Array concatenation works if the values are known at compile time.
(az/defconst part-one (az/array-init [1 2 3 4] [:array :_ :i32]))
(az/defconst part-two (az/array-init [5 6 7 8] [:array :_ :i32]))
(az/defconst all-of-it (az/op "++" part-one part-two))

(az/defcomptime concatenated-array
  (debug/assert
   (mem/eql :i32 (k/& all-of-it)
            (k/& (az/array-init [1 2 3 4 5 6 7 8] [:array :_ :i32])))))

;; Remember that string literals are arrays.
(az/defconst hello "hello")
(az/defconst world "world")
(az/defconst hello-world (az/op "++" hello " " world))

(az/defcomptime concatenated-string
  (debug/assert (mem/eql :u8 hello-world "hello world")))

;; ** does repeating patterns.
(az/defconst pattern (az/op "**" "ab" 3))

(az/defcomptime repeated-string
  (debug/assert (mem/eql :u8 pattern "ababab")))

;; Initialize an array to zero.
(az/defconst all-zero (az/op "**" (az/array-init [0] [:array :_ :u16]) 10))

(az/defcomptime zero-initialization
  (debug/assert (k/== (az/field all-zero :len) 10))
  (debug/assert (k/== (az/index all-zero 5) 0)))

(az/defstruct Point
  [[:x :i32]
   [:y :i32]])

;; Use compile-time code to initialize an array.
(az/defvar fancy-array (az/labeled-block init
    (let [initial-value (k/var k/undefined [:array 10 Point])]
      (k/for [[(az/pointer-capture point) (k/& initial-value)]
            [index (az/op ".." 0)]]
        (k/= @point (Point {:x (k/intCast index)
                             :y (k/intCast (k/* index 2))})))
      (k/break init initial-value))))

(az/deftest compile-time-array-test
  (try (testing/expectEqual 4 (az/field (az/index fancy-array 4) :x)))
  (try (testing/expectEqual 8 (az/field (az/index fancy-array 4) :y))))

(az/defn- make-point Point
  [[x :i32]]
  (Point {:x x :y (k/* x 2)}))

;; Call a function to initialize an array.
(az/defvar more-points (az/op "**" (az/array-init [(make-point 3)] [:array :_ Point]) 10))

(az/deftest function-array-test
  (try (testing/expectEqual 3 (az/field (az/index more-points 4) :x)))
  (try (testing/expectEqual 6 (az/field (az/index more-points 4) :y)))
  (try (testing/expectEqual 10 (az/field more-points :len))))

(comment
  (array-iteration-test)
  (array-mutation-test)
  (compile-time-array-test)
  (function-array-test))
