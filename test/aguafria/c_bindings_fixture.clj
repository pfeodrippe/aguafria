(ns aguafria.c-bindings-fixture
  (:require [aguafria.c :as ac]
            [clojure.java.io :as io]))

(ac/defbindings api
  (ac/import! "aguafria_c_bindings_fixture"
              (io/file (io/resource "fixtures/c_bindings_macro.h")) {})
  [native_point point_sum])
