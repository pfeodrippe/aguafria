(ns aguafria.zig.comptime-macro-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.math :as math]
            [aguafria.zig :as a]))

;; Its standalone initializer is a different demand from reading the constant.
(a/defconst infinity (math/inf :f32))
(a/defconst big (as (<< 1 40) :f64))

(a/defn phase-answer :i32 [[n :i32]]
  (if (inComptime) (+ n 1) 0))

;; Discovery prepares this exact inner expression without invoking the body.
(a/defn prepared-site :void []
  (= :_ (comptime (phase-answer 41))))

;; A statement-position scope must be observed as a whole too. Observing
;; only its inner operations cannot prepare the JVM macro's scoped adapter.
(a/defn prepared-statement :void []
  (comptime
   (let [x (k/var 1 :i32)
         ptr (k/& x)]
     (+= @ptr 1)
     (+= x 1)
     (if (!= @ptr 3) (compileError "comptime pointer mutation failed")))))

(comment
  (a/value (comptime (phase-answer 41))))
