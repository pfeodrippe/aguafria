(ns clj-kondo-valid
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as a]))

(a/defconst capacity :usize 8)

(a/defvar counter :usize 0)

(a/defstruct Point
  "A typed fixture value."
  [[:x :f32]
   [:y {:doc "Vertical component."} :f32]])

(a/defenum Color
  [:red
   [:really-red {:zig/name "@\"really red\""}]])

(a/defn ShortList :type
  [[T {:zig/prefix "comptime"} :type]
   [length {:zig/prefix "comptime"} :usize]]
  (a/struct
    [[:items [:array length T]]
     (a/fn-decl first-item T
       [[items [:array length T]]]
       (a/index items 0))]))

(a/defconst TaggedValue
  (a/union {:enum? true} [[:value :i32] [:empty :void]]))

(a/defconst Handle (a/opaque []))

(a/defconst Status (a/enum [:ready [:waiting {:doc "Pending"} 2]]))

(def color-type Color)

(a/defimport fixture-module "fixture" [fixture-member])

(a/defraw fixture-raw "const fixture_raw: u8 = 1;")

(a/deffield fixture-field :u8 1)

(a/defcomptime fixture-comptime
  (when false
    (ak/compileError "unreachable fixture branch")))

(a/defextern puts :c_int
  [[message [:c-pointer :c_char]]])

(a/defexternvar errno :- :c_int)

(a/defn- add-components :f32
  [[point Point]]
  (+ (a/field point :x)
     (a/field point :y)))

(a/defn inspect-point :f32
  "Inspect a typed value."
  {:attrs #{:public}}
  [[point Point]
   [opaque [:optional [:c-pointer :anyopaque]]]]
  (let [typed (a/cast opaque [:c-pointer :u8])]
    (set! counter (+ counter 1))
    (+ (add-components point)
       (ak/as (a/index typed 0) :f32))))

(a/defconst structural-values
  (a/container
    {:kind :struct}
    [(a/field-decl value :u8 1)
     (a/enum-field-decl ready 0)
     (a/field-decl object :u8
       (a/object [[:value capacity]]))]))

(a/deftest clj-kondo-fixture
  "A named Zig test and an inspectable Clojure Var."
  (inspect-point (Point {:x 1.0 :y 2.0}) ak/null))

(def fixture-test-var #'clj-kondo-fixture)

(a/deftest another-named-test
  capacity)
