(ns aguafria.zig.named-error-alias-driver
  (:require [aguafria.zig :as a]
            [aguafria.zig.named-error-alias-fixture :as fixture]
            [aguafria.zig.value :as value]
            [clojure.string :as str]))

(defn evaluate! []
  (let [error (value/->ZigError "OutOfMemory" [:error-set [:OutOfMemory]])
        widened (a/value (fixture/widen error))
        echoed (a/value (fixture/echo-error error))
        invalid (try
                  (fixture/widen (value/->ZigError "ForeignError" [:error-set [:ForeignError]]))
                  :unexpected-success
                  (catch clojure.lang.ExceptionInfo error (:reason (ex-data error))))
        raw-rejected? (try (fixture/widen 17) false
                          (catch clojure.lang.ExceptionInfo error
                            (str/includes? (ex-message error) "not a raw ordinal")))]
    [(:name widened) (:type widened) (:name echoed) invalid raw-rejected?]))
