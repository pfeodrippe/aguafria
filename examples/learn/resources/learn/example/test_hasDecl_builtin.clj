(ns learn.example.test-hasDecl-builtin
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defstruct Foo
  [[:nope :i32]
   [:blah {:var "xxx"} :_]
   [:hi {:const 1 :private true} :_]])

(a/deftest hasDecl
  (k/try (testing/expect (k/hasDecl Foo "blah")))

  ;; @hasDecl returns false for private declarations.
  (k/try (testing/expect (k/! (k/hasDecl Foo "hi"))))

  ;; @hasDecl is for declarations; not fields.
  (k/try (testing/expect (k/! (k/hasDecl Foo "nope"))))
  (k/try (testing/expect (k/! (k/hasDecl Foo "nope1234")))))

(comment
  (hasDecl))
