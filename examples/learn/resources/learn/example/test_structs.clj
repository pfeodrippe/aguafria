(ns learn.example.test-structs
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

;; Declare a struct.
;; Zig gives no guarantees about the order of fields and the size of
;; the struct but the fields are guaranteed to be ABI-aligned.
(a/defstruct Point [[:x :f32] [:y :f32]])
;; Declare an instance of a struct.
(a/defconst p Point {:x 0.12 :y 0.34})

;; Functions in the struct's namespace can be called with dot syntax.
(a/defstruct Vec3
  [[:x :f32]
   [:y :f32]
   [:z :f32]
   (a/fn init Vec3
     [[x :f32] [y :f32] [z :f32]]
     (Vec3 {:x x :y y :z z}))
   (a/fn dot :f32
     [[self Vec3] [other Vec3]]
     (let [{:keys [x y z]} self
           {ox :x oy :y oz :z} other]
       (+ (* x ox) (* y oy) (* z oz))))])

(a/deftest dot-product
  (let [v1 ((:init Vec3) 1.0 0.0 0.0)
        v2 ((:init Vec3) 0.0 1.0 0.0)]
    (k/try (testing/expectEqual 0.0 ((:dot v1) v2)))
    ;; Other than being available to call with dot syntax, struct methods are
    ;; not special. You can reference them as any other declaration inside
    ;; the struct:
    (k/try (testing/expectEqual 0.0 ((:dot Vec3) v1 v2)))))

;; Structs can have declarations.
;; Structs can have 0 fields.
(a/defstruct Empty
  [[:PI {:const 3.14} :_]])

(a/deftest struct-namespaced-variable
  (k/try (testing/expectEqual 3.14 (:PI Empty)))
  (k/try (testing/expectEqual 0 (sizeOf Empty)))
  ;; Empty structs can be instantiated the same as usual.
  (let [does-nothing (Empty {})]
    (= :_ does-nothing)))

;; Struct field order is determined by the compiler, however, a base pointer
;; can be computed from a field pointer:
(a/defn setYBasedOnX :void
  [[x [:* :f32]] [y :f32]]
  (let [point (as (fieldParentPtr "x" x) [:* Point])]
    (= (:y point) y)))

(a/deftest field-parent-pointer
  (let [point (k/var (Point {:x 0.1234 :y 0.5678}))]
    (setYBasedOnX (k/& (:x point)) 0.9)
    (k/try (testing/expectEqual 0.9 (:y point)))))

;; Structs can be returned from functions.
(a/defn LinkedList :type [[T {:attrs #{comptime}} :type]]
  (a/struct
   [(a/struct-decl Node {:attrs #{pub}}
                   [[:prev [:optional [:* Node]]]
                    [:next [:optional [:* Node]]]
                    [:data T]])
    [:first [:optional [:* Node]]]
    [:last [:optional [:* Node]]]
    [:len :usize]]))

(a/deftest linked-list
  ;; Functions called at compile-time are memoized.
  (k/try (testing/expectEqual (LinkedList :i32) (LinkedList :i32)))
  (let [list (a/init {:first nil :last nil :len 0} (LinkedList :i32))]
    (k/try (testing/expectEqual 0 (:len list))))
  ;; Since types are first class values you can instantiate the type
  ;; by assigning it to a variable:
  (let [ListOfInts (LinkedList :i32)]
    (k/try (testing/expectEqual (LinkedList :i32) ListOfInts))
    (let [node (k/var (a/init {:prev nil :next nil :data 1234} (:Node ListOfInts)))
          list2 (a/init {:first (k/& node) :last (k/& node) :len 1} (LinkedList :i32))]
      ;; When using a pointer to a struct, fields can be accessed directly,
      ;; without explicitly dereferencing the pointer.
      ;; So you can do
      (k/try (testing/expectEqual 1234 (:data (a/unwrap (:first list2)))))
      ;; instead of try expectEqual(1234, list2.first.?.*.data);
      )))

(comment
  (dot-product)
  (struct-namespaced-variable)
  (field-parent-pointer)
  (linked-list))
