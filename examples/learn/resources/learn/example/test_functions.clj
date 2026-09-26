(ns learn.example.test-functions
  (:require [aguafria.builtin :as builtin]
            [aguafria.keyword :as k]
            [aguafria.std.Target.Cpu :as cpu]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defconst native-arch (cpu/-arch builtin/cpu))

;; Functions are declared like this
(az/defn- add :i8
  [[a :i8] [b :i8]]
  (when (k/== a 0)
    (k/return b))
  (k/+ a b))

;; The export specifier makes a function externally visible in the generated
;; object file, and makes it use the C ABI.
(az/defn sub :i8
  {:attrs #{k/export}}
  [[a :i8] [b :i8]]
  (k/- a b))

;; The extern specifier is used to declare a function that will be resolved
;; at link time, when linking statically, or at runtime, when linking
;; dynamically. The quoted identifier after the extern keyword specifies
;; the library that has the function. (e.g. "c" -> libc.so)
;; The callconv specifier changes the calling convention of the function.
(az/defextern ExitProcess :noreturn
  {:zig/prefix "extern \"kernel32\"" :zig/qualifiers "callconv(.winapi)"}
  [[exit-code :u32]])

(az/defextern atan2 :f64
  {:zig/prefix "extern \"c\""}
  [[a :f64] [b :f64]])

;; The @branchHint builtin can be used to tell the optimizer that a function is rarely called ("cold").
(az/defn- abort :noreturn
  []
  (k/branchHint :.cold)
  (az/while-loop {} true))

;; The naked calling convention makes a function not have any function prologue or epilogue.
;; This can be useful when integrating with assembly.
(az/defn- _start :noreturn
  {:zig/qualifiers "callconv(.naked)"}
  []
  (abort))

;; The inline calling convention forces a function to be inlined at all call sites.
;; If the function cannot be inlined, it is a compile-time error.
(az/defn- shift-left-one :u32
  {:attrs #{k/inline}}
  [[a :u32]]
  (k/<< a 1))

;; The pub specifier allows the function to be visible when importing.
;; Another file can use @import and call sub2
(az/defn sub2 :i8
  [[a :i8] [b :i8]]
  (k/- a b))

;; Function pointers are prefixed with `*const `.
(az/defconst Call2Op
  (az/type [:*const [:fn {} [{:name :a :type :i8}
                             {:name :b :type :i8}]
                     :i8]]))

(az/defn- do-op :i8
  [[fn-call Call2Op] [op1 :i8] [op2 :i8]]
  (fn-call op1 op2))

(az/deftest function
  (try (testing/expectEqual 11 (do-op add 5 6)))
  (try (testing/expectEqual -1 (do-op sub2 5 6))))

(comment
  (function))
