(ns learn.example.test-errdefer-capture
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defn- capture-error [:error-union :void]
  [[captured [:* [:optional :anyerror]]]]
  (errdefer [error] (k/= @captured error))
  (az/error-value :GeneralFailure))

(az/deftest errdefer-capture-test
  (let [captured (k/var nil [:optional :anyerror])]
    (az/if-capture-stmt {:error [error]} (capture-error (k/& captured))
                        (k/unreachable)
                        (az/block
                          (try (testing/expectEqual (az/error-value :GeneralFailure)
                                                    (az/unwrap captured)))
                          (try (testing/expectEqual (az/error-value :GeneralFailure) error))))))

(comment
  (errdefer-capture-test))
