(ns aguafria.zig.describe-test
  (:require [aguafria.keyword :as k]
            [aguafria.std :as std]
            [aguafria.std.ArrayList :as al]
            [aguafria.zig :as az]
            [aguafria.zig.value :as value]
            [clojure.test :refer [deftest is]]))

(az/defstruct Point
  "A documented point."
  [[:x {:doc "Horizontal position."} :i32]
   [:limit {:const 10} :i32]
   [:counter {:var 0} :i32]
   (az/fn origin Point
     "Construct the origin."
     []
     (Point {:x 0}))
   (az/fn with-x Point
     "Construct a point with x."
     [[x :i32]]
     (Point {:x x}))
   (az/fn- hidden :void [])])

(defn- named [members name]
  (first (filter #(= name (:name %)) members)))

(az/defconst small-number :u8 7)

(az/defn- make-point Point
  [[x :i32]]
  (Point {:x x}))

(az/defconst message (az/array [\h \e \l \l \o] :u8))
(az/defconst same-message "hello")

(deftest private-functions-are-inspected-in-their-own-module
  (doseq [target [#'make-point make-point]]
    (let [description (az/describe target)]
      (is (= :fn (:kind description)))
      (is (= [{:type "i32" :generic? false}] (:parameters description)))
      (is (re-find #"Point$" (:return description)))))
  (is (false? (:public? (:aguafria/declaration (meta #'make-point)))))
  (is (not (re-find #"pub fn make_point" (az/source 'aguafria.zig.describe-test)))))

(deftest string-literal-pointers-remain-distinct-from-arrays
  (is (= "[5]u8" (:type (az/describe message))))
  (is (= "*const [5:0]u8" (:type (az/describe same-message))))
  (is (= [104 101 108 108 111] (az/value message)))
  (is (value/zig-pointer? (az/value same-message)))
  (is (= [104 101 108 108 111] (az/deref same-message)))
  (is (= "hello" (az/slice same-message 0 5))))

(deftest arrays-slices-and-scalars
  (with-open [array (az/array [1 2 3] :u8)
              slice (k/as [1 2 3] [:slice :u8])]
    (let [a (az/describe array)
          s (az/describe slice)]
      (is (= :array (:kind a)))
      (is (= "[3]u8" (:type a)))
      (is (= [{:name :len :type "usize"}] (:fields a)))
      (is (= #{:ptr :len} (set (map :name (:fields s)))))
      (is (= "usize" (:type (named (:fields s) :len))))
      (is (empty? (:functions a)))
      (is (not= (:type a) (:type s)))))
  (is (empty? (:fields (az/describe 42))))
  (is (= "u8" (:type (az/describe #'small-number))))
  (is (empty? (:functions (az/describe true)))))

(deftest declared-types-and-docs
  (let [description (az/describe Point)
        field (named (:fields description) :x)
        origin (named (:functions description) :origin)]
    (is (= :struct (:kind description)))
    (is (= :const (:declaration-kind (named (:constants description) :limit))))
    (is (= :var (:declaration-kind (named (:variables description) :counter))))
    (is (= "A documented point." (:doc description)))
    (is (= "Horizontal position." (:doc field)))
    (is (= "i32" (:type field)))
    (is (= "Construct the origin." (:doc origin)))
    (is (= "Construct a point with x."
           (:doc (named (:functions description) :with_x))))
    (is (= [] (:parameters origin)))
    (is (string? (:return origin)))
    (is (nil? (named (:functions description) :hidden)))
    (is (= (:fields description) (:fields (az/describe #'Point)))))
  (with-open [point (Point {:x 12})]
    (is (= "Horizontal position." (:doc (first (:fields (az/describe point))))))))

(deftest imported-generic-types-and-accessor-discovery
  (let [type (std/ArrayList :u21)
        description (az/describe type)
        items (named (:fields description) :items)
        append (named (:functions description) :append)]
    (is (= 'aguafria.std.ArrayList/-items (:accessor items)))
    (is (= "[]u21" (:type items)))
    (is (seq (:doc items)))
    (is (= 'aguafria.std.ArrayList/append (:var append)))
    (is (= 3 (count (:parameters append))))
    (is (= "u21" (get-in append [:parameters 2 :type])))
    (is (seq (:doc append)))
    (is (= :type (:declaration-kind (named (:types description) :Slice))))
    (with-open [list (k/var :.empty type)]
      (is (= (:fields description) (:fields (az/describe list))))
      (is (= 0 (az/field ((requiring-resolve (:accessor items)) list) :len))))))

(deftest no-storage-access-or-member-invocation
  (let [unreadable (value/native-value
                    {:type [:array 5 :u8] :kind :var}
                    #(throw (ex-info "Must not read storage" {})))]
    (is (= :pending (:status (value/info unreadable))))
    (is (= [{:name :len :type "usize"}] (:fields (az/describe unreadable))))
    (is (= :pending (:status (value/info unreadable)))))
  (let [null-pointer (value/->ZigPointer 0 [:* :u32])]
    (is (= :pointer (:kind (az/describe null-pointer)))))
  (is (= :fn (:kind (az/describe #'al/append))))
  (is (:requires-receiver? (az/describe #'al/append))))
