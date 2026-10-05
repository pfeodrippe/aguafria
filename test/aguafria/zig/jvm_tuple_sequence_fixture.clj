(ns aguafria.zig.jvm-tuple-sequence-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]))

(a/defn tuple-result (a/struct [(a/tuple-field-decl :u32)
                               (a/tuple-field-decl :u32)])
  [[numerator :u32] [denominator :u32]]
  [(k// numerator denominator) (k/% numerator denominator)])

(a/defn returned-body :void []
  (let [[quotient remainder] (tuple-result 10 3)]
    (debug/print "quotient = {}\n" [quotient])
    (debug/print "remainder = {}\n" [remainder])))

(a/defn scoped-body :void []
  (let [digits (a/array [3 8 9 0 7 4 1] :i8)
        [minimum maximum]
        (a/with-block :bounds
          (let [minimum (k/var 127 :i8)
                maximum (k/var -128 :i8)]
            (k/for [digit digits]
              (when (k/< digit minimum) (k/= minimum digit))
              (when (k/> digit maximum) (k/= maximum digit)))
            (k/break :bounds [minimum maximum])))]
    (debug/print "minimum = {}\n" [minimum])
    (debug/print "maximum = {}\n" [maximum])))

(a/defn pointer-capture-body :void []
  (let [digits (a/with-block :init
                 (let [digits (k/var (a/array [0 0] :u8))]
                   (k/for [(k/* digit) (k/& digits)]
                     (k/= @digit 1))
                   (k/break :init digits)))]
    (debug/print "digit = {}\n" [(a/get digits 0)])))
