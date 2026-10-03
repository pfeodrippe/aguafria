(ns aguafria.zig.packed-field-test
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]
            [clojure.test :refer [deftest is]]))

(deftest single-item-pointer-packed-field-types
  (let [fixture (create-ns (symbol (str "aguafria.packed-field-" (random-uuid))))
        old-config (a/configuration)]
    (try
      (a/configure! {:async? false :modules {}})
      (binding [*ns* fixture]
        (refer 'clojure.core)
        (alias 'a 'aguafria.zig)
        (eval '(a/defstruct Packed {:layout :packed}
                             [[:a :u32] [:b :u32]]))
        (eval '(a/defstruct Bits {:layout :packed}
                             [[:low :u3] [:high :u5]])))
      (let [Packed (var-get (ns-resolve fixture 'Packed))
            Bits (var-get (ns-resolve fixture 'Bits))
            packed (k/var (Packed {:a 1 :b 2}) nil {:align 4})
            pointer (k/as (k/& packed) [:* {:align 4} Packed])
            bits (k/var (Bits {:low 3 :high 17}))]
        (is (= 1 (a/value (:a pointer))))
        (is (= 2 (a/value (:b pointer))))
        (is (= 3 (a/value (:low (k/& bits)))))
        (is (= 17 (a/value (:high (k/& bits))))))
      (finally
        (a/configure! old-config)
        (remove-ns (ns-name fixture))))))
