(ns ghostty-agua.native
  "Same-JVM access to the regenerated standalone libghostty-vt.

  The terminal handle and all of its state live in native Ghostty memory, but
  the owning object remains an ordinary inspectable Clojure value. No helper
  process is involved."
  (:refer-clojure :exclude [reset!])
  (:require [clojure.java.io :as io])
  (:import [java.lang.foreign Arena FunctionDescriptor Linker MemoryLayout
            Linker$Option MemorySegment SymbolLookup ValueLayout]
           [java.lang.invoke MethodHandle]
           [java.nio.file Path]
           [java.util ArrayList]))

(def ^:private result-names
  {0 :success
   -1 :out-of-memory
   -2 :invalid-value
   -3 :out-of-space
   -4 :no-value
   -5 :io-error
   -6 :limit-exceeded})

(def ^:private terminal-data
  {:cols [1 :u16]
   :rows [2 :u16]
   :cursor-x [3 :u16]
   :cursor-y [4 :u16]
   :cursor-pending-wrap? [5 :bool]
   :active-screen [6 :i32]
   :cursor-visible? [7 :bool]
   :kitty-keyboard-flags [8 :u8]
   :mouse-tracking? [11 :bool]
   :title [12 :string]
   :pwd [13 :string]
   :total-rows [14 :usize]
   :scrollback-rows [15 :usize]
   :width-px [16 :u32]
   :height-px [17 :u32]
   :viewport-active? [32 :bool]
   :vt-processing-error? [33 :bool]
   :continuation-max-bytes [36 :usize]})

(defrecord GhosttySession
  [^Arena arena
   ^MemorySegment terminal
   ^SymbolLookup library
   handlers
   library-path
   closed?])

(defmethod print-method GhosttySession
  [session ^java.io.Writer writer]
  (.write writer
          (str "#ghostty/session "
               (pr-str {:library (:library-path session)
                        :address (when-not @(:closed? session)
                                   (.address ^MemorySegment (:terminal session)))
                        :closed? @(:closed? session)}))))

(defn- repository-root
  []
  (or
   (some
    (fn [^java.io.File directory]
      (when (.isFile (io/file directory "src/aguafria/zig.clj"))
        (.getCanonicalFile directory)))
    (take-while some?
                (iterate #(.getParentFile ^java.io.File %)
                         (.getCanonicalFile
                          (io/file (System/getProperty "user.dir"))))))
   (throw (ex-info "Could not find the Aguafria checkout"
                   {:user-dir (System/getProperty "user.dir")}))))

(defn default-library-path
  "Return the regenerated standalone libghostty-vt dylib path."
  []
  (.getAbsolutePath
   (io/file (repository-root)
            "examples/ghostty/build/standalone/zig-out/lib/libghostty-vt.0.1.0.dylib")))

(defn- function-descriptor
  [return-layout argument-layouts]
  (let [arguments (into-array MemoryLayout argument-layouts)]
    (if return-layout
      (FunctionDescriptor/of return-layout arguments)
      (FunctionDescriptor/ofVoid arguments))))

(defn- native-function
  [^Linker linker ^SymbolLookup library name return-layout argument-layouts]
  (let [symbol (or (.orElse (.find library name) nil)
                   (throw (ex-info "libghostty-vt symbol was not found"
                                   {:symbol name})))
        descriptor (function-descriptor return-layout argument-layouts)]
    (.downcallHandle linker
                     ^MemorySegment symbol
                     ^FunctionDescriptor descriptor
                     (make-array Linker$Option 0))))

(defn- invoke
  [^MethodHandle handle arguments]
  (.invokeWithArguments handle (ArrayList. ^java.util.Collection arguments)))

(defn- handlers
  [^Linker linker ^SymbolLookup library]
  {:terminal-new
   (native-function linker library "ghostty_terminal_new"
                    ValueLayout/JAVA_INT
                    [ValueLayout/ADDRESS ValueLayout/ADDRESS
                     ValueLayout/JAVA_SHORT ValueLayout/JAVA_SHORT])
   :terminal-free
   (native-function linker library "ghostty_terminal_free" nil
                    [ValueLayout/ADDRESS])
   :terminal-reset
   (native-function linker library "ghostty_terminal_reset" nil
                    [ValueLayout/ADDRESS])
   :terminal-resize
   (native-function linker library "ghostty_terminal_resize"
                    ValueLayout/JAVA_INT
                    [ValueLayout/ADDRESS ValueLayout/JAVA_SHORT
                     ValueLayout/JAVA_SHORT ValueLayout/JAVA_INT
                     ValueLayout/JAVA_INT])
   :terminal-write
   (native-function linker library "ghostty_terminal_vt_write" nil
                    [ValueLayout/ADDRESS ValueLayout/ADDRESS
                     ValueLayout/JAVA_LONG])
   :terminal-get
   (native-function linker library "ghostty_terminal_get"
                    ValueLayout/JAVA_INT
                    [ValueLayout/ADDRESS ValueLayout/JAVA_INT
                     ValueLayout/ADDRESS])
   :type-json
   (native-function linker library "ghostty_type_json"
                    ValueLayout/ADDRESS [])})

(defn- ensure-open!
  [session]
  (when @(:closed? session)
    (throw (ex-info "Ghostty terminal session is closed"
                    {:library (:library-path session)})))
  session)

