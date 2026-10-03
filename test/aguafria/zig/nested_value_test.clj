(ns aguafria.zig.nested-value-test
  (:require [aguafria.zig :as a]
            [clojure.test :refer [deftest is]]))

(deftest fresh-cross-namespace-nested-struct-values-test
  (let [suffix (str (random-uuid))
        owner (symbol (str "aguafria.nested-owner-" suffix))
        consumer (symbol (str "aguafria.nested-consumer-" suffix))
        owner-ns (create-ns owner)
        consumer-ns (create-ns consumer)]
    (try
      (doseq [n [owner-ns consumer-ns]]
        (binding [*ns* n] (refer 'clojure.core) (alias 'a 'aguafria.zig)))
      (binding [*ns* owner-ns]
        (eval '(a/defstruct Inner {:layout :extern} [[:phase :u8] [:distance :f32]]))
        (eval '(a/defstruct Outer {:layout :extern} [[:state Inner] [:gear :i8]]))
        (eval '(a/defn make-output Outer []
                 (Outer {:state (Inner {:phase 2 :distance 4.5}) :gear -1}))))
      (binding [*ns* consumer-ns]
        (alias 'owner owner)
        (eval '(a/defn inspect-output owner/Outer [] (owner/make-output))))
      ;; Do not pre-call/construct Inner: the first Outer result must discover
      ;; its nested accessors, not rely on incidental earlier REPL operations.
      (is (= {:state {:phase 2 :distance 4.5} :gear -1}
             (a/value ((ns-resolve consumer 'inspect-output)))))
      (is (= {:state {:phase 3 :distance 7.5} :gear 1}
             (a/value ((ns-resolve owner 'Outer)
                        {:state {:phase 3 :distance 7.5} :gear 1}))))
      (finally (remove-ns consumer) (remove-ns owner)))))
