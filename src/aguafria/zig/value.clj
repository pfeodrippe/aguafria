(ns aguafria.zig.value
  "Inspectable Clojure handles for Zig values that do not have an exact JVM
  representation. The backing bytes are materialized lazily by Aguafria and
  remain native memory; no lossy Java coercion is required."
  (:refer-clojure :exclude [bytes type])
  (:require [clojure.pprint :as pprint])
  (:import [java.lang.ref Cleaner Cleaner$Cleanable]
           [java.lang.foreign Arena MemorySegment]
           [java.nio.charset StandardCharsets]))

(declare decoded info realize! type type-info qualified-type value-state
         decode-packed-backing decode-struct decode-value-segment
         encode-packed-backing
         write-struct! write-value-segment! sequence-values sequence-element)

(defonce ^:private ^Cleaner native-cleaner (Cleaner/create))

(def ^:dynamic *allocation-arena*
  "Arena used for pointee storage while encoding values such as Zig slices."
  nil)

(deftype ZigPointer [address zigType]
  clojure.lang.IDeref
  (deref [this]
    ((requiring-resolve 'aguafria.zig.jvm/dereference-pointer!) this))
  Object
  (toString [_]
    (str "#aguafria/zig-pointer[" address " " (pr-str zigType) "]")))

(defn zig-pointer?
  "True for a typed, borrowed Zig pointer value."
  [value]
  (instance? ZigPointer value))

(defn pointer-address
  "Return a Zig pointer's native address as a JVM long."
  [^ZigPointer pointer]
  (.-address pointer))

(defn pointer-type
  "Return the exact Aguafria Zig pointer type form."
  [^ZigPointer pointer]
  (.-zigType pointer))

(defn- unsigned-address
  [address]
  (if (and (instance? Long address) (neg? (long address)))
    (java.math.BigInteger. (Long/toUnsignedString (long address)))
    (biginteger address)))

(defn pointer-segment
  "Create a borrowed MemorySegment view of `byte-size` bytes at a non-null Zig
  pointer. The pointee's Zig owner controls its lifetime."
  ^MemorySegment [^ZigPointer pointer byte-size]
  (let [address (long (pointer-address pointer))
        byte-size (long byte-size)]
    (when (zero? address)
      (throw (ex-info "Cannot create a MemorySegment for a null Zig pointer"
                      {:type (pointer-type pointer) :byte-size byte-size})))
    (when (neg? byte-size)
      (throw (ex-info "Pointer MemorySegment size cannot be negative"
                      {:type (pointer-type pointer) :byte-size byte-size})))
    (.reinterpret (MemorySegment/ofAddress address) byte-size)))

(defn- lookup-field
  [value key]
  (when-not (keyword? key)
    (throw (ex-info "Native field lookup requires a keyword" {:key key})))
  ((requiring-resolve 'aguafria.zig/field) value key))

(defn- reject-lookup-default!
  [key]
  (throw (ex-info "Native field lookup does not accept a default value"
                  {:key key})))

(deftype ZigType [descriptor construct]
  clojure.lang.ILookup
  (valAt [this key]
    (lookup-field this key))
  (valAt [_ key _not-found]
    (reject-lookup-default! key))

  clojure.lang.IFn
  (invoke [_ value]
    (construct value))
  (applyTo [this args]
    (clojure.lang.AFn/applyToHelper this args))

  Object
  (toString [this]
    (pr-str (type-info this))))

(defn zig-type?
  "True when value is an Aguafria Zig type constructor."
  [value]
  (instance? ZigType value))

(defn type-info
  "Return the inspectable descriptor for a Zig type constructor."
  [^ZigType zig-type]
  (.-descriptor zig-type))

(defn zig-type
  "Create a callable Zig type constructor."
  [descriptor construct]
  (ZigType. descriptor construct))

(defrecord ZigError [name type]
  Object
  (toString [_] (str "error." name)))

(defn zig-error? [value]
  (instance? ZigError value))

(defn error-form
  "Recreate an error by name in the receiving module, never by a library-local code."
  [error]
  (list 'aguafria.keyword/as
        (list 'aguafria.zig/field (list 'aguafria.zig/type (:type error))
              (keyword (:name error)))
        (:type error)))

(defmacro ^:private def-native-value-type [name fields & body]
  ;; IFn has a Java overload for every arity; all use the same native call path.
  (let [invoke (fn [receiver arguments]
                 `((requiring-resolve 'aguafria.zig.jvm/invoke-native-value!)
                   ~receiver ~arguments))
        receiver (gensym "value")
        arguments (vec (repeatedly 20 #(gensym "argument")))
        more (with-meta (gensym "more") {:tag 'objects})]
    `(deftype ~name ~fields
       clojure.lang.IFn
       ~@(for [arity (range 21)
               :let [parameters (subvec arguments 0 arity)]]
           `(~'invoke [~receiver ~@parameters] ~(invoke receiver parameters)))
       (~'invoke [~receiver ~@arguments ~more]
         ~(invoke receiver `(into ~arguments ~more)))
       (~'applyTo [~receiver ~'arguments] ~(invoke receiver 'arguments))
       (~'call [~receiver] ~(invoke receiver []))
       (~'run [~receiver] ~(invoke receiver []) nil)
       ~@body)))

(def-native-value-type ZigValue [descriptor state materialize]
  clojure.lang.ILookup
  (valAt [this key]
    (lookup-field this key))
  (valAt [_ key _not-found]
    (reject-lookup-default! key))

  clojure.lang.IDeref
  (deref [this]
    (let [result (decoded this)]
      (if (zig-pointer? result)
        ((requiring-resolve 'aguafria.zig.jvm/dereference-pointer!) this)
        result)))

  clojure.lang.Seqable
  (seq [this]
    (seq (sequence-values this)))

  clojure.lang.Indexed
  (nth [this index]
    (sequence-element this index))
  (nth [this index not-found]
    (sequence-element this index not-found))
  (count [this]
    (count (decoded this)))

  java.lang.AutoCloseable
  (close [this]
    (let [state (value-state this)]
      (when-not (= :closed (:status @state))
        (let [{:keys [close! cleanable]} (realize! this)]
          (if cleanable
            (.clean ^Cleaner$Cleanable cleanable)
            (do
              (when close! (close!))
              (swap! state assoc :status :closed)))))
      nil))

  Object
  (toString [this]
    (pr-str this)))

(defn zig-value?
  "True when value is an Aguafria native Zig value handle."
  [value]
  (instance? ZigValue value))

(defn- value-state
  [^ZigValue value]
  (if-let [^ThreadLocal states (:thread-states (.-descriptor value))]
    (do
      (when (.isVirtual (Thread/currentThread))
        (throw (ex-info "Native thread-local storage requires a platform thread"
                        {:type :native-thread-local-storage})))
      (.get states))
    (.-state value)))

(defn info
  "Return a small, printable view without forcing native compilation."
  [^ZigValue value]
  (let [state-map @(value-state value)]
    (merge
     (select-keys (.-descriptor value)
                  [:module :name :kind :type :logical-id :execution-context :align])
     (select-keys state-map
                  [:status :representation :size :alignment :generation]))))

(defn realize!
  "Materialize a Zig value once and return its internal representation map."
  [^ZigValue value]
  (let [state (value-state value)]
    (locking state
      (case (:status @state)
        :ready @state
        :closed (throw (ex-info "Zig value is closed" (info value)))
        :failed (throw (:error @state))
        (try
          (let [realized ((.-materialize value))
                close! (:close! realized)
                cleanable
                (when close!
                  (.register
                   native-cleaner
                   value
                   (reify Runnable
                     (run [_]
                       (close!)
                       (swap! state assoc :status :closed)))))
                ready (cond-> (assoc realized :status :ready)
                        cleanable (assoc :cleanable cleanable))]
            (reset! state ready)
            ready)
          (catch Throwable error
            (reset! state {:status :failed :error error})
            (throw error)))))))

(defn value
  "Return an exact JVM scalar when available, otherwise the ZigValue itself."
  [^ZigValue zig-value]
  (let [realized (realize! zig-value)]
    (if (= :scalar (:representation realized))
      (:value realized)
      zig-value)))

(defn segment
  "Return the value's exact native bytes as a MemorySegment."
  ^MemorySegment [^ZigValue zig-value]
  (let [{:keys [representation segment] :as realized} (realize! zig-value)]
    (when-not (= :native representation)
      (throw (ex-info "Zig value has an exact JVM scalar representation"
                      (dissoc realized :close!))))
    segment))

(defn bytes
  "Copy the exact current native representation into a JVM byte vector."
  [^ZigValue zig-value]
  (vec (.toArray (segment zig-value)
                 java.lang.foreign.ValueLayout/JAVA_BYTE)))

(defn- native-bytes-big-endian
  [^MemorySegment native-segment]
  (let [raw (.toArray native-segment
                      java.lang.foreign.ValueLayout/JAVA_BYTE)]
    (if (= java.nio.ByteOrder/LITTLE_ENDIAN
           (java.nio.ByteOrder/nativeOrder))
      (byte-array (reverse raw))
      raw)))

(defn- narrow-integer
  [^java.math.BigInteger integer]
  (if (< (.bitLength integer) 63)
    (.longValue integer)
    integer))

(defn- decode-integer
  [^MemorySegment native-segment signed? bits]
  (let [modulus (.shiftLeft java.math.BigInteger/ONE bits)
        unsigned-value
        (.and (java.math.BigInteger. 1
                                     (native-bytes-big-endian native-segment))
              (.subtract modulus java.math.BigInteger/ONE))
        value (if (and signed? (pos? bits) (.testBit unsigned-value (dec bits)))
                (.subtract unsigned-value modulus)
                unsigned-value)]
    (narrow-integer value)))

(defn- unsigned-native-integer
  [^MemorySegment native-segment]
  (java.math.BigInteger. 1 (native-bytes-big-endian native-segment)))

(defn- decode-packed-field
  [^java.math.BigInteger backing
   {:keys [bit-offset bit-size type schema]}]
  (let [mask (.subtract (.shiftLeft java.math.BigInteger/ONE bit-size)
                        java.math.BigInteger/ONE)
        unsigned-value (.and (.shiftRight backing bit-offset) mask)]
    (cond
      (= :packed-struct (:kind schema))
      (decode-packed-backing unsigned-value schema)

      (= :bool type) (not (.equals java.math.BigInteger/ZERO unsigned-value))
      (and (keyword? type) (re-matches #"i\d+" (name type)))
      (narrow-integer
       (if (.testBit unsigned-value (dec bit-size))
         (.subtract unsigned-value
                    (.shiftLeft java.math.BigInteger/ONE bit-size))
         unsigned-value))
      :else (narrow-integer unsigned-value))))

(defn- field-key
  [field-name]
  (if (keyword? field-name)
    field-name
    (keyword (name field-name))))

(defn- decode-packed-backing
  [^java.math.BigInteger backing {:keys [fields]}]
  (into (array-map)
        (map (fn [{:keys [name] :as field}]
               [(field-key name) (decode-packed-field backing field)]))
        fields))

(defn- decode-packed-struct
  [^MemorySegment native-segment schema]
  (decode-packed-backing (unsigned-native-integer native-segment) schema))

(defn- field-value
  [field-values field]
  (let [missing (Object.)
        field-name (:name field)
        result (reduce (fn [_ key]
                         (if (contains? field-values key)
                           (reduced (get field-values key))
                           missing))
                       missing
                       [(field-key field-name)
                        field-name
                        (clojure.core/name field-name)])]
    (if (identical? missing result)
      (when-let [default-segment (:default-segment field)]
        (decode-value-segment default-segment (:type field) (:schema field)))
      result)))

(defn- field-present?
  [field-values field]
  (let [field-name (:name field)]
    (boolean
     (some #(contains? field-values %)
           [(field-key field-name)
            field-name
            (clojure.core/name field-name)]))))

(defn- validate-field-map!
  [description schema field-values]
  (when-not (map? field-values)
    (throw (ex-info (str description " require a Clojure field map")
                    {:schema schema :value field-values})))
  (let [known-keys (into #{} (mapcat (fn [{:keys [name]}]
                                       [(field-key name)
                                        name
                                        (clojure.core/name name)]))
                         (:fields schema))
        unknown (seq (remove known-keys (keys field-values)))]
    (when unknown
      (throw (ex-info (str "Unknown " description " fields")
                      {:unknown-fields (vec unknown)
                       :known-fields (mapv (comp field-key :name)
                                           (:fields schema))}))))
  field-values)

(defn- integer-field-value
  [field value]
  (let [{:keys [bit-size type]} field
        integer (cond
                  (= :bool type) (case value
                                   true java.math.BigInteger/ONE
                                   false java.math.BigInteger/ZERO
                                   (throw (ex-info "Packed Zig bool requires true or false"
                                                   {:field field :value value})))
                  (char? value) (java.math.BigInteger/valueOf (int value))
                  (instance? java.math.BigInteger value) value
                  (integer? value) (biginteger value)
                  :else (throw (ex-info "Packed Zig field requires an integer or boolean"
                                        {:field field :value value})))
        modulus (.shiftLeft java.math.BigInteger/ONE bit-size)
        minimum (if (and (keyword? type)
                         (re-matches #"i\d+" (name type)))
                  (.negate (.shiftRight modulus 1))
                  java.math.BigInteger/ZERO)
        maximum (if (neg? (.signum minimum))
                  (.subtract (.shiftRight modulus 1) java.math.BigInteger/ONE)
                  (.subtract modulus java.math.BigInteger/ONE))]
    (when (or (neg? (.compareTo integer minimum))
              (pos? (.compareTo integer maximum)))
      (throw (ex-info "Packed Zig field value is out of range"
                      {:field field :value value
                       :minimum minimum :maximum maximum})))
    (if (neg? (.signum integer)) (.add integer modulus) integer)))

(defn- packed-field-integer
  [field value]
  (if (= :packed-struct (get-in field [:schema :kind]))
    (encode-packed-backing (:schema field) value)
    (integer-field-value field value)))

(defn- encode-packed-backing
  [{:keys [fields] :as schema} field-values]
  (validate-field-map! "Packed Zig values" schema field-values)
  (reduce
   (fn [^java.math.BigInteger backing {:keys [bit-offset] :as field}]
     (let [value (field-value field-values field)
           value (if (nil? value)
                   (cond
                     (= :packed-struct (get-in field [:schema :kind])) {}
                     (= :bool (:type field)) false
                     :else 0)
                   value)]
       (.or backing (.shiftLeft (packed-field-integer field value)
                                bit-offset))))
   java.math.BigInteger/ZERO
   fields))

(defn write-packed-struct!
  "Encode a Clojure field map into an exact packed-struct MemorySegment."
  [^MemorySegment native-segment schema field-values]
  (let [backing (encode-packed-backing schema field-values)
        byte-count (.byteSize native-segment)
        big-endian (.toByteArray backing)
        padded (byte-array byte-count)]
    (doseq [index (range (min byte-count (alength big-endian)))]
      (aset-byte padded
                 (- byte-count index 1)
                 (aget big-endian (- (alength big-endian) index 1))))
    (let [native-bytes (if (= java.nio.ByteOrder/LITTLE_ENDIAN
                              (java.nio.ByteOrder/nativeOrder))
                         (byte-array (reverse padded))
                         padded)]
      (doseq [index (range byte-count)]
        (.setAtIndex native-segment
                     java.lang.foreign.ValueLayout/JAVA_BYTE
                     index
                     (aget native-bytes index)))))
  native-segment)

(defn- integer-type
  [zig-type storage-size]
  (when (keyword? zig-type)
    (let [type-name (name zig-type)]
      (cond
        (= "usize" type-name) {:signed? false :bits (* 8 storage-size)}
        (= "isize" type-name) {:signed? true :bits (* 8 storage-size)}
        (re-matches #"c_(u?)(short|int|long|longlong)" type-name)
        {:signed? (not (.startsWith type-name "c_u"))
         :bits (* 8 storage-size)}
        :else
        (when-let [[_ signed-marker bit-count]
                   (re-matches #"([iu])(\d+)" type-name)]
          {:signed? (= "i" signed-marker)
           :bits (Long/parseLong bit-count)})))))

(defn- integer-value
  [{:keys [signed? bits] :as integer-type} value context]
  (let [integer (cond
                  (instance? java.math.BigInteger value) value
                  (char? value) (biginteger (int value))
                  (integer? value) (biginteger value)
                  :else (throw (ex-info "Zig integer requires a Clojure integer"
                                        (assoc context
                                               :integer-type integer-type
                                               :value value))))
        modulus (.shiftLeft java.math.BigInteger/ONE bits)
        minimum (if signed?
                  (.negate (.shiftRight modulus 1))
                  java.math.BigInteger/ZERO)
        maximum (if (and signed? (pos? bits))
                  (.subtract (.shiftRight modulus 1) java.math.BigInteger/ONE)
                  (.subtract modulus java.math.BigInteger/ONE))]
    (when (or (neg? (.compareTo integer minimum))
              (pos? (.compareTo integer maximum)))
      (throw (ex-info "Zig integer value is out of range"
                      (assoc context
                             :integer-type integer-type
                             :value value
                             :minimum minimum
                             :maximum maximum))))
    (if (neg? (.signum integer)) (.add integer modulus) integer)))

(defn- write-native-integer!
  [^MemorySegment native-segment zig-type value context]
  (let [storage-size (.byteSize native-segment)
        integer-type (integer-type zig-type storage-size)
        integer (integer-value integer-type value context)
        big-endian (.toByteArray integer)
        padded (byte-array storage-size)]
    (doseq [index (range (min storage-size (alength big-endian)))]
      (aset-byte padded
                 (- storage-size index 1)
                 (aget big-endian (- (alength big-endian) index 1))))
    (let [native-bytes (if (= java.nio.ByteOrder/LITTLE_ENDIAN
                              (java.nio.ByteOrder/nativeOrder))
                         (byte-array (reverse padded))
                         padded)]
      (doseq [index (range storage-size)]
        (.setAtIndex native-segment
                     java.lang.foreign.ValueLayout/JAVA_BYTE
                     index
                     (aget native-bytes index)))))
  native-segment)

(defn- decode-native-field
  [^MemorySegment field-segment {:keys [type] :as field}]
  (decode-value-segment field-segment type (:schema field)))

(defn- decode-array
  [^MemorySegment native-segment
   {:keys [length storage-length element-type element-schema element-bit-size element-size]}]
  (if element-bit-size
    (let [backing (unsigned-native-integer native-segment)]
      (mapv (fn [index]
              (decode-packed-field backing {:bit-offset (* index element-bit-size)
                                            :bit-size element-bit-size
                                            :type element-type :schema element-schema}))
            (range length)))
    (let [storage-length (long (or storage-length length))
          element-size (or element-size
                           (if (zero? storage-length)
                             0
                             (quot (.byteSize native-segment) storage-length)))]
      (mapv (fn [index]
              (decode-value-segment
               (.asSlice native-segment (* index element-size) element-size)
               element-type
               element-schema))
            (range length)))))

(defn- enum-key
  [value]
  (keyword (str (if (keyword? value) (name value) value))))

(defn- decode-enum
  [^MemorySegment native-segment {:keys [members] :as schema}]
  (let [native-bytes (vec (.toArray native-segment
                                    java.lang.foreign.ValueLayout/JAVA_BYTE))]
    (or (some (fn [{:keys [name bytes]}]
                (when (= native-bytes bytes) name))
              members)
        (throw (ex-info "Native Zig enum has an unknown tag value"
                        {:schema schema :bytes native-bytes})))))

(defn- decode-union
  [^MemorySegment native-segment {:keys [tagged? fields] :as schema}]
  (let [active-field
        (if tagged?
          (some #(when ((:active-fn %) native-segment) %) fields)
          (some #(when (= (:active-field schema) (field-key (:name %))) %) fields))]
    (when (and (not tagged?) (nil? active-field))
      (throw (ex-info
              "An untagged Zig union requires an active-field interpretation"
              {:schema (dissoc schema :fields)
               :bytes (vec (.toArray native-segment
                                     java.lang.foreign.ValueLayout/JAVA_BYTE))})))
    (if-let [{:keys [name type byte-size schema payload-segment-fn]}
             active-field]
      {(field-key name)
       (if (or (= :void type) (zero? byte-size))
         nil
         (let [payload (if tagged?
                         (payload-segment-fn native-segment)
                         (.asSlice native-segment 0 byte-size))]
           (when-not payload
             (throw (ex-info "Tagged Zig union payload address is unavailable"
                             {:field name :schema schema})))
           (decode-value-segment payload type schema)))}
      (throw (ex-info "Tagged Zig union has no recognized active field"
                      {:schema (dissoc schema :fields)
                       :known-fields (mapv (comp field-key :name) fields)})))))

(defn- decode-optional
  [^MemorySegment native-segment
   {:keys [child-type child-schema present-fn payload-segment-fn]}]
  (when (present-fn native-segment)
    (let [payload (payload-segment-fn native-segment)]
      (when-not payload
        (throw (ex-info "Present Zig optional has no payload address"
                        {:child-type child-type})))
      (decode-value-segment payload child-type child-schema))))

(defn- decode-pointer
  [^MemorySegment native-segment zig-type]
  (ZigPointer. (.longValue (unsigned-native-integer native-segment)) zig-type))

(defn- decode-slice
  [^MemorySegment native-segment
   {:keys [element-type element-schema element-size read-fn] :as schema}]
  (let [{:keys [address length]} (read-fn native-segment)
        length (long length)
        element-size (long element-size)]
    (when (neg? length)
      (throw (ex-info "Zig slice is too large for JVM indexing"
                      {:schema (dissoc schema :read-fn :set-fn)
                       :length length})))
    (when (and (pos? length) (zero? address))
      (throw (ex-info "Non-empty Zig slice has a null pointer"
                      {:schema (dissoc schema :read-fn :set-fn)
                       :length length})))
    (let [byte-size (Math/multiplyExact length element-size)
          pointee (when (pos? byte-size)
                    (.reinterpret (MemorySegment/ofAddress (long address))
                                  byte-size))]
      (mapv (fn [index]
              (if (zero? element-size)
                nil
                (decode-value-segment
                 (.asSlice ^MemorySegment pointee
                           (* index element-size) element-size)
                 element-type element-schema)))
            (range length)))))

(defn- decode-error-union
  [^MemorySegment native-segment
   {:keys [error-fn payload-segment-fn payload-type payload-schema]}]
  (if-let [error (error-fn native-segment)]
    {:error error}
    {:ok (if (= :void payload-type)
           nil
           (decode-value-segment (payload-segment-fn native-segment)
                                 payload-type payload-schema))}))

(defn- decode-value-segment
  [^MemorySegment native-segment zig-type schema]
  (cond
    (= :packed-struct (:kind schema))
    (decode-packed-struct native-segment schema)

    (= :struct (:kind schema))
    (decode-struct native-segment schema)

    (contains? #{:array :vector} (:kind schema))
    (decode-array native-segment schema)

    (= :enum (:kind schema))
    (decode-enum native-segment schema)

    (= :union (:kind schema))
    (decode-union native-segment schema)

    (= :optional (:kind schema))
    (decode-optional native-segment schema)

    (= :pointer (:kind schema))
    (decode-pointer native-segment zig-type)

    (= :slice (:kind schema))
    (decode-slice native-segment schema)

    (= :error-union (:kind schema))
    (decode-error-union native-segment schema)

    (= :void zig-type)
    nil

    (= :bool zig-type)
    (not (zero? (.get native-segment
                      java.lang.foreign.ValueLayout/JAVA_BYTE 0)))

    (integer-type zig-type (.byteSize native-segment))
    (let [{:keys [signed? bits]}
          (integer-type zig-type (.byteSize native-segment))]
      (decode-integer native-segment signed? bits))

    (= :f32 zig-type)
    (.get native-segment java.lang.foreign.ValueLayout/JAVA_FLOAT 0)

    (= :f64 zig-type)
    (.get native-segment java.lang.foreign.ValueLayout/JAVA_DOUBLE 0)

    :else
    ;; Preserve every byte when a richer field codec is not available yet.
    (vec (.toArray native-segment java.lang.foreign.ValueLayout/JAVA_BYTE))))

(defn- decode-struct
  [^MemorySegment native-segment {:keys [fields tuple?]}]
  (into (if tuple? [] (array-map))
        (map (fn [{:keys [name byte-offset byte-size] :as field}]
               (let [decoded (decode-native-field
                              (.asSlice native-segment byte-offset byte-size)
                              field)]
                 (if tuple? decoded [(field-key name) decoded]))))
        fields))

(defn- write-native-field!
  [^MemorySegment field-segment {:keys [name type] :as field} value]
  (write-value-segment! field-segment type (:schema field) value
                        {:field name}))

(defn write-array!
  [^MemorySegment native-segment
   {:keys [length storage-length element-type element-schema sentinel element-bit-size element-size]
    :as schema}
   values]
  (when-not (sequential? values)
    (throw (ex-info "Zig arrays and vectors require a sequential Clojure value"
                    {:schema schema :value values})))
  (when-not (= length (count values))
    (throw (ex-info "Wrong number of Zig array/vector elements"
                    {:schema schema :expected length :actual (count values)})))
  (if element-bit-size
    (write-packed-struct! native-segment
                          {:fields (mapv (fn [index]
                                           {:name (str index) :type element-type
                                            :schema element-schema
                                            :bit-offset (* index element-bit-size)
                                            :bit-size element-bit-size})
                                         (range length))}
                          (into {} (map-indexed (fn [index value] [(keyword (str index)) value]) values)))
    (let [storage-length (long (or storage-length length))
          element-size (or element-size
                           (if (zero? storage-length)
                             0
                             (quot (.byteSize native-segment) storage-length)))
          storage-values (cond-> (vec values)
                           (> storage-length length) (conj sentinel))]
      (doseq [[index value] (map-indexed vector storage-values)]
        (write-value-segment!
         (.asSlice native-segment (* index element-size) element-size)
         element-type element-schema value {:index index}))))
  native-segment)

(defn write-enum!
  "Encode a Clojure keyword/symbol/string as an exact Zig enum value."
  [^MemorySegment native-segment {:keys [members] :as schema} value]
  (let [requested (enum-key value)
        member (some #(when (= requested (:name %)) %) members)]
    (when-not member
      (throw (ex-info "Unknown Zig enum member"
                      {:value value
                       :requested requested
                       :known-members (mapv :name members)
                       :schema (dissoc schema :members)})))
    (doseq [[index byte-value] (map-indexed vector (:bytes member))]
      (.setAtIndex native-segment
                   java.lang.foreign.ValueLayout/JAVA_BYTE
                   index
                   (byte byte-value))))
  native-segment)

(defn write-union!
  "Encode one active Zig union field from a single-entry Clojure map. Tagged
  unions remain decodable after arbitrary Zig calls; untagged unions retain
  exact bytes but require an explicit interpretation when read back."
  [^MemorySegment native-segment {:keys [fields] :as schema} value]
  (when-not (and (map? value) (= 1 (count value)))
    (throw (ex-info "Zig unions require exactly one active field"
                    {:value value
                     :known-fields (mapv (comp field-key :name) fields)})))
  (let [[requested field-value] (first value)
        requested (field-key requested)
        {:keys [name type byte-size schema init-fn] :as field}
        (some #(when (= requested (field-key (:name %))) %) fields)]
    (when-not field
      (throw (ex-info "Unknown Zig union field"
                      {:field requested
                       :known-fields (mapv (comp field-key :name) fields)})))
    (.fill native-segment (byte 0))
    (if (or (= :void type) (zero? byte-size))
      (when-not (nil? field-value)
        (throw (ex-info "A void Zig union field requires nil"
                        {:field name :value field-value})))
      (write-value-segment! (.asSlice native-segment 0 byte-size)
                            type schema field-value {:field name}))
    (init-fn native-segment
             (when (pos? byte-size) (.asSlice native-segment 0 byte-size))))
  native-segment)

(defn- write-optional!
  [^MemorySegment native-segment
   {:keys [child-type child-schema payload-size set-fn]}
   value]
  (if (nil? value)
    (set-fn native-segment false nil)
    (let [payload (.asSlice native-segment 0 payload-size)]
      (write-value-segment! payload child-type child-schema value
                            {:optional-child child-type})
      (set-fn native-segment true payload)))
  native-segment)

(defn- write-pointer!
  [^MemorySegment native-segment zig-type {:keys [nullable?]} value]
  (let [address
        (cond
          (zig-pointer? value) (pointer-address value)
          (instance? MemorySegment value) (.address ^MemorySegment value)
          (and nullable? (nil? value)) 0
          (integer? value) value
          :else
          (throw (ex-info
                  "Zig pointer requires a ZigPointer, MemorySegment, or address"
                  {:type zig-type :value value
                   :clojure-type (clojure.core/type value)})))]
    (let [address (unsigned-address address)]
      (when (and (zero? address) (not nullable?))
        (throw (ex-info "Non-null Zig pointer cannot use address zero"
                        {:type zig-type :value value})))
      (write-native-integer! native-segment :usize address {:type zig-type})))
  native-segment)

(defn- write-slice!
  [^MemorySegment native-segment
   zig-type
   {:keys [element-type element-schema element-size element-alignment set-fn]
    :as schema}
   values]
  (let [values
        (if (and (string? values) (= :u8 element-type))
          (mapv #(bit-and 0xff %)
                (.getBytes ^String values StandardCharsets/UTF_8))
          values)]
    (when-not (sequential? values)
      (throw (ex-info "Zig slices require a sequential Clojure value; u8 slices also accept UTF-8 strings"
                      {:type zig-type :schema (dissoc schema :read-fn :set-fn)
                       :value values})))
    (when-not *allocation-arena*
      (throw (ex-info
              "Constructing a Zig slice requires owner-scoped native storage"
              {:type zig-type
               :hint "Pass the vector directly to an Aguafria function or construct it inside an owning Zig value."})))
    (let [values (vec values)
          element-size (long element-size)
          element-alignment (long (max 1 element-alignment))
          byte-size (Math/multiplyExact (long (count values)) element-size)
        ;; A Zig slice pointer is non-null even when its length is zero.
          backing (.allocate ^Arena *allocation-arena*
                             (long (max 1 byte-size))
                             element-alignment)]
      (doseq [[index value] (map-indexed vector values)]
        (when (pos? element-size)
          (write-value-segment!
           (.asSlice backing (* index element-size) element-size)
           element-type element-schema value {:index index :slice-type zig-type})))
      (set-fn native-segment backing (count values)))
    native-segment))

(defn- write-error-union!
  [^MemorySegment native-segment zig-type
   {:keys [payload-type payload-schema payload-size set-ok-fn set-error-fn]}
   value]
  (when-not (and (map? value) (= 1 (count value)))
    (throw (ex-info "Zig error unions require {:ok value} or {:error {:name keyword}}"
                    {:type zig-type :value value})))
  (let [[branch branch-value] (first value)]
    (case branch
      :ok
      (if (= :void payload-type)
        (do
          (when-not (nil? branch-value)
            (throw (ex-info "A void Zig error-union payload requires nil"
                            {:type zig-type :value value})))
          (set-ok-fn native-segment nil))
        (let [payload (.asSlice native-segment 0 payload-size)]
          (write-value-segment! payload payload-type payload-schema branch-value
                                {:error-union zig-type :branch :ok})
          (set-ok-fn native-segment payload)))

      :error
      (let [error-name (cond
                         (keyword? branch-value) branch-value
                         (map? branch-value) (:name branch-value)
                         :else nil)]
        (when-not (or (keyword? error-name) (string? error-name))
          (throw (ex-info
                  "A Zig error value requires its :name; native error codes are image-local"
                  {:type zig-type :value value
                   :hint "Pass {:error {:name :ErrorName}}, not a numeric code."})))
        (set-error-fn native-segment error-name))

      (throw (ex-info "Unknown Zig error-union branch"
                      {:type zig-type :branch branch
                       :expected #{:ok :error}}))))
  native-segment)

(defn- write-value-segment!
  [^MemorySegment native-segment zig-type schema value context]
  (cond
    (= :packed-struct (:kind schema))
    (write-packed-struct! native-segment schema value)

    (= :struct (:kind schema))
    (write-struct! native-segment schema value)

    (contains? #{:array :vector} (:kind schema))
    (write-array! native-segment schema value)

    (= :enum (:kind schema))
    (write-enum! native-segment schema value)

    (= :union (:kind schema))
    (write-union! native-segment schema value)

    (= :optional (:kind schema))
    (write-optional! native-segment schema value)

    (= :pointer (:kind schema))
    (write-pointer! native-segment zig-type schema value)

    (= :slice (:kind schema))
    (write-slice! native-segment zig-type schema value)

    (= :error-union (:kind schema))
    (write-error-union! native-segment zig-type schema value)

    (= :void zig-type)
    (when-not (nil? value)
      (throw (ex-info "Zig void requires nil"
                      (assoc context :type zig-type :value value))))

    (= :bool zig-type)
    (do
      (when-not (instance? Boolean value)
        (throw (ex-info "Zig bool field requires true or false"
                        (assoc context :type zig-type :value value))))
      (.set native-segment java.lang.foreign.ValueLayout/JAVA_BYTE 0
            (byte (if value 1 0))))

    (integer-type zig-type (.byteSize native-segment))
    (write-native-integer! native-segment zig-type value context)

    (= :f32 zig-type)
    (.set native-segment java.lang.foreign.ValueLayout/JAVA_FLOAT 0
          (float value))

    (= :f64 zig-type)
    (.set native-segment java.lang.foreign.ValueLayout/JAVA_DOUBLE 0
          (double value))

    :else
    (throw (ex-info "Clojure construction is not implemented for this Zig field type"
                    (assoc context :type zig-type :schema schema
                           :value value))))
  native-segment)

(defn write-value!
  "Write one checked semantic value into caller-owned native Zig storage.
  Public for Aguafria runtime bridges; users normally call constructors or
  `az/set-value!`."
  ([^MemorySegment native-segment zig-type schema value]
   (write-value-segment! native-segment zig-type schema value {}))
  ([^MemorySegment native-segment zig-type schema value arena]
   (binding [*allocation-arena* arena]
     (write-value-segment! native-segment zig-type schema value {}))))

(defn write-struct!
  "Encode a field map (or a sequence for a tuple) using offsets and storage
  sizes reported by Zig itself. Omitted fields use Zig's defaults;
  fields without a default retain the JVM constructor's zero initialization."
  [^MemorySegment native-segment {:keys [fields tuple?] :as schema} field-values]
  (let [field-values
        (if (and tuple? (sequential? field-values))
          (do
            (when-not (= (count fields) (count field-values))
              (throw (ex-info "Wrong number of Zig tuple elements"
                              {:schema schema :expected (count fields)
                               :actual (count field-values)})))
            (into {} (map (fn [field item] [(field-key (:name field)) item])
                          fields field-values)))
          field-values)]
    (validate-field-map! "Zig struct values" schema field-values)
    (.fill native-segment (byte 0))
    (doseq [{:keys [byte-offset byte-size type] :as field} fields
            :let [value (field-value field-values field)]
            :when (or (field-present? field-values field) (:default-segment field))]
      (write-native-field! (.asSlice native-segment byte-offset byte-size)
                           field value))
    native-segment))

(defn decoded
  "Decode a Zig value into its natural Clojure view while retaining the native
  backing value for round trips. Arbitrary-width integers are exact."
  [^ZigValue zig-value]
  (try
    (let [{:keys [representation value segment schema decoded-fn]}
          (realize! zig-value)
          zig-type (type zig-value)]
      (if (= :scalar representation)
        value
        (cond
          decoded-fn (decoded-fn segment)
          (:kind schema) (decode-value-segment segment zig-type schema)
          :else ((requiring-resolve 'aguafria.zig.jvm/inspect-value!) zig-value))))
    (finally
      ;; Cleaner ownership is attached to the ZigValue rather than the raw
      ;; segment. The JVM may otherwise prove the wrapper dead while a long
      ;; composite decode is still reading its arena.
      (java.lang.ref.Reference/reachabilityFence zig-value))))

(defn- native-tuple-length
  [zig-value]
  (let [{:keys [tuple-length schema]} (realize! zig-value)]
    (or tuple-length
        (when (:tuple? schema) (count (:fields schema))))))

(defn- sequence-values
  [zig-value]
  (if-some [length (native-tuple-length zig-value)]
    (map #((requiring-resolve 'aguafria.zig/index) zig-value %) (range length))
    (decoded zig-value)))

(defn- sequence-element
  ([zig-value index]
   (if-some [length (native-tuple-length zig-value)]
     (if (< -1 index length)
       ((requiring-resolve 'aguafria.zig/index) zig-value index)
       (throw (IndexOutOfBoundsException. (str index))))
     (nth (decoded zig-value) index)))
  ([zig-value index not-found]
   (if-some [length (native-tuple-length zig-value)]
     (if (< -1 index length)
       ((requiring-resolve 'aguafria.zig/index) zig-value index)
       not-found)
     (nth (decoded zig-value) index not-found))))

(defn error-bearing-schema?
  "Whether a compiler-reported storage schema contains image-local error IDs."
  [schema]
  (or (= :error-union (:kind schema))
      (some error-bearing-schema?
            (keep #(get schema %) [:child-schema :element-schema :payload-schema]))
      (some #(error-bearing-schema? (:schema %)) (:fields schema))))

(defn copy-native!
  "Copy native storage without transferring image-local error integers.
  By-value aggregates retain pointer bytes and ownership. Error-bearing borrowed
  storage requires a dedicated alias-preserving bridge, never a silent deep copy."
  [^MemorySegment destination destination-schema
   ^MemorySegment source source-schema]
  (when-not (= (.byteSize destination) (.byteSize source))
    (throw (ex-info "Native storage layouts differ across images"
                    {:source-size (.byteSize source)
                     :destination-size (.byteSize destination)})))
  (if (or (identical? destination-schema source-schema)
          (not (or (error-bearing-schema? source-schema)
                   (error-bearing-schema? destination-schema))))
    (.copyFrom destination source)
    (let [kind (:kind source-schema)]
      (when-not (= kind (:kind destination-schema))
        (throw (ex-info "Missing compiler schema for cross-image error transport"
                        {:source-kind kind :destination-kind (:kind destination-schema)})))
      (case kind
        :error-union
        (if-let [error ((:error-fn source-schema) source)]
          ((:set-error-fn destination-schema) destination (:name error))
          (if (zero? (:payload-size source-schema))
            ((:set-ok-fn destination-schema) destination nil)
            (let [payload (.asSlice destination 0 (long (:payload-size destination-schema)))]
              (copy-native! payload (:payload-schema destination-schema)
                            ((:payload-segment-fn source-schema) source)
                            (:payload-schema source-schema))
              ((:set-ok-fn destination-schema) destination payload))))

        :optional
        (if-not ((:present-fn source-schema) source)
          ((:set-fn destination-schema) destination false nil)
          (let [payload (.asSlice destination 0 (long (:payload-size destination-schema)))]
            (copy-native! payload (:child-schema destination-schema)
                          ((:payload-segment-fn source-schema) source)
                          (:child-schema source-schema))
            ((:set-fn destination-schema) destination true payload)))

        (:array :vector)
        (let [length (long (or (:storage-length source-schema) (:length source-schema)))
              from-size (or (:element-size source-schema)
                            (when (pos? length) (quot (.byteSize source) length)))
              to-size (or (:element-size destination-schema)
                          (when (pos? length) (quot (.byteSize destination) length)))]
          (doseq [index (range length)]
            (copy-native! (.asSlice destination (* index to-size) to-size)
                          (:element-schema destination-schema)
                          (.asSlice source (* index from-size) from-size)
                          (:element-schema source-schema))))

        (throw (ex-info "Cross-image error transport requires an alias-preserving bridge for this storage"
                        {:aguafria/phase :native-error-transport :kind kind
                         :reason :unsupported-error-bearing-storage})))))
  destination)

(defn set-value!
  "Write a semantic Clojure value into a live native `az/defvar` and return
  its decoded value. This does not compile or publish code. Callers must obey
  Zig's normal synchronization rules when native threads access the same var."
  [^ZigValue zig-value new-value]
  (let [descriptor (.-descriptor zig-value)]
    (when-not (= :var (:kind descriptor))
      (throw (ex-info "Only an az/defvar Zig value is mutable"
                      (merge (info zig-value) {:value new-value}))))
    (let [{:keys [representation segment schema]} (realize! zig-value)]
      (when-not (= :native representation)
        (throw (ex-info "Mutable Zig state has no native storage"
                        (info zig-value))))
      (if (zig-value? new-value)
        (do
          (when-not (= (qualified-type zig-value) (qualified-type new-value))
            (throw (ex-info "Native assignment requires the target's Zig type"
                            {:expected (type zig-value) :actual (type new-value)})))
          (copy-native! segment schema
                        (aguafria.zig.value/segment new-value)
                        (:schema (realize! new-value)))
          (swap! (value-state zig-value) update :owners (fnil conj []) new-value))
        (binding [*allocation-arena* (:allocation-arena schema)]
          (write-value-segment! segment (:type descriptor) schema new-value
                                {:module (:module descriptor)
                                 :name (:name descriptor)})))
      (when (and (= :union (:kind schema))
                 (not (:tagged? schema))
                 (map? new-value)
                 (= 1 (count new-value)))
        (swap! (value-state zig-value) assoc-in [:schema :active-field]
               (field-key (ffirst new-value))))
      (decoded zig-value))))

(defn type
  "Return the Zig type form recorded for this value."
  [^ZigValue zig-value]
  (:type (.-descriptor zig-value)))

(defn qualified-type
  "Return a native value's type with named references anchored to its module.
  Moving a handle between JVM namespaces must not change its type identity."
  [zig-value]
  (let [module (:module (info zig-value))]
    (letfn [(qualify [form]
              (cond
                (and module (symbol? form) (nil? (namespace form))
                     (not (:aguafria/local? (meta form))))
                (with-meta (symbol module (name form)) (meta form))

                (vector? form) (mapv qualify form)
                (seq? form)
                (let [[operator & arguments] form
                      ;; Prepared types retain intrinsic operators such as
                      ;; `type`; only declaration references name a module.
                      operator (if (:aguafria/zig-reference (meta operator))
                                 (qualify operator)
                                 operator)]
                  (with-meta (apply list operator (map qualify arguments)) (meta form)))
                :else form))]
      (qualify (type zig-value)))))

(defn native-value
  "Create a lazy Zig value. Public for tooling; normal users receive these
  from `az/defconst` and function results."
  [descriptor materialize]
  (let [descriptor (cond-> descriptor
                     (:threadlocal? descriptor)
                     (assoc :thread-states
                            (proxy [ThreadLocal] []
                              (initialValue [] (atom {:status :pending})))))]
    (ZigValue. descriptor (atom {:status :pending}) materialize)))

(defn array-element-view
  "Borrow one array element as an immutable native value, retaining its owner."
  [array-value index]
  (let [{:keys [segment schema generation alignment]} (realize! array-value)
        {:keys [length element-type element-schema]} schema]
    (when-not (and (integer? index) (<= 0 index) (< index length))
      (throw (ex-info "Array element index is out of bounds" {:index index :length length})))
    (let [element-size (quot (.byteSize ^MemorySegment segment) length)]
      (native-value
       {:module (:module (info array-value)) :kind :const :type element-type}
       (constantly {:representation :native
                    :segment (.asSlice ^MemorySegment segment (* index element-size) element-size)
                    :size element-size :alignment alignment
                    :schema element-schema :generation generation
                    :owners [array-value]})))))

(defn retain-owners!
  "Keep borrowed-view owners alive for as long as the returned native value."
  [result owners]
  (when (zig-value? result)
    (realize! result)
    (swap! (value-state result) update :owners (fnil into []) owners))
  result)

(defn retain-mutation-owners!
  "Retain assigned pointer/slice backing storage on a mutable view and its
  mutable parents. Updating a temporary nested view must outlive that view."
  [target owners]
  (let [visited (java.util.IdentityHashMap.)]
    (letfn [(retain [v]
              (when (and (zig-value? v) (= :var (:kind (info v)))
                         (not (.containsKey visited v)))
                (.put visited v true)
                (let [parents (:owners (realize! v))]
                  (retain-owners! v owners)
                  (doseq [parent parents] (retain parent)))))]
      (retain target)))
  target)

(defn address-value
  "Own a pointer to existing storage, retaining its pointee without copying it."
  [owner mutable?]
  (let [{:keys [segment pointer-alignment]} (realize! owner)
        child (qualified-type owner)
        type (if-let [alignment (or pointer-alignment (:align (info owner)))]
               [:* {:const? (not mutable?) :align alignment} child]
               [(if mutable? :* :*const) child])
        arena (Arena/ofShared)]
    (try
      (let [storage (.allocate arena java.lang.foreign.ValueLayout/ADDRESS)
            result (native-value
                    {:kind :const :type type}
                    (constantly {:representation :native
                                 :segment storage
                                 :size (.byteSize storage)
                                 :alignment (.byteAlignment java.lang.foreign.ValueLayout/ADDRESS)
                                 :owners [owner]
                                 :schema {:kind :pointer :child-type child}
                                 :close! #(.close arena)}))]
        (.set storage java.lang.foreign.ValueLayout/ADDRESS 0 segment)
        (realize! result)
        result)
      (catch Throwable failure
        (.close arena)
        (throw failure)))))

(defn array-elements-pointer
  "Borrow an ordinary array's element storage as a many-item pointer.
  Length stays with the caller for native bounds checks; the owner stays live."
  [owner]
  (let [type (qualified-type owner)]
    (when-not (and (vector? type) (= :array (first type)) (= 3 (count type)))
      (throw (ex-info "Expected an ordinary native array" {:type type})))
    (let [mutable? (= :var (:kind (info owner)))
          pointer (address-value owner mutable?)
          state (realize! pointer)]
      (native-value
       {:kind :const :type [(if mutable? :many :many-const) (nth type 2)]}
       (constantly (-> (select-keys state [:representation :segment :size :alignment])
                       (assoc :owners [owner pointer]
                              :schema {:kind :pointer :child-type (nth type 2)})))))))

(defn- storage-alignment
  [alignment]
  (let [alignment (if (zig-value? alignment) (decoded alignment) alignment)]
    (when-not (and (integer? alignment) (pos? alignment)
                   (<= alignment Long/MAX_VALUE)
                   (zero? (bit-and alignment (dec alignment))))
      (throw (ex-info "Alignment must be a positive power of two" {:alignment alignment})))
    (long alignment)))

(defn- storage-copy
  "Copy owned native storage into an independently owned JVM handle.
  Slice/pointer owners remain reachable; new pointees live in the copy's arena."
  [source zig-type schema options kind]
  (let [{:keys [segment size alignment tuple-length]} (realize! source)
        requested-alignment (storage-alignment (get options :align 1))
        alignment (max alignment requested-alignment)
        arena (Arena/ofShared)]
    (try
      (let [storage (.allocate arena (long size) (long alignment))]
        (.copyFrom storage segment)
        (let [result (native-value
                      (merge (select-keys (info source) [:module :execution-context])
                             {:kind kind :type zig-type}
                             (when (contains? options :align) {:align requested-alignment}))
                      (constantly {:representation :native
                                   :segment storage :size size :alignment alignment
                                   :tuple-length tuple-length
                                   :owners [source]
                                   :schema (assoc schema :allocation-arena arena)
                                   :close! #(.close arena)}))]
          (realize! result)
          result))
      (catch Throwable failure
        (.close arena)
        (throw failure)))))

(defn mutable-copy
  "Copy native storage into a mutable handle, preserving pointee owners."
  ([source zig-type schema] (mutable-copy source zig-type schema {}))
  ([source zig-type schema options]
   (storage-copy source zig-type schema options :var)))

(defn aligned-copy
  "Own a copy with explicit storage alignment, preserving constness and type."
  [source alignment]
  (storage-copy source (qualified-type source) (:schema (realize! source))
                {:align alignment} (:kind (info source))))

(defn slice-element-view
  "Borrow an element's native storage, retaining the slice and its library owner."
  [slice-value index mutable?]
  (let [{:keys [schema generation]} (realize! slice-value)
        {:keys [element-type element-schema element-size element-alignment read-fn]} schema
        {:keys [address length]} (read-fn (segment slice-value))]
    (when-not (and (integer? index) (<= 0 index) (< index length))
      (throw (ex-info "Slice element index is out of bounds" {:index index :length length})))
    (native-value
     {:module (:module (info slice-value))
      :kind (if mutable? :var :const)
      :type element-type}
     (constantly {:representation :native
                  :segment (.reinterpret (MemorySegment/ofAddress
                                          (+ address (* index element-size))) element-size)
                  :size element-size :alignment element-alignment
                  :schema element-schema :generation generation
                  :owners [slice-value]}))))

(defmethod print-method ZigValue
  [value ^java.io.Writer writer]
  (.write writer "#aguafria.zig.value.ZigValue[")
  (print-method (decoded value) writer)
  (.write writer "]"))

(defmethod print-dup ZigValue
  [value ^java.io.Writer writer]
  ;; This is an inspection tag, not a promise to reconstruct native ownership.
  (print-method value writer))

(defmethod pprint/simple-dispatch ZigValue
  [value]
  (pprint/pprint-logical-block :prefix "#aguafria.zig.value.ZigValue[" :suffix "]"
                               (pprint/write-out (decoded value))))

(defmethod print-method ZigType
  [zig-type ^java.io.Writer writer]
  (.write writer "#aguafria/zig-type ")
  (print-method (dissoc (type-info zig-type) :logical-id :schema-fingerprint) writer))

(defmethod pprint/simple-dispatch ZigType
  [zig-type]
  (pprint/write-out (dissoc (type-info zig-type) :logical-id :schema-fingerprint)))

(defmethod print-method ZigPointer
  [pointer ^java.io.Writer writer]
  (.write writer (.toString pointer)))

(defmethod pprint/simple-dispatch ZigPointer
  [pointer]
  (print pointer))
