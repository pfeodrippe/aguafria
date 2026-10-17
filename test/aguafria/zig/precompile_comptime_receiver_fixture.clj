(ns aguafria.zig.precompile-comptime-receiver-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defstruct Settings
  [[:element :type]
   [:count :usize]
   (a/fn bytes :usize
     [[self {:attrs #{comptime}} Settings]]
     (* (:count self) (sizeOf (:element self))))])

(a/defstruct Root
  [[:settings Settings]])

(a/defconst first-root Root {:settings (Settings {:element :u16 :count 3})})

(a/defconst second-root Root {:settings (Settings {:element :u32 :count 5})})

(a/defconst inferred-root first-root)

(a/defn first-size :usize
  []
  ((:bytes (:settings first-root))))

(a/defn second-size :usize
  []
  ((:bytes (:settings second-root))))

(a/defn inferred-size :usize
  []
  ((:bytes (:settings inferred-root))))
