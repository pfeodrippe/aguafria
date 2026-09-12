(ns aguafria.zig.set-many-test
  (:require [aguafria.zig :as az]
            [aguafria.zig.emitter :as emit]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn expand-once [form]
  (binding [*ns* (the-ns 'aguafria.zig.set-many-test)]
    (macroexpand-1 form)))

(az/defn sequential-result
  :- :usize
  []
  (let [^{:var :usize} x 2
        ^{:var :usize} y 0
        ^{:var [:array 3 :usize]} values [0 0 0]]
    (az/set-many!
      x (+ x 1)
      y (* x 2)
      x (- x 2)
      (az/index values x) y
      (az/index values (+ x 1)) (+ (az/index values x) 5))
    (az/index values 2)))

(deftest native-sequential-target-and-value-evaluation
  (is (= 11 (sequential-result))))

(deftest ordered-assignment-expansion
  (is (= '(do (set! x (+ x 1))
              (set! y (* x 2))
              (set! (az/index buffer x) y))
         (expand-once '(az/set-many!
           x (+ x 1)
           y (* x 2)
           (az/index buffer x) y))))
  (is (= '(do) (expand-once '(az/set-many!)))))

(deftest malformed-assignments
  (doseq [form ['(az/set-many! x 1 y)
               '(az/set-many! {x 1})
               '(az/set-many! (x 1))]]
    (is (thrown? clojure.lang.Compiler$CompilerException (expand-once form)))))

(deftest assignments-emit-in-order
  (let [declaration
        (emit/prepare-declaration (the-ns 'aguafria.zig.set-many-test)
          {:kind :fn :name 'ordered :args [] :return :i32
           :body '[(let [^{:var :i32} x 2
                         ^{:var :i32} y 0]
                     (az/set-many!
                       x (+ x 3)
                       y (* x 2)
                       x (+ y 1))
                     x)]})
        source (emit/emit-declaration declaration)
        first-write (str/index-of source "x = (x + 3);")
        second-write (str/index-of source "y = (x * 2);")
        third-write (str/index-of source "x = (y + 1);")]
    (is (every? some? [first-write second-write third-write]) source)
    (when (every? some? [first-write second-write third-write])
      (is (< first-write second-write third-write)))))
