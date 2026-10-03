(ns aguafria.zig.destructuring-test
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]
            [clojure.test :refer [deftest is]]))

(defn- fixture []
  (let [context (create-ns (gensym "aguafria.destructuring-"))]
    (binding [*ns* context]
      (refer 'clojure.core)
      (require '[aguafria.zig :as a] '[aguafria.keyword :as k]))
    context))

(deftest native-destructuring-in-binding-positions
  (binding [*ns* (fixture)]
    (eval '(a/defstruct Point [[:x :i32] [:y :i32]]))
    (eval '(a/defn sum-point :i32 [[{:keys [x y] :as point} Point]]
             (k/+ x y (:x point))))
    (is (= 12 (a/value (eval '(sum-point (Point {:x 3 :y 6}))))))
    (eval '(a/defn nested :i32 [[data [:array 2 Point]]]
             (let [[{:keys [x]} {other-y :y} :as points] data]
               (k/+ x other-y (k/as (:len points) :i32)))))
    (is (= 9 (a/value (eval '(nested [{:x 3 :y 6} {:x 1 :y 4}])))))
    (eval '(a/defn tail-sum :i32 [[[head & tail :as all] [:slice-const :i32]]]
             (k/+ head (a/get tail 0) (k/as (k/intCast (:len all)) :i32))))
    (is (= 10 (a/value (eval '(tail-sum [3 4 9])))))
    (eval '(a/defn loop-sum :i32 [[points [:slice-const Point]]]
             (let [total (k/var 0 :i32)]
               (k/for [{:keys [x y]} points]
                 (k/+= total (k/+ x y)))
               total)))
    (is (= 12 (a/value (eval '(loop-sum [{:x 3 :y 6} {:x 1 :y 2}])))))
    (eval '(a/defn pointer-loop :i32 []
             (let [points (k/var (a/array [(Point {:x 3 :y 6})] Point))]
               (k/for [(k/* {:keys [x] :as point}) (k/& points)]
                 (k/= (:y point) x))
               (a/get-in points [0 :y]))))
    (is (= 3 (a/value (eval '(pointer-loop)))))
    (eval '(a/defn optional-point :i32 [[point [:optional Point]]]
             (a/if-capture {:payload [{:keys [x y]}]} point (k/+ x y) 0)))
    (is (= 9 (a/value (eval '(optional-point {:x 3 :y 6})))))
    (is (= 0 (a/value (eval '(optional-point nil)))))
    (eval '(a/defconst Choice (a/union {:attrs #{k/enum}}
                                [[:point Point] [:empty :void]])))
    (eval '(a/defn switch-point :i32 [[choice Choice]]
             (k/switch choice
               (case [:.point] [{:keys [x y]}] (k/+ x y))
               (case [:.empty] 0))))
    (is (= 9 (a/value (eval '(switch-point (Choice {:point {:x 3 :y 6}}))))))
    (eval '(a/defn while-point :i32 []
             (let [point (k/var (k/as (Point {:x 3 :y 6}) [:optional Point]))
                   total (k/var 0 :i32)]
               (a/while-loop {:payload [{:keys [x y]}]} point
                 (k/+= total (k/+ x y))
                 (k/= point nil))
               total)))
    (is (= 9 (a/value (eval '(while-point)))))
    (eval '(a/defstruct Operations
             [(a/fn sum :i32 [[{:keys [x y]} Point]] (k/+ x y))]))
    (eval '(a/defn method-sum :i32 []
             ((:sum Operations) (Point {:x 3 :y 6}))))
    (is (= 9 (a/value (eval '(method-sum)))))))

(deftest destructuring-evaluates-once-and-binds-values
  (binding [*ns* (fixture)]
    (eval '(a/defstruct Point [[:x :i32] [:y :i32]]))
    (eval '(a/defvar calls :i32 0))
    (eval '(a/defn make-point Point []
             (k/+= calls 1)
             (Point {:x 3 :y 6})))
    (eval '(a/defn inspect-once :i32 []
             (let [{:keys [x y]} (make-point)]
               (k/+ x y calls))))
    (is (= 10 (a/value (eval '(inspect-once)))))
    (eval '(a/defn snapshot :i32 []
             (let [point (k/var (Point {:x 3 :y 6}))
                   {:keys [x]} point]
               (k/= (:x point) 10)
               x)))
    (is (= 3 (a/value (eval '(snapshot)))))
    (is (= [3 6] (mapv a/value (eval '(let [{:keys [x y]} (make-point)] [x y])))))
    (is (= 9 (eval '(let [total (k/var 0 :i32)
                         points (a/array [(Point {:x 3 :y 6})] Point)]
                     (k/for [{:keys [x y]} points] (k/+= total (k/+ x y)))
                     (a/value total)))))))
