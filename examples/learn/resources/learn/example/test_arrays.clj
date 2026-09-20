(ns learn.example.test-arrays
  (:require [aguafria.keyword :as ak]
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
  (debug/assert (mem/eql :u8 (& message) (& alt-message))))

;; Get the size of an array.
(az/defcomptime message-length
  (debug/assert (ak/== (az/field message :len) 5)))

;; A string literal is a single-item pointer to an array.
(az/defconst same-message "hello")

(az/defcomptime matching-string
  (debug/assert (mem/eql :u8 (& message) same-message)))

(az/deftest array-iteration-test
  (let [sum (ak/var 0 :usize)]
    (for [byte message]
      (ak/+= sum byte))
    (try (testing/expectEqual (+ \h \e (* \l 2) \o) sum))))

;; Modifiable array.
(az/defvar some-integers [:array 100 :i32] ak/undefined)

(az/deftest array-mutation-test
  (for [[(az/pointer-capture item) (& some-integers)]
        [index (az/op ".." 0)]]
    (ak/= @item (ak/intCast index)))
  (try (testing/expectEqual 10 (az/index some-integers 10)))
  (try (testing/expectEqual 99 (az/index some-integers 99))))

;; Array concatenation works if the values are known at compile time.
(az/defconst part-one (az/array-init [1 2 3 4] [:array :_ :i32]))
(az/defconst part-two (az/array-init [5 6 7 8] [:array :_ :i32]))
(az/defconst all-of-it (az/op "++" part-one part-two))

(az/defcomptime concatenated-array
  (debug/assert
   (mem/eql :i32 (& all-of-it)
            (& (az/array-init [1 2 3 4 5 6 7 8] [:array :_ :i32])))))

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
  (debug/assert (ak/== (az/field all-zero :len) 10))
  (debug/assert (ak/== (az/index all-zero 5) 0)))

(az/defstruct Point
  [[:x :i32]
   [:y :i32]])

;; Use compile-time code to initialize an array.
(az/defvar fancy-array
  (az/labeled-block init
    (let [initial-value (ak/var ak/undefined [:array 10 Point])]
      (for [[(az/pointer-capture point) (& initial-value)]
            [index (az/op ".." 0)]]
        (ak/= @point (Point {:x (ak/intCast index)
                             :y (ak/intCast (* index 2))})))
      (ak/break init initial-value))))

(az/deftest compile-time-array-test
  (try (testing/expectEqual 4 (az/field (az/index fancy-array 4) :x)))
  (try (testing/expectEqual 8 (az/field (az/index fancy-array 4) :y))))

;; Call a function to initialize an array.
(az/defvar more-points
  (az/op "**" (az/array-init [(make-point 3)] [:array :_ Point]) 10))

(az/defn- make-point Point
  [[x :i32]]
  (Point {:x x :y (* x 2)}))

(az/deftest function-array-test
  (try (testing/expectEqual 3 (az/field (az/index more-points 4) :x)))
  (try (testing/expectEqual 6 (az/field (az/index more-points 4) :y)))
  (try (testing/expectEqual 10 (az/field more-points :len))))

(comment
  (array-iteration-test)
  (array-mutation-test)
  (compile-time-array-test)
  (function-array-test))
