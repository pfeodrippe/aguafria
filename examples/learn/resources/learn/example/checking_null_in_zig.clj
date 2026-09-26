(ns learn.example.checking-null-in-zig
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defstruct Foo [])

(az/defn- do-something-with-foo :void
  [[foo [:* Foo]]]
  (k/= :_ foo))

(az/defn- do-a-thing :void
  [[optional-foo [:optional [:* Foo]]]]
  ;; do some stuff
  (az/if-capture-stmt {:payload [foo]} optional-foo
                      (do-something-with-foo foo))
  ;; do some stuff
  )
