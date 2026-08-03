(ns tigerbeetle-agua.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [tigerbeetle-agua.core :as example]
            [tigerbeetle-agua.generate :as generate]))

(deftest example-project-finds-its-inputs-test
  (let [{:keys [input-root output-root]} (generate/project-paths)]
    (is (.isDirectory (clojure.java.io/file input-root)))
    (is (generate/generated? output-root))))

(deftest example-loads-generated-tigerbeetle-test
  (let [loaded (example/load! {:async? true})]
    (testing "generated main is compiled and exposed as a normal Var"
      (is (= "tigerbeetle.src.tigerbeetle.main" (:module loaded)))
      (is (var? (example/main-var)))
      (is (= 4096 tigerbeetle.src.constants/sector_size))
      (require 'tigerbeetle.src.vsr)
      (let [floor (ns-resolve 'tigerbeetle.src.vsr 'sector_floor)
            ceil (ns-resolve 'tigerbeetle.src.vsr 'sector_ceil)
            before-floor (:requested-generation
                          (aguafria.zig/module-info 'tigerbeetle.src.vsr))]
        (is (= 4096 (floor 4097)))
        (let [after-first-floor (:requested-generation
                                 (aguafria.zig/module-info
                                  'tigerbeetle.src.vsr))]
          (is (< before-floor after-first-floor))
          (is (= 4096 (floor 4097)))
          (is (= after-first-floor
                 (:requested-generation
                  (aguafria.zig/module-info 'tigerbeetle.src.vsr)))))
        (is (= 8192 (ceil 4097))))
      (is (nil? (:error loaded))))))
