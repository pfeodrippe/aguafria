(ns field-lab.mesh-group
  "An owned set of synchronized per-body FEM playback caches."
  (:require [aguafria.zig :as az]
            [aguafria.keyword :as ak]
            [aguafria.std.heap :as heap]
            [aguafria.std.debug :as debug]
            [field-lab.mesh-cache :as cache]))

(az/defstruct Group
  [[:items [:array 3 [:optional [:* cache/Cache]]]] [:count :u32]])

(az/defn create!
  :- [:* Group]
  []
  (let [group (catch ((az/field heap/page_allocator create) Group)
                (debug/panic "Unable to allocate FEM cache group" []))]
    (set! (az/deref group) (Group {:items [null null null] :count 0}))
    group))

(az/defn add!
  "Transfer one cache into the group; all caches have the same frame capacity."
  :- :void
  [[group [:* Group]] [owned [:* cache/Cache]]]
  (debug/assert (< (az/field group count) 3))
  (when (> (az/field group count) 0)
    (debug/assert (ak/== (az/field (az/field owned frames) len)
                         (az/field (az/field (az/unwrap (az/index (az/field group items) 0)) frames) len))))
  (az/set-many!
    (az/index (az/field group items) (az/field group count)) owned
    (az/field group count) (+ (az/field group count) 1)))

(az/defn item
  :- [:* cache/Cache]
  [[group [:* Group]] [body :usize]]
  (debug/assert (< body (az/field group count)))
  (az/unwrap (az/index (az/field group items) body)))

(az/defn complete?
  :- :bool
  [[group [:* Group]]]
  (when (or (ak/== (az/field group count) 0) (> (az/field group count) 3)) (ak/return false))
  (dotimes [body (az/field group count)]
    (let [owned (item group body)]
      (when (or (ak/== (az/field owned count) 0)
                (ak/!= (az/field owned count) (az/field (az/field owned frames) len)))
        (ak/return false))))
  true)

(az/defn destroy!
  :- :void
  [[group [:* Group]]]
  (dotimes [body (az/field group count)] (cache/destroy! (item group body)))
  ((az/field heap/page_allocator destroy) group))
