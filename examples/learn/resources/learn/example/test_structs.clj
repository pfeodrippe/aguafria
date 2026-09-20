(ns learn.example.test-structs
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

;; Ordinary structs guarantee field alignment, but leave layout to the compiler.
(az/defstruct Point [[:x :f32] [:y :f32]])
(az/defconst origin Point {:x 0.12 :y 0.34})

(az/defstruct Vec3
  [[:x :f32]
   [:y :f32]
   [:z :f32]
   (az/fn-decl init Vec3 {:attrs #{:public}}
     [[x :f32] [y :f32] [z :f32]]
     (ak/return (az/init {:x x :y y :z z} Vec3)))
   (az/fn-decl dot :f32 {:attrs #{:public}}
     [[self Vec3] [other Vec3]]
     (ak/return (+ (* (az/field self :x) (az/field other :x))
                   (* (az/field self :y) (az/field other :y))
                   (* (az/field self :z) (az/field other :z)))))])

(az/deftest dot-product-test
  (let [horizontal ((az/field Vec3 :init) 1.0 0.0 0.0)
        vertical ((az/field Vec3 :init) 0.0 1.0 0.0)]
    (try (testing/expectEqual 0.0 ((az/field horizontal :dot) vertical)))
    ;; Method syntax and the equivalent namespaced function call are identical.
    (try (testing/expectEqual 0.0 ((az/field Vec3 :dot) horizontal vertical)))))

(az/defstruct Empty
  [(az/const-decl PI {:attrs #{:public}} 3.14)])

(az/deftest namespaced-constant-test
  (try (testing/expectEqual 3.14 (az/field Empty :PI)))
  (try (testing/expectEqual 0 (ak/sizeOf Empty)))
  (let [empty (Empty {})]
    (ak/= :_ empty)))

(az/defn setYBasedOnX :void
  [[x-pointer [:* :f32]] [y :f32]]
  (let [point (ak/as (ak/fieldParentPtr "x" x-pointer) [:* Point])]
    (ak/= (az/field point :y) y)))

(az/deftest field-parent-pointer-test
  (let [point (ak/var (Point {:x 0.1234 :y 0.5678}))]
    (setYBasedOnX (& (az/field point :x)) 0.9)
    (try (testing/expectEqual 0.9 (az/field point :y)))))

(az/defn LinkedList :type [[T {:zig/prefix "comptime"} :type]]
  (az/struct
    [(az/struct-decl Node {:attrs #{:public}}
       [[:prev [:optional [:* Node]]]
        [:next [:optional [:* Node]]]
        [:data T]])
     [:first [:optional [:* Node]]]
     [:last [:optional [:* Node]]]
     [:len :usize]]))

(az/deftest linked-list-test
  ;; Repeated calls at compile time return the same memoized type.
  (try (testing/expectEqual (LinkedList (az/type :i32)) (LinkedList (az/type :i32))))
  (let [empty-list (az/init {:first nil :last nil :len 0} (LinkedList (az/type :i32)))
        ListOfInts (LinkedList (az/type :i32))]
    (try (testing/expectEqual 0 (az/field empty-list :len)))
    (try (testing/expectEqual (LinkedList (az/type :i32)) ListOfInts))
    (let [node (ak/var (az/init {:prev nil :next nil :data 1234} (az/field ListOfInts :Node)))
          list (az/init {:first (& node) :last (& node) :len 1} (LinkedList (az/type :i32)))]
      ;; Pointer field access dereferences automatically.
      (try (testing/expectEqual 1234 (az/field (az/unwrap (az/field list :first)) :data))))))

(comment
  (dot-product-test)
  (namespaced-constant-test)
  (field-parent-pointer-test)
  (linked-list-test))
