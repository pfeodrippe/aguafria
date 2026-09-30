(ns aguafria.zig.packed-field-test
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]
            [clojure.test :refer [deftest is]]))

(deftest single-item-pointer-packed-field-types
  (let [fixture (create-ns (symbol (str "aguafria.packed-field-" (random-uuid))))
        old-config (az/configuration)]
    (try
      (az/configure! {:async? false :modules {}})
      (binding [*ns* fixture]
        (refer 'clojure.core)
        (alias 'az 'aguafria.zig)
        (eval '(az/defstruct Packed {:layout :packed}
                             [[:a :u32] [:b :u32]]))
        (eval '(az/defstruct Bits {:layout :packed}
                             [[:low :u3] [:high :u5]])))
      (let [Packed (var-get (ns-resolve fixture 'Packed))
            Bits (var-get (ns-resolve fixture 'Bits))
            packed (k/var (Packed {:a 1 :b 2}) nil {:align 4})
            pointer (k/as (k/& packed) [:* {:align 4} Packed])
            bits (k/var (Bits {:low 3 :high 17}))]
        (is (= 1 (az/value (:a pointer))))
        (is (= 2 (az/value (:b pointer))))
        (is (= 3 (az/value (:low (k/& bits)))))
        (is (= 17 (az/value (:high (k/& bits))))))
      (finally
        (az/configure! old-config)
        (remove-ns (ns-name fixture))))))
