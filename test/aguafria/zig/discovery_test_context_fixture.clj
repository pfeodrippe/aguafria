(ns aguafria.zig.discovery-test-context-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defconst builtin (k/import "builtin"))

(a/defstruct Helpers
  [(a/fn is-test :bool []
     (:is_test builtin))
   (a/fn require-test :u32 []
     (when (k/! (:is_test builtin))
       (k/compileError "This helper requires Zig's test environment"))
     7)])

(a/defn normal-context :bool []
  ((:is-test Helpers)))

(a/deftest do-not-execute
  (k/= :_ ((:is-test Helpers)))
  (k/= :_ ((:require-test Helpers)))
  (k/unreachable))
