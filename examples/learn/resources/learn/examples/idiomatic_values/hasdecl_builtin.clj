(ns learn.examples.idiomatic-values.hasdecl-builtin
  "Converted from test_hasDecl_builtin.zig"
  (:require aguafria.std
            [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defconst Foo
  (az/container {:kind :struct}
    (az/field-decl :nope :i32)
    (az/var-decl blah {:attrs #{:public}} "xxx")
    (az/const-decl hi 1)))

(az/deftest declaration-presence-test
  (try (testing/expect (ak/hasDecl Foo "blah")))

  ;; Although hi is private, @hasDecl returns true in the same file as Foo.
  ;; It would return false if Foo were declared in a different file.
  (try (testing/expect (ak/hasDecl Foo "hi")))

  ;; @hasDecl is for declarations, not fields.
  (try (testing/expect (ak/! (ak/hasDecl Foo "nope"))))
  (try (testing/expect (ak/! (ak/hasDecl Foo "nope1234")))))
