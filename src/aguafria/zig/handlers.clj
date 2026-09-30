(ns aguafria.zig.handlers
  "Operand plans for reusable native handlers. Arithmetic still executes in Zig;
  these plans choose transport types, not implementations of Zig operators."
  (:require [aguafria.zig.value :as value])
  (:import [java.lang.foreign ValueLayout]))

(defn- shift-count-type [type]
  (let [bits (cond
               (#{:usize :isize} type) (* 8 (.byteSize ValueLayout/ADDRESS))
               (and (keyword? type) (re-matches #"[iu][0-9]+" (name type)))
               (Long/parseLong (subs (name type) 1)))]
    (when bits
      (keyword (str "u" (if (<= bits 1) 0 (- 64 (Long/numberOfLeadingZeros (dec bits)))))))))

(defn assignment-operand-type [target operation]
  (let [type (value/qualified-type target)
        state (value/realize! target)
        pointer? (or (= :pointer (:native-kind state))
                     (and (vector? type)
                          (#{:* :*const :many :many-const :c-pointer} (first type))))]
    (cond
      (#{"<<=" ">>=" "<<|="} operation) (shift-count-type type)
      (and pointer? (#{"+=" "-="} operation)) :usize
      (and (keyword? type)
           (or (#{:usize :isize :f16 :f32 :f64 :f80 :f128} type)
               (re-matches #"[iu][0-9]+" (name type)))
           (not (#{"<<=" ">>=" "<<|="} operation))) type)))

(defn- literal-number [argument]
  (cond
    (number? argument) argument
    (and (value/zig-value? argument)
         (#{:comptime_int :comptime_float} (value/qualified-type argument)))
    (value/value argument)))

(defn- integer-carrier [operation operands]
  ;; Comptime integers are unbounded. A carrier is a transport capacity, not
  ;; the exposed Zig type. Grow geometrically, with enough headroom for the
  ;; operation, so ordinary changes of values share the same native handler.
  (let [bits (map #(.bitLength (.abs (biginteger %))) operands)
        needed (+ 2 (if (#{"*" "*%" "*|"} operation)
                      (reduce + bits)
                      (+ (reduce max 0 bits) (count operands))))
        width (first (drop-while #(< % needed) (iterate #(* 2 %) 128)))]
    (when (<= width 65535)
      (keyword (str "i" width)))))

(defn- comparison-carrier [native-type arguments]
  (when (and (keyword? native-type)
             (or (#{:usize :isize} native-type)
                 (re-matches #"[iu][0-9]+" (name native-type))))
    (let [bits (if (#{:usize :isize} native-type)
                 (* 8 (.byteSize ValueLayout/ADDRESS))
                 (Long/parseLong (subs (name native-type) 1)))
          literal-bits (reduce max 0 (map #(.bitLength (.abs (biginteger %)))
                                          (filter integer? arguments)))]
      (keyword (str "i" (max 128 (inc bits) (+ 2 literal-bits)))))))

(defn operator-plan
  "Return a reusable runtime signature where no operand value is required by
  Zig's type system. Nil means this operation requires its structural/comptime
  specialization path. Explicit native operand types are never discarded."
  [{:keys [kind zig-token]} arguments]
  (when (= kind :operator)
    (let [numbers (mapv literal-number arguments)
          comparison? (contains? #{"==" "!=" "<" ">" "<=" ">="} zig-token)
          arithmetic? (contains? #{"+" "-" "*" "+%" "-%" "*%" "+|" "-|" "*|"} zig-token)
          numeric? (and (seq arguments) (every? number? numbers))
          integer? (and numeric? (every? clojure.core/integer? numbers))
          floating? (and numeric? (not integer?))
          carrier (when (and integer? (or arithmetic? comparison?))
                    (integer-carrier zig-token numbers))
          native-type (some #(when (value/zig-value? %) (value/qualified-type %)) arguments)
          pointer? (and (value/zig-value? (first arguments))
                        (or (= :pointer (:native-kind (value/realize! (first arguments))))
                            (and (vector? native-type)
                                 (#{:many :many-const :c-pointer} (first native-type)))))
          numeric-type? (and (keyword? native-type)
                             (or (#{:usize :isize :f16 :f32 :f64 :f80 :f128} native-type)
                                 (re-matches #"[iu][0-9]+" (name native-type))))]
      (cond
        carrier
        {:types (vec (repeat (count arguments) carrier))
         :arguments numbers
         :result-type (when-not comparison? :comptime_int)}

        (and floating? (or arithmetic? comparison? (= "/" zig-token)))
        {:types (vec (repeat (count arguments) [:slice-const :u8]))
         :arguments (mapv str numbers)
         :float-literals? true
         :result-type (when-not comparison? :comptime_float)}

        (and (seq arguments) (every? boolean? arguments))
        {:types (vec (repeat (count arguments) :bool)) :arguments arguments}

        (and pointer? (#{"+" "-"} zig-token) (= 2 (count arguments))
             (clojure.core/integer? (second arguments)))
        {:types [native-type :usize] :arguments arguments}

        (and numeric-type?
             (every? #(or (value/zig-value? %)
                          (clojure.core/integer? %)
                          (and (#{:f16 :f32 :f64 :f80 :f128} native-type) (number? %)))
                     arguments))
        {:types (mapv (fn [index argument]
                        (cond
                          (value/zig-value? argument) (value/qualified-type argument)
                          (and (pos? index) (#{"<<" ">>" "<<|"} zig-token))
                          (shift-count-type native-type)
                          (and comparison? (clojure.core/integer? argument))
                          (or (comparison-carrier native-type arguments) native-type)
                          :else native-type))
                      (range) arguments)
         :arguments arguments}

        :else nil))))
