(ns aguafria.zig.value
  "Inspectable Clojure handles for Zig values that do not have an exact JVM
  representation. The backing bytes are materialized lazily by Aguafria and
  remain native memory; no lossy Java coercion is required."
  (:refer-clojure :exclude [bytes type])
  (:require [clojure.pprint :as pprint])
  (:import [java.lang.ref Cleaner Cleaner$Cleanable]
           [java.lang.foreign Arena MemorySegment]))

(declare decoded info realize! type type-info
         decode-packed-backing decode-struct decode-value-segment
         encode-packed-backing
         write-struct! write-value-segment!)

(defonce ^:private ^Cleaner native-cleaner (Cleaner/create))

(def ^:dynamic *allocation-arena*
  "Arena used for pointee storage while encoding values such as Zig slices."
  nil)

(deftype ZigPointer [address zigType]
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

(deftype ZigType [descriptor construct]
  clojure.lang.IFn
  (invoke [_ value]
    (construct value))

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

(deftype ZigValue [descriptor state materialize]
  clojure.lang.IDeref
  (deref [this]
    (decoded this))

  java.lang.AutoCloseable
  (close [this]
    (when-not (= :closed (:status @state))
      (let [{:keys [close! cleanable]} (realize! this)]
        (if cleanable
          (.clean ^Cleaner$Cleanable cleanable)
          (do
            (when close! (close!))
            (swap! state assoc :status :closed)))))
    nil)

  Object
  (toString [this]
    (pr-str (decoded this))))

(defn zig-value?
  "True when value is an Aguafria native Zig value handle."
  [value]
  (instance? ZigValue value))

(defn info
  "Return a small, printable view without forcing native compilation."
  [^ZigValue value]
  (let [state-map @(.-state value)]
    (merge
     (select-keys (.-descriptor value)
                  [:module :name :kind :type :logical-id])
     (select-keys state-map
                  [:status :representation :size :alignment :generation]))))

(defn realize!
  "Materialize a Zig value once and return its internal representation map."
  [^ZigValue value]
  (let [state (.-state value)]
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
        value (if (and signed? (.testBit unsigned-value (dec bits)))
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
    (when-not (identical? missing result)
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
                  (= :bool type) (if value
                                   java.math.BigInteger/ONE
                                   java.math.BigInteger/ZERO)
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
        :else
        (when-let [[_ signed-marker bit-count]
                   (re-matches #"([iu])(\d+)" type-name)]
          {:signed? (= "i" signed-marker)
           :bits (Long/parseLong bit-count)})))))

(defn- integer-value
  [{:keys [signed? bits] :as integer-type} value context]
  (let [integer (cond
                  (instance? java.math.BigInteger value) value
                  (integer? value) (biginteger value)
                  :else (throw (ex-info "Zig integer requires a Clojure integer"
                                        (assoc context
                                               :integer-type integer-type
                                               :value value))))
        modulus (.shiftLeft java.math.BigInteger/ONE bits)
        minimum (if signed?
                  (.negate (.shiftRight modulus 1))
                  java.math.BigInteger/ZERO)
        maximum (if signed?
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
   {:keys [length storage-length element-type element-schema]}]
  (let [storage-length (long (or storage-length length))
        element-size (if (zero? storage-length)
                       0
                       (quot (.byteSize native-segment) storage-length))]
    (mapv (fn [index]
            (decode-value-segment
             (.asSlice native-segment (* index element-size) element-size)
             element-type
             element-schema))
          (range length))))

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
  [^MemorySegment native-segment {:keys [fields]}]
  (into (array-map)
        (map (fn [{:keys [name byte-offset byte-size] :as field}]
               [(field-key name)
                (decode-native-field
                 (.asSlice native-segment byte-offset byte-size)
                 field)]))
        fields))

(defn- write-native-field!
  [^MemorySegment field-segment {:keys [name type] :as field} value]
  (write-value-segment! field-segment type (:schema field) value
                        {:field name}))

(defn write-array!
  [^MemorySegment native-segment
   {:keys [length storage-length element-type element-schema sentinel]
    :as schema}
   values]
  (when-not (sequential? values)
    (throw (ex-info "Zig arrays and vectors require a sequential Clojure value"
                    {:schema schema :value values})))
  (when-not (= length (count values))
    (throw (ex-info "Wrong number of Zig array/vector elements"
                    {:schema schema :expected length :actual (count values)})))
  (let [storage-length (long (or storage-length length))
        element-size (if (zero? storage-length)
                       0
                       (quot (.byteSize native-segment) storage-length))
        storage-values (cond-> (vec values)
                         (> storage-length length) (conj sentinel))]
    (doseq [[index value] (map-indexed vector storage-values)]
      (write-value-segment!
       (.asSlice native-segment (* index element-size) element-size)
       element-type element-schema value {:index index})))
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
  (when-not (sequential? values)
    (throw (ex-info "Zig slices require a sequential Clojure value"
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
  native-segment)

(defn- write-error-union!
  [^MemorySegment native-segment zig-type
   {:keys [payload-type payload-schema payload-size set-ok-fn set-error-fn]}
   value]
  (when-not (and (map? value) (= 1 (count value)))
    (throw (ex-info "Zig error unions require {:ok value} or {:error {:code n}}"
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
      (let [code (cond
                   (integer? branch-value) branch-value
                   (map? branch-value) (:code branch-value)
                   :else nil)]
        (when-not (and (integer? code) (pos? (biginteger code)))
          (throw (ex-info
                  "A Zig error value requires its positive native :code"
                  {:type zig-type :value value
                   :hint "A decoded {:error {:name ... :code ...}} value can be passed back directly."})))
        (set-error-fn native-segment code))

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
  "Encode a Clojure field map into a normal or extern struct using offsets and
  storage sizes reported by Zig itself. Missing fields are zero-initialized."
  [^MemorySegment native-segment {:keys [fields] :as schema} field-values]
  (validate-field-map! "Zig struct values" schema field-values)
  (.fill native-segment (byte 0))
  (doseq [{:keys [byte-offset byte-size type] :as field} fields
          :let [value (field-value field-values field)]
          :when (field-present? field-values field)]
    (write-native-field! (.asSlice native-segment byte-offset byte-size)
                         field value))
  native-segment)

(defn decoded
  "Decode a Zig value into its natural Clojure view while retaining the native
  backing value for round trips. Arbitrary-width integers are exact."
  [^ZigValue zig-value]
  (let [{:keys [representation value segment schema decoded-fn]}
        (realize! zig-value)
        zig-type (type zig-value)]
    (if (= :scalar representation)
      value
      (cond
        decoded-fn (decoded-fn segment)
        schema
        (decode-value-segment segment zig-type schema)
        :else
        (if-let [[_ signed-marker bit-count]
                 (and (keyword? zig-type)
                      (re-matches #"([iu])(\d+)" (name zig-type)))]
          (decode-integer segment (= "i" signed-marker)
                          (Long/parseLong bit-count))
          ;; Unknown composites remain lossless and inspectable while their
          ;; schema decoder is added.
          (bytes zig-value))))))

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
      (binding [*allocation-arena* (:allocation-arena schema)]
        (write-value-segment! segment (:type descriptor) schema new-value
                              {:module (:module descriptor)
                               :name (:name descriptor)}))
      (when (and (= :union (:kind schema))
                 (not (:tagged? schema))
                 (map? new-value)
                 (= 1 (count new-value)))
        (swap! (.-state zig-value) assoc-in [:schema :active-field]
               (field-key (ffirst new-value))))
      (decoded zig-value))))

(defn type
  "Return the Zig type form recorded for this value."
  [^ZigValue zig-value]
  (:type (.-descriptor zig-value)))

(defn native-value
  "Create a lazy Zig value. Public for tooling; normal users receive these
  from `az/defconst` and function results."
  [descriptor materialize]
  (ZigValue. descriptor (atom {:status :pending}) materialize))

(defmethod print-method ZigValue
  [value ^java.io.Writer writer]
  (print-method (decoded value) writer))

(defmethod print-dup ZigValue
  [value ^java.io.Writer writer]
  ;; Native pointers are process-local, so print the portable decoded value
  ;; instead of pretending the wrapper itself can be read back.
  (print-dup (decoded value) writer))

(defmethod pprint/simple-dispatch ZigValue
  [value]
  (pprint/write-out (decoded value)))

(defmethod print-method ZigType
  [zig-type ^java.io.Writer writer]
  (.write writer "#aguafria/zig-type ")
  (print-method (type-info zig-type) writer))

(defmethod pprint/simple-dispatch ZigType
  [zig-type]
  (pprint/write-out (type-info zig-type)))

(defmethod print-method ZigPointer
  [pointer ^java.io.Writer writer]
  (.write writer (.toString pointer)))

(defmethod pprint/simple-dispatch ZigPointer
  [pointer]
  (print pointer))
