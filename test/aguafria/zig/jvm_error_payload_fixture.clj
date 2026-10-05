(ns aguafria.zig.jvm-error-payload-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.std.heap :as heap]
            [aguafria.std.mem :as mem]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defconst Failure (a/type [:error-set [:Rejected]]))

(a/defstruct Packet [[:number :i32]])

(a/defn generic-number :!i32 [[number :anytype]]
  (if (k/< number 0)
    (:Rejected Failure)
    (k/+ number 1)))

(a/defn generic-optional [:error-union :anyerror [:optional :i32]]
  [[number :anytype]]
  number)

(a/defn generic-packet [:error-union :anyerror Packet] [[number :anytype]]
  (Packet {:number number}))

(a/deftest successful-payloads
  (let [number (k/try (generic-number 40))
        optional (k/try (generic-optional 42))
        missing (k/try (generic-optional nil))
        packet (k/try (generic-packet 43))]
    (k/try (testing/expectEqual 41 number))
    (k/try (testing/expectEqual 42 (a/unwrap optional)))
    (k/try (testing/expectEqual nil missing))
    (k/try (testing/expectEqual 43 (:number packet)))))

(a/deftest direct-error-union-payload
  (let [input (k/as 7 [:error-union :anyerror [:optional :i32]])
        payload (k/try input)]
    (k/try (testing/expectEqual 7 (a/unwrap payload)))))

(a/deftest named-native-error
  (k/try (testing/expectError (:Rejected Failure) (generic-number -1))))

(a/deftest constructed-native-error
  (let [result (k/as (:Rejected Failure) [:error-union Failure :i32])]
    (k/try (testing/expectError (:Rejected Failure) result))))

(a/deftest source-backed-values
  (let [text (a/deref "hello")
        buffer (k/var (a/array [\h \e \l \l \o] :u8))]
    (k/try (testing/expectEqual \o (a/get text 4)))
    (k/try (testing/expectEqualStrings "hello" (a/slice buffer 0)))
    (k/try (testing/expectError (a/error-value :Rejected) (generic-number -1)))))

(a/defn copy-text [:error-union [:slice :u8]]
  [[allocator mem/Allocator] [text [:slice-const :u8]]]
  (let [result (k/try ((:alloc allocator) :u8 (:len text)))]
    (k/memcpy result text)
    result))

(a/deftest imported-factory-result
  (let [buffer (k/var k/undefined [:array 100 :u8])
        fba (k/var ((:init heap/FixedBufferAllocator) (k/& buffer)))
        allocator ((:allocator fba))
        result (k/try (copy-text allocator "hello"))]
    (k/try (testing/expectEqualStrings "hello" result))))
