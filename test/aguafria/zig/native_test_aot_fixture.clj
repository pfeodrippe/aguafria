(ns aguafria.zig.native-test-aot-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defvar counter :u32 0)

(a/deftest skipped-test
  (return (a/error-value :SkipZigTest)))

(a/deftest panic-test
  (panic "prepared test panic"))

(a/deftest failing-test
  (k/try (testing/expect false)))

(a/deftest first-isolated-test
  (raw "try @import(\"std\").testing.expect(@import(\"builtin\").is_test);")
  (raw "try @import(\"std\").testing.expect(@import(\"builtin\").test_functions.len == 1);")
  (+= counter 1)
  (k/try (testing/expectEqual (u32 1) counter)))

(a/deftest second-isolated-test
  (raw "try @import(\"std\").testing.expect(@import(\"builtin\").is_test);")
  (raw "try @import(\"std\").testing.expect(@import(\"builtin\").test_functions.len == 1);")
  (+= counter 1)
  (k/try (testing/expectEqual (u32 1) counter)))
