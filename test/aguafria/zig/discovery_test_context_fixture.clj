(ns aguafria.zig.discovery-test-context-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defconst builtin (import "builtin"))

(a/defstruct Helpers
  [(a/fn is-test :bool []
     (:is_test builtin))
   (a/fn require-test :u32 []
     (when (! (:is_test builtin))
       (compileError "This helper requires Zig's test environment"))
     7)])

(a/defn normal-context :bool []
  ((:is-test Helpers)))

(a/deftest do-not-execute
  (= :_ ((:is-test Helpers)))
  (= :_ ((:require-test Helpers)))
  (unreachable))
