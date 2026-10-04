(ns la-professeure.game-cache-check-test
  (:require [clojure.test :refer [deftest is]]
            [aguafria.zig.artifact :as artifact]
            [la-professeure.game-cache-check :as cache-check]))

(deftest standalone-hits-do-not-hide-unprepared-handlers
  (let [gap {:event :disk-cache-hit
             :module "aguafria.jvm.coercion-missing"
             :artifact-key "missing"}
        events [gap
                {:event :bundle-cache-hit :module "aguafria.jvm.coercion-prepared"}
                {:event :memory-cache-hit :module "aguafria.jvm.coercion-prepared"}
                {:event :disk-cache-hit :module "la-professeure.scene"}]
        excluded (artifact/key-for :bundle-entry [(:module gap) (:artifact-key gap)])]
    (is (= [gap] (#'cache-check/unbundled-handlers #{} events)))
    (is (empty? (#'cache-check/unbundled-handlers
                 #{excluded} events))
        "Only an explicitly reported standalone exclusion may use a standalone handler")
    (is (= [gap] (#'cache-check/unbundled-handlers
                  #{(artifact/key-for :bundle-entry [(:module gap) "another-key"])} events))
        "An exclusion for another signature in the same module must not hide a gap")))
