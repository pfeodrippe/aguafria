(ns clj-kondo-valid
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defconst capacity :usize 8)

(az/defvar counter :usize 0)

(az/defstruct Point
  "A typed fixture value."
  [[:x :f32]
   [:y {:doc "Vertical component."} :f32]])

(az/defimport fixture-module "fixture" [fixture-member])

(az/defraw fixture-raw "const fixture_raw: u8 = 1;")

(az/deffield fixture-field :u8 1)

(az/defcomptime fixture-comptime
  (when false
    (ak/compileError "unreachable fixture branch")))

(az/defextern puts :- :c_int
  [[message [:c-pointer :c_char]]])

(az/defexternvar errno :- :c_int)

(az/defn- add-components
  :-
  :f32
  [[point Point]]
  (+ (az/field point :x)
     (az/field point :y)))

(az/defn inspect-point
  {:attrs #{:public}}
  :-
  :f32
  [[point Point]
   [opaque [:optional [:c-pointer :anyopaque]]]]
  (let [typed (az/cast opaque [:c-pointer :u8])]
    (set! counter (+ counter 1))
    (+ (add-components point)
       (ak/as :f32 (az/index typed 0)))))

(az/defconst structural-values
  (az/container
   {:kind :struct}
   (az/field-decl value :u8 1)
   (az/enum-field-decl ready 0)
   (az/field-decl object :u8
                  (az/object [[:value capacity]]))))

(az/deftest "clj-kondo fixture"
  (inspect-point (Point {:x 1.0 :y 2.0}) ak/null))
