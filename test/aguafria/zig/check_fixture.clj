(ns aguafria.zig.check-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]
            [aguafria.std.testing :as testing]
            [aguafria.zig.compiler-work :as work]
            [aguafria.zig.explain :as explain]))

(a/defn- increment :i32 [[x :i32]] (k/+ x 1))
(a/defn entry :i32 [[x :i32]] (increment x))

(a/deftest succeeds
  (k/try (testing/expectEqual 43 (entry 42))))

(a/deftest fails
  (k/try (testing/expect false)))

(defn caught-attempt []
  (let [spawned? (atom false)]
    (try
      ;; Policy fixture: do not spawn a real compiler. The policy must reject
      ;; BEFORE this thunk, and catching the rejection must not erase evidence.
      (work/run-command! ["policy-fixture-zig" "build-lib"]
                         #(do (reset! spawned? true) {:exit 0}))
      (catch clojure.lang.ExceptionInfo _ nil))
    @spawned?))

(defn caught-thread-attempt []
  (let [result (atom :not-run)
        thread (Thread. ^Runnable #(reset! result (caught-attempt)))]
    (.start thread)
    (.join thread)
    @result))

(defn forbidden-fallback []
  (explain/event! {:event :disk-cache-hit :module "policy-fixture"})
  :returned)