(defn- check-result!
  [operation result]
  (let [result (int result)]
    (when-not (zero? result)
      (throw (ex-info (str "Ghostty " (name operation) " failed: "
                           (name (get result-names result :unknown-result)))
                      {:operation operation
                       :result result
                       :result-name (get result-names result :unknown-result)})))
    result))

(defn open!
  "Open a real libghostty-vt terminal in this JVM.

  The library is the ReleaseFast artifact materialized from generated
  Aguafria sources. `cols` and `rows` default to 80 by 24."
  ([] (open! {}))
  ([{:keys [library-path cols rows]
     :or {cols 80 rows 24}}]
   (let [library-path (or library-path (default-library-path))
         file (io/file library-path)]
     (when-not (.isFile file)
       (throw (ex-info
               "Regenerated libghostty-vt is absent; run clojure -M:standalone"
               {:library-path (.getAbsolutePath file)})))
     (let [arena (Arena/ofShared)]
       (try
         (let [linker (Linker/nativeLinker)
               library (SymbolLookup/libraryLookup
                        ^Path (.toPath file) arena)
               handlers (handlers linker library)
               out (.allocate arena ValueLayout/ADDRESS)
               result (invoke (:terminal-new handlers)
                              [MemorySegment/NULL out
                               (short cols) (short rows)])
               _ (check-result! :terminal-new result)
               terminal (.get out ValueLayout/ADDRESS 0)]
           (when (zero? (.address terminal))
             (throw (ex-info "Ghostty returned a null terminal after success"
                             {:library-path (.getAbsolutePath file)})))
           (->GhosttySession arena terminal library handlers
                             (.getAbsolutePath file) (atom false)))
         (catch Throwable error
           (.close arena)
           (throw error)))))))

(defn close!
  "Free a Ghostty terminal and its FFM arena. Safe to call more than once."
  [session]
  (when (compare-and-set! (:closed? session) false true)
    (try
      (invoke (get-in session [:handlers :terminal-free])
              [(:terminal session)])
      (finally
        (.close ^Arena (:arena session)))))
  nil)

(defn write!
  "Feed UTF-8 text or arbitrary bytes through Ghostty's real VT parser."
  [session value]
  (ensure-open! session)
  (let [bytes (if (bytes? value)
                value
                (.getBytes (str value) java.nio.charset.StandardCharsets/UTF_8))
        segment (.allocate ^Arena (:arena session) (max 1 (alength bytes)))]
    (when (pos? (alength bytes))
      (.copyFrom segment (MemorySegment/ofArray bytes)))
    (invoke (get-in session [:handlers :terminal-write])
            [(:terminal session) segment (long (alength bytes))])
    session))

(defn reset!
  "Perform Ghostty's full terminal reset without replacing the session."
  [session]
  (ensure-open! session)
  (invoke (get-in session [:handlers :terminal-reset])
          [(:terminal session)])
  session)

(defn resize!
  "Resize the live terminal and update its cell pixel dimensions."
  ([session cols rows] (resize! session cols rows 8 16))
  ([session cols rows cell-width-px cell-height-px]
   (ensure-open! session)
   (->> (invoke (get-in session [:handlers :terminal-resize])
                [(:terminal session) (short cols) (short rows)
                 (int cell-width-px) (int cell-height-px)])
        (check-result! :terminal-resize))
   session))

(defn- read-c-string
  [^MemorySegment pointer]
  (when-not (zero? (.address pointer))
    (.getString (.reinterpret pointer (long (* 16 1024 1024))) 0)))

(defn type-layout-json
  "Return Ghostty's own target-specific C ABI layout description."
  [session]
  (ensure-open! session)
  (-> (invoke (get-in session [:handlers :type-json]) [])
      read-c-string))

(defn- get-data
  [session data-key]
  (let [[data-id type] (or (get terminal-data data-key)
                           (throw (ex-info "Unknown Ghostty terminal datum"
                                           {:data-key data-key
                                            :known (sort (keys terminal-data))})))
        arena ^Arena (:arena session)
        [layout size]
        (case type
          :u8 [ValueLayout/JAVA_BYTE 1]
          :bool [ValueLayout/JAVA_BYTE 1]
          :u16 [ValueLayout/JAVA_SHORT 2]
          :u32 [ValueLayout/JAVA_INT 4]
          :i32 [ValueLayout/JAVA_INT 4]
          :usize [ValueLayout/JAVA_LONG 8]
          :string [nil 16])
        out (.allocate arena (long size) (long (min size 8)))
        result (invoke (get-in session [:handlers :terminal-get])
                       [(:terminal session) (int data-id) out])]
    (check-result! :terminal-get result)
    (case type
      :u8 (bit-and 0xff (long (.get out layout 0)))
      :bool (not (zero? (long (.get out layout 0))))
      :u16 (bit-and 0xffff (long (.get out layout 0)))
      :u32 (bit-and 0xffffffff (long (.get out layout 0)))
      :i32 (int (.get out layout 0))
      :usize (long (.get out layout 0))
      :string (let [pointer (.get out ValueLayout/ADDRESS 0)
                    length (long (.get out ValueLayout/JAVA_LONG 8))]
                (if (zero? length)
                  ""
                  (let [bytes (.toArray (.reinterpret pointer length)
                                        ValueLayout/JAVA_BYTE)]
                    (String. bytes java.nio.charset.StandardCharsets/UTF_8)))))))

(defn state
  "Return an inspectable snapshot of live Ghostty terminal state."
  [session]
  (ensure-open! session)
  (into (sorted-map)
        (map (fn [data-key] [data-key (get-data session data-key)]))
        (keys terminal-data)))
