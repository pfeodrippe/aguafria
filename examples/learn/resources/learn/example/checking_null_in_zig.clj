(ns learn.example.checking-null-in-zig
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defstruct Foo [])

(a/defn- do-something-with-foo :void
  [[foo [:* Foo]]]
  (k/= :_ foo))

(a/defn- do-a-thing :void
  [[optional-foo [:optional [:* Foo]]]]
  ;; do some stuff
  (a/if-capture-stmt {:payload [foo]} optional-foo
                     (do-something-with-foo foo))
  ;; do some stuff
  )
