(ns learn.example.test-structs
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

;; Ordinary structs guarantee field alignment, but leave layout to the compiler.
(az/defstruct Point [[:x :f32] [:y :f32]])
(az/defconst origin Point {:x 0.12 :y 0.34})

(az/defstruct Vec3
  [[:x :f32]
   [:y :f32]
   [:z :f32]
   (az/fn init Vec3
     [[x :f32] [y :f32] [z :f32]]
     (Vec3 {:x x :y y :z z}))
   (az/fn dot :f32
     [[self Vec3] [other Vec3]]
     (k/+ (k/* (:x self) (:x other))
        (k/* (:y self) (:y other))
        (k/* (:z self) (:z other))))])

(az/deftest dot-product-test
  (let [horizontal ((:init Vec3) 1.0 0.0 0.0)
        vertical ((:init Vec3) 0.0 1.0 0.0)]
    (try (testing/expectEqual 0.0 ((:dot horizontal) vertical)))
    ;; Method syntax and the equivalent namespaced function call are identical.
    (try (testing/expectEqual 0.0 ((:dot Vec3) horizontal vertical)))))

(az/defstruct Empty
  [[:PI {:const 3.14} :_]])

(az/deftest namespaced-constant-test
  (try (testing/expectEqual 3.14 (:PI Empty)))
  (try (testing/expectEqual 0 (k/sizeOf Empty)))
  (let [empty (Empty {})]
    (k/= :_ empty)))

(az/defn setYBasedOnX :void
  [[x-pointer [:* :f32]] [y :f32]]
  (let [point (k/as (k/fieldParentPtr "x" x-pointer) [:* Point])]
    (k/= (:y point) y)))

(az/deftest field-parent-pointer-test
  (let [point (k/var (Point {:x 0.1234 :y 0.5678}))]
    (setYBasedOnX (k/& (:x point)) 0.9)
    (try (testing/expectEqual 0.9 (:y point)))))

(az/defn LinkedList :type [[T {:attrs #{k/comptime}} :type]]
  (az/struct
    [(az/struct-decl Node {:attrs #{k/pub}}
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
    (try (testing/expectEqual 0 (:len empty-list)))
    (try (testing/expectEqual (LinkedList (az/type :i32)) ListOfInts))
    (let [node (k/var (az/init {:prev nil :next nil :data 1234} (:Node ListOfInts)))
          list (az/init {:first (k/& node) :last (k/& node) :len 1} (LinkedList (az/type :i32)))]
      ;; Pointer field access dereferences automatically.
      (try (testing/expectEqual 1234 (:data (az/unwrap (:first list))))))))

(comment
  (dot-product-test)
  (namespaced-constant-test)
  (field-parent-pointer-test)
  (linked-list-test))
