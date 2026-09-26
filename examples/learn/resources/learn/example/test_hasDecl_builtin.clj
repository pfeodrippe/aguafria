(ns learn.example.test-hasDecl-builtin
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defstruct Foo
  [[:nope :i32]
   [:blah {:var "xxx"} :_]
   [:hi {:const 1 :private true} :_]])

(az/deftest hasDecl
  (try (testing/expect (k/hasDecl Foo "blah")))

  ;; Even though `hi` is private, @hasDecl returns true because this test is
  ;; in the same file scope as Foo. It would return false if Foo was declared
  ;; in a different file.
  (try (testing/expect (k/hasDecl Foo "hi")))

  ;; @hasDecl is for declarations; not fields.
  (try (testing/expect (k/! (k/hasDecl Foo "nope"))))
  (try (testing/expect (k/! (k/hasDecl Foo "nope1234")))))

(comment
  (hasDecl))
