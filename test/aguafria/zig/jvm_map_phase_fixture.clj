(ns aguafria.zig.jvm-map-phase-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defn comptime-name-length :usize
  [[options {:attrs #{k/comptime}} :anytype]]
  (a/field (a/field options :name) :len))

(a/defn runtime-name-length :usize
  [[options :anytype]]
  (a/field (a/field options :name) :len))

(a/defn- internal-name :void
  {:zig/qualifiers "callconv(.c)"}
  [])

(a/defcomptime export-name
  (k/export (k/& internal-name) {:name "aguafria_map_phase_fixture_export" :linkage :.strong}))

(a/deftest names-obey-the-parameter-phase
  (try (testing/expectEqual 3 (comptime-name-length {:name "foo"})))
  (try (testing/expectEqual 3 (runtime-name-length {:name "foo"}))))
