(ns aguafria.zig.discovery-concat-fields-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defstruct Settings [[:one :u32] [:two :bool]])

(a/deftest never-run
  (a/inline-for [field-name (:field_names (:struct (k/typeInfo Settings)))]
                (k/= :_ (k/++ "prefix." field-name)))
  (k/unreachable))

(a/defstruct WideSettings
  [[:setting_0 :u32] [:setting_1 :u32] [:setting_2 :u32] [:setting_3 :u32]
   [:setting_4 :u32] [:setting_5 :u32] [:setting_6 :u32] [:setting_7 :u32]
   [:setting_8 :u32] [:setting_9 :u32] [:setting_10 :u32] [:setting_11 :u32]
   [:setting_12 :u32] [:setting_13 :u32] [:setting_14 :u32] [:setting_15 :u32]
   [:setting_16 :u32] [:setting_17 :u32] [:setting_18 :u32] [:setting_19 :u32]
   [:setting_20 :u32] [:setting_21 :u32] [:setting_22 :u32] [:setting_23 :u32]
   [:setting_24 :u32] [:setting_25 :u32] [:setting_26 :u32] [:setting_27 :u32]
   [:setting_28 :u32] [:setting_29 :u32] [:setting_30 :u32] [:setting_31 :u32]])

(a/deftest never-run-many-fields
  (a/inline-for [field-name (:field_names (:struct (k/typeInfo WideSettings)))]
                (k/= :_ (k/++ "wide." field-name)))
  (k/unreachable))
