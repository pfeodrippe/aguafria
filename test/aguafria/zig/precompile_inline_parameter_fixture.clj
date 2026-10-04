(ns aguafria.zig.precompile-inline-parameter-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defn public-value :u32
  [[input (a/container {:kind :struct} [(a/field-decl value :u32)])]]
  (:value input))

(a/defn- private-value :u32
  [[input (a/container {:kind :struct} [(a/field-decl value :u32)])]]
  (:value input))

(a/defn option-size :u32
  [[options (a/container {:kind :struct}
                         [(a/field-decl string [:slice-const :u8])
                          (a/field-decl port_default :u16)])]]
  (k/+ (k/as (k/intCast (:len (:string options))) :u32)
       (k/as (:port_default options) :u32)))
