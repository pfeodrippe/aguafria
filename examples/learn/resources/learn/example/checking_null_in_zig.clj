(ns learn.example.checking-null-in-zig
  (:require [aguafria.zig :as az]))

(az/defstruct Foo [])

(az/defn- do-something-with-foo :void
  [[foo [:* Foo]]]
  (set! _ foo))

(az/defn- do-a-thing :void
  [[optional-foo [:optional [:* Foo]]]]
  ;; Do some stuff.
  (az/if-capture-stmt {:payload [foo]} optional-foo
    (do-something-with-foo foo))
  ;; Do some stuff.
  )

(comment
  ;; This excerpt has no standalone entry point; evaluate its declarations above in their documented context.
  )
