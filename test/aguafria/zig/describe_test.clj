(ns aguafria.zig.describe-test
  (:require [aguafria.keyword :as k]
            [aguafria.std :as std]
            [aguafria.std.ArrayList :as al]
            [aguafria.zig :as a]
            [aguafria.zig.value :as value]
            [clojure.test :refer [deftest is]]))

(a/defstruct Point
  "A documented point."
  [[:x {:doc "Horizontal position."} :i32]
   [:limit {:const 10} :i32]
   [:counter {:var 0} :i32]
   (a/fn origin Point
     "Construct the origin."
     []
     (Point {:x 0}))
   (a/fn with-x Point
     "Construct a point with x."
     [[x :i32]]
     (Point {:x x}))
   (a/fn- hidden :void [])])

(defn- named [members name]
  (first (filter #(= name (:name %)) members)))

(a/defconst small-number :u8 7)

(a/defn- make-point Point
  [[x :i32]]
  (Point {:x x}))

(a/defconst message (a/array [\h \e \l \l \o] :u8))
(a/defconst same-message "hello")

(deftest private-functions-are-inspected-in-their-own-module
  (doseq [target [#'make-point make-point]]
    (let [description (a/describe target)]
      (is (= :fn (:kind description)))
      (is (= [{:type "i32" :generic? false}] (:parameters description)))
      (is (re-find #"Point$" (:return description)))))
  (is (false? (:public? (:aguafria/declaration (meta #'make-point)))))
  (is (not (re-find #"pub fn make_point" (a/source 'aguafria.zig.describe-test)))))

(deftest string-literal-pointers-remain-distinct-from-arrays
  (is (= "[5]u8" (:type (a/describe message))))
  (is (= "*const [5:0]u8" (:type (a/describe same-message))))
  (is (= [104 101 108 108 111] (a/value message)))
  (is (value/zig-pointer? (a/value same-message)))
  (is (= [104 101 108 108 111] (a/deref same-message)))
  (is (= "hello" (a/slice same-message 0 5))))

(deftest arrays-slices-and-scalars
  (with-open [array (a/array [1 2 3] :u8)
              slice (k/as [1 2 3] [:slice :u8])]
    (let [a (a/describe array)
          s (a/describe slice)]
      (is (= :array (:kind a)))
      (is (= "[3]u8" (:type a)))
      (is (= [{:name :len :type "usize"}] (:fields a)))
      (is (= #{:ptr :len} (set (map :name (:fields s)))))
      (is (= "usize" (:type (named (:fields s) :len))))
      (is (empty? (:functions a)))
      (is (not= (:type a) (:type s)))))
  (is (empty? (:fields (a/describe 42))))
  (is (= "u8" (:type (a/describe #'small-number))))
  (is (empty? (:functions (a/describe true)))))

(deftest declared-types-and-docs
  (let [description (a/describe Point)
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
    (is (= (:fields description) (:fields (a/describe #'Point)))))
  (with-open [point (Point {:x 12})]
    (is (= "Horizontal position." (:doc (first (:fields (a/describe point))))))))

(deftest imported-generic-types-and-accessor-discovery
  (let [type (std/ArrayList :u21)
        description (a/describe type)
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
      (is (= (:fields description) (:fields (a/describe list))))
      (is (= 0 (a/field ((requiring-resolve (:accessor items)) list) :len))))))

(deftest no-storage-access-or-member-invocation
  (let [unreadable (value/native-value
                    {:type [:array 5 :u8] :kind :var}
                    #(throw (ex-info "Must not read storage" {})))]
    (is (= :pending (:status (value/info unreadable))))
    (is (= [{:name :len :type "usize"}] (:fields (a/describe unreadable))))
    (is (= :pending (:status (value/info unreadable)))))
  (let [null-pointer (value/->ZigPointer 0 [:* :u32])]
    (is (= :pointer (:kind (a/describe null-pointer)))))
  (is (= :fn (:kind (a/describe #'al/append))))
  (is (:requires-receiver? (a/describe #'al/append))))
