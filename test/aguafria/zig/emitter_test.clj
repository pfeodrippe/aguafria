(ns aguafria.zig.emitter-test
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]
            [aguafria.zig.emitter :as emit]
            [aguafria.zig.project :as project]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest type-emission-test
  (is (= "i32" (emit/emit-type :i32)))
  (is (= "Point" (emit/emit-type 'Point)))
  (is (= "*const i32" (emit/emit-type [:*const :i32])))
  (is (= "[*:0]const u8" (emit/emit-type [:sentinel-const :u8 0])))
  (is (= "[]const u8" (emit/emit-type [:slice-const :u8])))
  (is (= "[4]f32" (emit/emit-type [:array 4 :f32])))
  (is (= "@Vector(4, f32)" (emit/emit-type [:vector 4 :f32])))
  (is (= "[*c]u8" (emit/emit-type [:c-pointer :u8])))
  (is (= "?!i32"
         (emit/emit-type [:optional [:error-union :i32]])))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Unknown composite Zig type"
                        (emit/emit-type [:mystery :i32]))))

(deftest clojure-identifier-emission-test
  (is (= "circle_contains_q" (emit/identifier 'circle-contains?)))
  (is (= "reset_bang" (emit/identifier 'reset!))))

(deftest static-dependency-demotes-default-exports-without-development-containers-test
  (let [source
        (emit/emit-static-dependency-module
         "demo.dependency"
         [{:kind :fn
           :module "demo.dependency"
           :name 'initialize!
           :declaration-key [:fn 'initialize!]
           :args []
           :return :bool
           :body [true]
           :public? true
           :export? true
           :source {:file "demo/dependency.clj" :line 7 :column 1}}])]
    (is (str/includes? source
                       "pub fn initialize_bang() callconv(.c) bool"))
    (is (not (str/includes? source "export fn initialize_bang")))
    (is (not (str/includes? source "__aguafria_type__")))
    (is (str/includes? source
                       "Aguafria declaration: demo.dependency/initialize!"))))

(deftest unevaluated-cross-namespace-name-is-normalized-test
  (let [provider-symbol 'aguafria.emitter-forward-provider
        caller-symbol 'aguafria.emitter-forward-caller
        provider-ns (create-ns provider-symbol)
        caller-ns (create-ns caller-symbol)]
    (try
      (project/register-catalog!
       {:schema-version 1
        :modules {(str provider-symbol) {}}})
      (binding [*ns* caller-ns]
        (alias 'provider provider-symbol)
        (let [declaration
              (emit/prepare-declaration
               caller-ns
               {:kind :fn
                :name 'run
                :args []
                :return :u32
                :body '((provider/tick-auto))
                :public? true
                :implicit-return? true})]
          (is (str/includes? (emit/emit-declaration declaration)
                             "return provider.tick_auto();"))))
      (finally
        (remove-ns caller-symbol)
        (remove-ns provider-symbol)))))

(deftest clojure-macros-expand-in-declaration-test
  (let [context-ns (the-ns 'aguafria.zig.emitter-test)]
    (is (= '[(transform value 1)]
           (:body
            (emit/prepare-declaration
             context-ns
             {:kind :fn
              :name 'threaded
              :args []
              :return :i32
              :body '((-> value (transform 1)))})))))
  (let [context-ns (the-ns 'aguafria.zig.emitter-test)
        declaration
        (emit/prepare-declaration
         context-ns
         {:kind :fn
          :name 'cast-pointer
          :args []
          :return :void
          :body '((-> pointer (az/cast [:* Widget])))})]
    (is (= "@as(*Widget, @ptrCast(@alignCast(pointer.?)))"
           (emit/emit-expr context-ns (first (:body declaration))))))
  (let [context-ns (the-ns 'aguafria.zig.emitter-test)
        declaration
        (emit/prepare-declaration
         context-ns
         {:kind :fn
          :name 'choose
          :args []
          :return :i32
          :body '((cond (= value 0) 10
                        (= value 1) 20
                        :else 30))})]
    (is (= '(if (= value 0)
              10
              (if (= value 1) 20 30))
           (first (:body declaration))))))

(deftest local-callable-shadows-clojure-core-macro-test
  (let [context-ns (the-ns 'aguafria.zig.emitter-test)
        declaration
        (emit/prepare-declaration
         context-ns
         {:kind :fn
          :name 'check
          :args []
          :return :void
          :body '((ak/const assert checker)
                  (assert true "from Zig"))})]
    (is (= '[(const assert checker)
             (assert true "from Zig")]
           (:body declaration))))
  (let [context-ns (the-ns 'aguafria.zig.emitter-test)
        declaration
        (emit/prepare-declaration
         context-ns
         {:kind :fn
          :name 'factory
          :args []
          :return :type
          :body '((az/container
                   {:kind :struct :layout :normal}
                   (az/fn-decl sync :- :void []))
                  (sync self frame))})]
    (is (= '(sync self frame) (second (:body declaration))))))

(deftest expression-emission-test
  (is (= "(a + (b * 2))" (emit/emit-expr '(+ a (* b 2)))))
  (is (= "@mod(counter, 5)" (emit/emit-expr '(mod counter 5))))
  (is (= "@max(a, b)"
         (emit/emit-expr (the-ns 'aguafria.zig.emitter-test)
                         '(ak/max a b))))
  (is (= "(~bits)" (emit/emit-expr '(op "~" bits))))
  (is (= "(if ((x < 0)) (-x) else x)"
         (emit/emit-expr '(if (< x 0) (- x) x))))
  (is (= "point.x" (emit/emit-expr '(field point x))))
  (is (= "items[start..end]" (emit/emit-expr '(slice items start end))))
  (is (= "0 .. 10" (emit/emit-expr '(op ".." 0 10))))
  (is (= "?*u8" (emit/emit-expr '(type [:optional [:* :u8]]))))
  (is (= "(Foo{.x = 1}).stat()"
         (emit/emit-expr '((field (init Foo {:x 1}) stat)))))
  (is (= ".{.z = 3, .a = 1, .m = 2}"
         (emit/emit-expr '(object [[:z 3] [:a 1] [:m 2]]))))
  (is (= "Foo{.z = 3, .a = 1}"
         (emit/emit-expr '(init Foo (object [[:z 3] [:a 1]])))))
  (let [foo (with-meta 'Foo
              {:aguafria/zig-reference
               {:kind :declaration
                :declaration-kind :struct
                :zig-name "Foo"
                :type-reference? true}})]
    (is (= "Foo{.a = 1, .z = 3}"
           (emit/emit-expr (list foo {:z 3 :a 1}))))
    (is (= "Foo(.{.a = 1, .z = 3})"
           (emit/emit-expr '(Foo {:z 3 :a 1})))
        "An unmarked function call receiving a map must stay a function call"))
  (testing "postfix expressions preserve Zig precedence"
    (is (= "b.step(\"check\", \"Check\")"
           (emit/emit-expr '((field b step) "check" "Check"))))
    (is (= "(try file.stat(io)).size"
           (emit/emit-expr '(field (try ((field file stat) io)) size)))))
  (is (str/starts-with?
       (emit/emit-expr
        '(container {:kind :struct :layout :packed :argument :u16}
                    (field-decl bits :u16)))
       "packed struct(u16)"))
  (is (= (str "enum {\n"
              "    /// Waiting for work.\n"
              "    waiting,\n"
              "}")
         (emit/emit-expr
          '(container {:kind :enum :layout :normal}
                      (enum-field-decl waiting "Waiting for work.")))))
  (is (= ".{.x = 1, .y = 2}" (emit/emit-expr {:y 2 :x 1})))
  (is (= ".{1, 2, 3}" (emit/emit-expr [1 2 3])))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"expects at least two operands"
                        (emit/emit-expr '(== 1)))))

(deftest statement-emission-test
  (is (= "const answer: i32 = 42;"
         (emit/emit-stmt '(const answer :i32 42))))
  (is (= "total += value;" (emit/emit-stmt '(+= total value))))
  (is (= "total /= divisor;"
         (emit/emit-stmt '(assign "/=" total divisor))))
  (is (= "defer cleanup();" (emit/emit-stmt '(defer (cleanup)))))
  (is (= "errdefer cleanup();" (emit/emit-stmt '(errdefer (cleanup)))))
  (is (= "comptime validate();"
         (emit/emit-stmt '(comptime-stmt (validate)))))
  (testing "Clojure-shaped locals are immutable unless explicitly marked mutable"
    (let [source (emit/emit-stmt
                  '(let [a 4
                         ^:var b 10
                         ^{:zig/type :u8} c 2]
                     (set! b (+ a c))))]
      (is (str/includes? source "const a = 4;"))
      (is (str/includes? source "var b = 10;"))
      (is (str/includes? source "const c: u8 = 2;"))))
  (is (= (str "for (0..@as(usize, @intCast(count))) |row| {\n"
              "    use(row);\n"
              "}")
         (emit/emit-stmt '(dotimes [row count] (use row)))))
  (is (= "continue :dispatch self.producer;"
         (emit/emit-stmt '(continue dispatch (field self producer)))))
  (is (= (str "while ((i < n)) {\n"
              "    total += i;\n"
              "    i += 1;\n"
              "}")
         (emit/emit-stmt '(while (< i n) (+= total i) (+= i 1)))))
  (is (= (str "if ((x < 0)) {\n"
              "    return (-x);\n"
              "} else {\n"
              "    return x;\n"
              "}")
         (emit/emit-stmt '(if (< x 0) (return (- x)) (return x)))))
  (testing "for-else expressions terminate the complete Zig statement"
    (is (= (str "for (items) |item| {\n"
                "    use(item);\n"
                "} else return .different_member_set;")
           (emit/emit-stmt
            '(for [[item items]] (use item)
               (else-expression (return :.different_member_set))))))
    (is (= (str "inline for (items) |item| {\n"
                "    use(item);\n"
                "} else unreachable;")
           (emit/emit-stmt
            '(inline-for [[item items]] (use item)
               (else-expression unreachable))))))
  (testing "while-else expressions terminate only when Zig requires it"
    (is (= (str "while ((head < max)) {\n"
                "    advance();\n"
                "} else max;")
           (emit/emit-stmt
            '(while-loop {:else-expression max} (< head max) (advance)))))
    (let [source
          (emit/emit-stmt
           '(while-loop
             {:else-expression
              (switch value
                (case [0] zero)
                (case-else other))}
             ready
             (advance)))]
      (is (str/ends-with? source "\n}"))
      (is (not (str/ends-with? source "\n};"))))))

(deftest struct-schema-test
  (testing "Malli-style field entries preserve per-field properties"
    (is (= [{:name :x
             :type :f32
             :properties {:doc "Horizontal component" :align 4}}
            {:name :y :type :f32 :properties {}}]
           (emit/parse-struct-fields
            [[:x {:doc "Horizontal component" :align 4} :f32]
             [:y :f32]]))))
  (testing "metadata on an entry is retained as field properties"
    (is (= {:unit :meters}
           (:properties
            (first (emit/parse-struct-fields
                    [(with-meta [:distance :f32] {:unit :meters})]))))))
  (testing "the old flattened struct syntax is rejected clearly"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Each az/defstruct field must be a vector"
         (emit/parse-struct-fields '[:x :- :f32 :y :- :f32])))))

(deftest declaration-and-module-test
  (let [source (emit/emit-module
                "demo"
                 [{:kind :fn :name 'add :return :i32 :export? true
                  :args [{:name 'a :type :i32} {:name 'b :type :i32}]
                  :body ['(+ a b)]}
                 {:kind :const :name 'factor :type :i32 :value 3}])]
    (testing "top-level declarations are deterministic and constants precede functions"
      (is (< (.indexOf source "pub const factor")
             (.indexOf source "export fn add"))))
    (is (re-find #"export fn add\(a: i32, b: i32\) callconv\(\.c\) i32" source))
    (is (str/includes? source "return (a + b);"))))

(deftest dependency-module-preserves-default-function-abi-test
  (let [source
        (emit/emit-dependency-module
         "demo.callback"
         [{:kind :fn
           :name 'callback
           :return :void
           :export? true
           :public? true
           :attributes {}
           :args [{:name 'value :type :i32}]
           :body []}])]
    (is (str/includes?
         source
         "pub fn callback(value: i32) callconv(.c) void"))
    (is (not (str/includes? source "export fn callback")))))

(deftest dependency-module-keeps-generic-functions-on-the-zig-abi-test
  (let [source
        (emit/emit-dependency-module
         "demo.generic"
         [{:kind :fn
           :name 'scale
           :return :u32
           :export? true
           :public? true
           :attributes {}
           :args [{:name 'value
                   :type :u32
                   :properties {:zig/prefix "comptime"}}]
           :body ['(* value 2)]}])]
    (is (str/includes? source "pub fn scale(comptime value: u32) u32"))
    (is (not (str/includes? source "callconv(.c)")))))

(deftest declaration-only-hot-slice-retains-external-import-test
  (let [external-call
        (with-meta 'aguafria.zig.import.demo.uuid/new-v4
          {:aguafria/zig-reference
           {:kind :import-member
            :import "uuid"
            :module "uuid"
            :member "v4.new"
            :zig-name "uuid.v4.new"}})
        source
        (emit/emit-reloadable-module
         "demo.external"
         [{:kind :fn
           :name 'make-id
           :declaration-key [:fn 'make-id]
           :return :u128
           :args [{:name 'io :type 'std.Io}]
           :body [(list external-call 'io)]}]
         {[:fn 'make-id]
          {:implementation "make_id_implementation"
           :dispatch "make_id_dispatch"
           :dispatch-type "make_id_function_type"
           :setter "make_id_set_dispatch"
           :getter "make_id_implementation_address"
           :active-counter "active_calls"
           :active-depth "active_depth"
           :active-tracking "track_active"
           :active-tracking-setter "set_active_tracking"
           :active-getter "active_call_count"
           :publication-epoch "publication_epoch"
           :publication-epoch-setter "set_publication_epoch"}}
         {}
         {:dependency? true})]
    (is (str/includes? source "const uuid = @import(\"uuid\");"))
    (is (str/includes? source "uuid.v4.new(io)"))))

(deftest named-dependencies-preserve-per-module-type-identity-test
  (let [alpha-container (emit/named-module-container "demo.alpha")
        beta-container (emit/named-module-container "demo.beta")
        imported-alpha
        (with-meta 'alpha
          {:aguafria/zig-reference
           {:kind :namespace-root
            :module "demo.alpha"
            :import-name "demo.alpha"
            :import-namespace 'demo.alpha
            :zig-name "alpha"
            :symbol 'alpha}})
        dependency-source
        (emit/emit-dependency-module
         "demo.alpha"
         [{:kind :const
           :name 'Thing
           :public? true
           :value '(container {:kind :struct})}])
        root-source
        (emit/emit-named-module
         "demo.root"
         [{:kind :const :name 'alpha :value imported-alpha}
          {:kind :const :name 'type-name
           :value '(ak/typeName :u32)}])]
    (testing "containers are deterministic and differ across source modules"
      (is (not= alpha-container beta-container))
      (is (str/includes? dependency-source
                         (str "pub const " alpha-container " = struct")))
      (is (str/includes? dependency-source
                         "inline fn __aguafria_type_name(comptime T: type) [:0]const u8"))
      (is (str/includes? dependency-source
                         "if (comptime @import(\"std\").mem.startsWith")))
    (testing "compiler roots select the dependency container"
      (is (str/includes?
           root-source
           (str "@import(\"demo.alpha\")." alpha-container)))
      (is (str/includes? root-source
                         "__aguafria_type_name(u32)")))))

(deftest reloadable-module-publication-epoch-test
  (let [declaration {:kind :fn :name 'increment :return :i32 :export? true
                     :declaration-key [:fn 'increment]
                     :args [{:name 'value :type :i32}]
                     :body ['(+ value 1)]}
        source
        (emit/emit-reloadable-module
         "demo.live" [declaration]
         {[:fn 'increment]
          {:implementation "__impl"
           :dispatch-type "__fn_type"
           :dispatch "__dispatch"
           :getter "__implementation_address"
           :setter "__set_dispatch"
           :emit-getter? true
           :active-counter "__active_calls"
           :active-getter "__active_call_count"
           :publication-epoch "__publication_epoch"
           :publication-epoch-setter "__set_publication_epoch"}})]
    (is (str/includes? source
                       "var __publication_epoch: ?*const usize = null;"))
    (is (str/includes?
         source
         (str "export fn __set_publication_epoch("
              "__set_publication_epoch_address: usize)")))
    (is (str/includes? source
                       "if ((__dispatch_publication_before & 1) != 0) continue;"))
    (is (str/includes? source
                       (str "if (__dispatch_publication_before == "
                            "__dispatch_publication_after) break :publication "
                            "__dispatch_publication_candidate;")))
    (is (< (.indexOf source "if (@inComptime())")
           (.indexOf source "const __dispatch_target")))))

(deftest exported-native-callback-counts-active-calls-without-tls-test
  (let [source
        (emit/emit-reloadable-module
         "demo.callback"
         [{:kind :fn
           :name 'tick
           :return :void
           :export? true
           :attributes {:attrs #{:export}}
           :declaration-key [:fn 'tick]
           :args [{:name 'context :type [:* :u8]}]
           :body []}]
         {[:fn 'tick]
          {:implementation "__impl"
           :dispatch-type "__fn_type"
           :dispatch "__dispatch"
           :getter "__implementation_address"
           :setter "__set_dispatch"
           :active-counter "__active_calls"
           :active-depth "__active_depth"
           :active-tracking "__track_active_calls"
           :active-getter "__active_call_count"
           :publication-epoch "__publication_epoch"
           :publication-epoch-setter "__set_publication_epoch"}})
        implementation
        (subs source (.indexOf source "fn __impl")
              (.indexOf source "const __fn_type"))]
    (is (str/includes? implementation
                       "@atomicRmw(usize, &__active_calls, .Add"))
    (is (str/includes? implementation
                       "@atomicRmw(usize, &__active_calls, .Sub"))
    (is (not (str/includes? implementation "__active_depth")))
    (is (not (str/includes? implementation "_outermost")))))

(deftest reloadable-module-reserves-publication-locals-test
  (let [declaration {:kind :fn :name 'choose :return :usize
                     :declaration-key [:fn 'choose]
                     :args [{:name 'candidate :type :usize}]
                     :body ['candidate]}
        source
        (emit/emit-reloadable-module
         "demo.publication-locals" [declaration]
         {[:fn 'choose]
          {:implementation "__impl"
           :dispatch-type "__fn_type"
           :dispatch "__dispatch"
           :getter "__implementation_address"
           :setter "__set_dispatch"
           :active-counter "__active_calls"
           :active-depth "__active_depth"
           :active-tracking "__track_active_calls"
           :active-getter "__active_call_count"
           :publication-epoch "__publication_epoch"
           :publication-epoch-setter "__set_publication_epoch"}})]
    (is (str/includes? source "candidate: usize"))
    (is (str/includes? source
                       "const __dispatch_publication_candidate = @atomicLoad"))
    (is (not (str/includes? source "const candidate = @atomicLoad")))))

(deftest reloadable-branch-hint-remains-first-statement-test
  (let [declaration {:kind :fn :name 'crash :return :noreturn
                     :declaration-key [:fn 'crash]
                     :args []
                     :body ['(ak/branchHint :.cold) 'unreachable]}
        source
        (emit/emit-reloadable-module
         "demo.cold" [declaration]
         {[:fn 'crash]
          {:implementation "__impl"
           :dispatch-type "__fn_type"
           :dispatch "__dispatch"
           :getter "__implementation_address"
           :setter "__set_dispatch"
           :active-counter "__active_calls"
           :active-depth "__active_depth"
           :active-tracking "__track_active_calls"
           :active-getter "__active_call_count"
           :publication-epoch "__publication_epoch"
           :publication-epoch-setter "__set_publication_epoch"}})]
    (is (re-find #"fn __impl\(\) noreturn \{\s*(?://[^\n]*\n\s*)?@branchHint\(\.cold\);\s+const"
                 source))
    (is (re-find #"fn crash\(\) noreturn \{\s*(?://[^\n]*\n\s*)?@branchHint\(\.cold\);\s+if"
                 source))))

(deftest reloadable-discard-arguments-get-callable-internal-names-test
  (let [declaration {:kind :fn :name 'visit :return :i32 :export? true
                     :declaration-key [:fn 'visit]
                     :args [{:name '_ :type :i32}
                            {:name 'value :type :i32}]
                     :body ['value]}
        source
        (emit/emit-reloadable-module
         "demo.discard" [declaration]
         {[:fn 'visit]
          {:implementation "__impl"
           :dispatch-type "__fn_type"
           :dispatch "__dispatch"
           :getter "__implementation_address"
           :setter "__set_dispatch"
           :emit-getter? true
           :active-counter "__active_calls"
           :active-depth "__active_depth"
           :active-tracking "__track_active_calls"
           :active-getter "__active_call_count"
           :publication-epoch "__publication_epoch"
           :publication-epoch-setter "__set_publication_epoch"}})]
    (is (str/includes? source "__aguafria_discard_0: i32"))
    (is (str/includes? source "_ = __aguafria_discard_0;"))
    (is (str/includes? source
                       "const __fn_type = @TypeOf(&__impl);"))
    (is (str/includes? source
                       "return __dispatch_target(__aguafria_discard_0, value);"))
    (is (str/includes? source
                       "return @intFromPtr(&__impl);"))
    (is (not (str/includes? source "__impl(_, value)")))))

(deftest reloadable-noreturn-fallback-is-a-complete-statement-test
  (let [declaration {:kind :fn :name 'fatal :return :noreturn
                     :declaration-key [:fn 'fatal]
                     :args [{:name '_ :type :i32}]
                     :body ['unreachable]}
        source
        (emit/emit-reloadable-module
         "demo.noreturn" [declaration]
         {[:fn 'fatal]
          {:implementation "__impl"
           :dispatch-type "__fn_type"
           :dispatch "__dispatch"
           :getter "__implementation_address"
           :setter "__set_dispatch"
           :active-counter "__active_calls"
           :active-depth "__active_depth"
           :active-tracking "__track_active_calls"
           :active-getter "__active_call_count"
           :publication-epoch "__publication_epoch"
           :publication-epoch-setter "__set_publication_epoch"}})]
    (is (re-find
         #"if \(__dispatch_target_address == 0\) \{\s+__impl\(__aguafria_discard_0\);\s+\}"
         source))
    (is (not (str/includes? source
                            "__impl(__aguafria_discard_0)\n    }")))))

(deftest reloadable-inferred-error-dispatch-preserves-exact-zig-abi-test
  (let [declaration {:kind :fn :name 'run :return :void
                     :zig-qualifiers "!"
                     :declaration-key [:fn 'run]
                     :args []
                     :body ['(return)]}
        source
        (emit/emit-reloadable-module
         "demo.inferred-error" [declaration]
         {[:fn 'run]
          {:implementation "__impl"
           :dispatch-type "__fn_type"
           :dispatch "__dispatch"
           :getter "__implementation_address"
           :setter "__set_dispatch"
           :active-counter "__active_calls"
           :active-depth "__active_depth"
           :active-tracking "__track_active_calls"
           :active-getter "__active_call_count"
           :publication-epoch "__publication_epoch"
           :publication-epoch-setter "__set_publication_epoch"}})]
    (is (str/includes? source "const __fn_type = @TypeOf(&__impl);"))
    (is (str/includes? source "return __dispatch_target();"))
    (is (not (str/includes? source "dispatch_frame")))
    (is (not (str/includes? source "anyerror!void")))))

(deftest reloadable-const-keeps-comptime-state-reference-direct-test
  (let [state-symbol
        (with-meta 'io-threaded
          {:aguafria/zig-reference
           {:symbol 'io-threaded
            :zig-name "io_threaded"
            :declaration-kind :var
            :state-accessor "__state_io_threaded_reference"}})
        state {:kind :var
               :name 'io-threaded
               :zig-name "io_threaded"
               :type :u32
               :value 41
               :declaration-key [:var 'io-threaded]}
        derived {:kind :const
                 :name 'answer
                 :type :u32
                 :value (list 'aguafria.keyword/+ state-symbol 1)
                 :declaration-key [:const 'answer]}
        runtime-reader {:kind :fn
                        :name 'read-answer
                        :return :u32
                        :args []
                        :body [state-symbol]
                        :declaration-key [:fn 'read-answer]}
        source
        (emit/emit-reloadable-module
         "demo.comptime-state"
         [state derived runtime-reader]
         {}
         {[:var 'io-threaded]
          {:accessor "__state_io_threaded_reference"
           :getter "__state_io_threaded_address"
           :setter "__state_io_threaded_set_address"
           :size-getter "__state_io_threaded_size"
           :align-getter "__state_io_threaded_alignment"}})]
    (is (str/includes? source "const answer: u32 = (io_threaded + 1);"))
    (is (str/includes? source
                       "return __state_io_threaded_reference().*;"))))

(deftest reloadable-state-supports-comptime-selected-void-storage-test
  (let [declaration {:kind :var
                     :name 'state
                     :type 'StateType
                     :value '(raw ".{}")
                     :declaration-key [:var 'state]}
        source
        (emit/emit-reloadable-module
         "demo.conditional-state"
         [declaration]
         {}
         {[:var 'state]
          {:accessor "__state_reference"
           :getter "__state_address"
           :setter "__state_set_address"
           :size-getter "__state_size"
           :align-getter "__state_alignment"}})]
    (is (str/includes?
         source
         "var state: StateType = if (@typeInfo(StateType) == .void) {} else .{};"))))

(deftest source-metadata-safety-test
  (let [source (emit/emit-module
                "cider-buffer"
                [{:kind :const :name 'answer :type :i32 :value 42
                  :source {:file "(ns broken\n  (:require [evil]))"
                           :line 9 :column 3}}])]
    (testing "multiline editor buffer contents can never be injected as Zig"
      (is (not (str/includes? source "(:require")))
      (is (str/includes? source "// Aguafria source: <repl>:9:3")))))

(deftest unresolved-reference-test
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"Unresolved dotted Zig reference"
       (emit/emit-expr '(out_of_nowhere.member 1)))))

(deftest implicit-return-test
  (is (= "return (a + b);"
         (emit/emit-function-body '((+ a b)) :i32)))
  (is (= (str "if ((x < 0)) {\n"
              "    return (-x);\n"
              "} else {\n"
              "    return x;\n"
              "}")
         (emit/emit-function-body '((if (< x 0) (- x) x)) :i32)))
  (is (= "value;" (emit/emit-function-body '(value) :void)))
  (is (= (str "if ((x < 0)) {\n"
              "    return 0;\n"
              "}\n"
              "return (x + 1);")
         (emit/emit-function-body
          '((when (< x 0) (return 0))
            (+ x 1))
          :i32)))
  (is (= (str "{\n"
              "    const x = (a + 1);\n"
              "    const y = (x * 2);\n"
              "    return (x + y);\n"
              "}")
         (emit/emit-function-body
          '((let [x (+ a 1) y (* x 2)] (+ x y)))
          :i32)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"must produce a value"
                        (emit/emit-function-body '((while ready (continue))) :i32))))
