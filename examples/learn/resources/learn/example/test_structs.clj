(ns learn.example.test-structs
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

;; Declare a struct.
;; Zig gives no guarantees about the order of fields and the size of
;; the struct but the fields are guaranteed to be ABI-aligned.
(az/defstruct Point [[:x :f32] [:y :f32]])
;; Declare an instance of a struct.
(az/defconst p Point {:x 0.12 :y 0.34})

;; Functions in the struct's namespace can be called with dot syntax.
(az/defstruct Vec3
  [[:x :f32]
   [:y :f32]
   [:z :f32]
   (az/fn init Vec3
     [[x :f32] [y :f32] [z :f32]]
     (Vec3 {:x x :y y :z z}))
   (az/fn dot :f32
     [[self Vec3] [other Vec3]]
     (let [{:keys [x y z]} self
           {ox :x oy :y oz :z} other]
       (k/+ (k/* x ox) (k/* y oy) (k/* z oz))))])

(az/deftest dot-product
  (let [v1 ((:init Vec3) 1.0 0.0 0.0)
        v2 ((:init Vec3) 0.0 1.0 0.0)]
    (try (testing/expectEqual 0.0 ((:dot v1) v2)))
    ;; Other than being available to call with dot syntax, struct methods are
    ;; not special. You can reference them as any other declaration inside
    ;; the struct:
    (try (testing/expectEqual 0.0 ((:dot Vec3) v1 v2)))))

;; Structs can have declarations.
;; Structs can have 0 fields.
(az/defstruct Empty
  [[:PI {:const 3.14} :_]])

(az/deftest struct-namespaced-variable
  (try (testing/expectEqual 3.14 (:PI Empty)))
  (try (testing/expectEqual 0 (k/sizeOf Empty)))
  ;; Empty structs can be instantiated the same as usual.
  (let [does-nothing (Empty {})]
    (k/= :_ does-nothing)))

;; Struct field order is determined by the compiler, however, a base pointer
;; can be computed from a field pointer:
(az/defn setYBasedOnX :void
  [[x [:* :f32]] [y :f32]]
  (let [point (k/as (k/fieldParentPtr "x" x) [:* Point])]
    (k/= (:y point) y)))

(az/deftest field-parent-pointer
  (let [point (k/var (Point {:x 0.1234 :y 0.5678}))]
    (setYBasedOnX (k/& (:x point)) 0.9)
    (try (testing/expectEqual 0.9 (:y point)))))

;; Structs can be returned from functions.
(az/defn LinkedList :type [[T {:attrs #{k/comptime}} :type]]
  (az/struct
   [(az/struct-decl Node {:attrs #{k/pub}}
                    [[:prev [:optional [:* Node]]]
                     [:next [:optional [:* Node]]]
                     [:data T]])
    [:first [:optional [:* Node]]]
    [:last [:optional [:* Node]]]
    [:len :usize]]))

(az/deftest linked-list
  ;; Functions called at compile-time are memoized.
  (try (testing/expectEqual (LinkedList (az/type :i32)) (LinkedList (az/type :i32))))
  (let [list (az/init {:first nil :last nil :len 0} (LinkedList (az/type :i32)))]
    (try (testing/expectEqual 0 (:len list))))
  ;; Since types are first class values you can instantiate the type
  ;; by assigning it to a variable:
  (let [ListOfInts (LinkedList (az/type :i32))]
    (try (testing/expectEqual (LinkedList (az/type :i32)) ListOfInts))
    (let [node (k/var (az/init {:prev nil :next nil :data 1234} (:Node ListOfInts)))
          list2 (az/init {:first (k/& node) :last (k/& node) :len 1} (LinkedList (az/type :i32)))]
      ;; When using a pointer to a struct, fields can be accessed directly,
      ;; without explicitly dereferencing the pointer.
      ;; So you can do
      (try (testing/expectEqual 1234 (:data (az/unwrap (:first list2)))))
      ;; instead of try expectEqual(1234, list2.first.?.*.data);
      )))

(comment
  (dot-product)
  (struct-namespaced-variable)
  (field-parent-pointer)
  (linked-list))
