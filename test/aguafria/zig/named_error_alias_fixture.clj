(ns aguafria.zig.named-error-alias-fixture
  (:refer-clojure :only [])
  (:require [aguafria.zig :as a]))

(a/defconst Small (a/type [:error-set [:OutOfMemory]]))
(a/defconst Large (a/type [:error-set [:AccessDenied :FileNotFound :OutOfMemory]]))
(a/defconst SmallChain Small)
(a/defconst Count (a/type :usize))
(a/defconst Maybe (a/type [:optional :u32]))
(a/defconst Bytes (a/type [:slice-const :u8]))
(a/defconst Items (a/type [:array 4 :i32]))
(a/defstruct Nominal [[x :u32]])
(a/defconst NominalAlias (a/type Nominal))

(a/defn widen Large [[err Small]] err)
(a/defn echo-error Small [[err SmallChain]] err)
