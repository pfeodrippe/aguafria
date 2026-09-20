(ns learn.example.test-errdefer-capture
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defn- capture-error [:error-union :void]
  [[captured [:* [:optional :anyerror]]]]
  (errdefer [error] (set! @captured error))
  (ak/return (az/error-value :GeneralFailure)))

(az/deftest errdefer-capture-test
  (let [^:var captured (ak/as nil [:optional :anyerror])]
    (az/if-capture-stmt {:error [error]} (capture-error (ak/& captured))
                        (ak/unreachable)
                        (az/block
                          (try (testing/expectEqual (az/error-value :GeneralFailure)
                                                    (az/unwrap captured)))
                          (try (testing/expectEqual (az/error-value :GeneralFailure) error))))))

(comment
  (errdefer-capture-test))
