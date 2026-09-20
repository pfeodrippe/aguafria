(ns learn.example.test-functions
  (:require [aguafria.builtin :as builtin]
            [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defconst native-arch (az/field builtin/cpu :arch))

;; Functions are declared like this.
(az/defn- add :i8
  [[a :i8] [b :i8]]
  (when (ak/== a 0)
    (ak/return b))
  (+ a b))

;; Export makes a function externally visible in the generated object file
;; and makes it use the C ABI.
(az/defn sub :i8
  {:attrs #{:export}}
  [[a :i8] [b :i8]]
  (- a b))

;; Extern declares a function resolved at link time when linking statically,
;; or at runtime when linking dynamically. The quoted library name identifies
;; the library containing the function (for example, "c" refers to libc.so).
;; callconv changes the function's calling convention.
(az/defextern ExitProcess :noreturn
  {:zig/prefix "extern \"kernel32\"" :zig/qualifiers "callconv(.winapi)"}
  [[exit-code :u32]])

(az/defextern atan2 :f64
  {:zig/prefix "extern \"c\""}
  [[a :f64] [b :f64]])

;; @branchHint tells the optimizer that a function is rarely called ("cold").
(az/defn- abort :noreturn
  []
  (ak/branchHint :.cold)
  (az/while-loop {} true))

;; The naked calling convention omits the function prologue and epilogue.
;; This can be useful when integrating with assembly.
(az/defn- _start :noreturn
  {:zig/qualifiers "callconv(.naked)"}
  []
  (abort))

;; Inline forces a function to be inlined at every call site.
;; If it cannot be inlined, that is a compile-time error.
(az/defn- shift-left-one :u32
  {:zig/prefix "inline"}
  [[value :u32]]
  (az/op "<<" value 1))

;; Public visibility allows another file to import and call this function.
(az/defn sub2 :i8
  [[a :i8] [b :i8]]
  (- a b))

;; Function pointers have a *const prefix.
(az/defconst Call2Op
  (az/type [:*const [:fn {} [{:name :a :type :i8}
                             {:name :b :type :i8}]
                     :i8]]))

(az/defn- do-op :i8
  [[operation Call2Op] [left :i8] [right :i8]]
  (operation left right))

(az/deftest function-test
  (try (testing/expectEqual 11 (do-op add 5 6)))
  (try (testing/expectEqual -1 (do-op sub2 5 6))))

(comment
  (function-test))
