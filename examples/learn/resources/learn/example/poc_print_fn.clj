(ns learn.example.poc-print-fn
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defstruct Writer
  [(a/fn print [:error-union :anyerror :void]
     "Calls print and then flushes the buffer."
     [[self [:* Writer]]
      [format {:attrs #{comptime}} [:slice-const :u8]]
      [args :anytype]]
     (let [State (a/enum
                  [:start
                   :open-brace
                   :close-brace])
           start-index (k/var 0 :usize {:attrs #{comptime}})
           state (k/var (:start State) nil {:attrs #{comptime}})
           next-arg (k/var 0 :usize {:attrs #{comptime}})]
       (a/inline-for [c format i (a/range 0)]
                     (switch state
                       (:start State)
                       (switch c
                         \{
                         (do
                           (when (< start-index i)
                             (k/try ((:write self) (a/slice format start-index i))))
                           (= state (:open-brace State)))
                         \}
                         (do
                           (when (< start-index i)
                             (k/try ((:write self) (a/slice format start-index i))))
                           (= state (:close-brace State)))
                         (do))
                       (:open-brace State)
                       (switch c
                         \{
                         (do
                           (= state (:start State))
                           (= start-index i))
                         \}
                         (do
                           (k/try ((:print-value self) (a/get args next-arg)))
                           (+= next-arg 1)
                           (= state (:start State))
                           (= start-index (+ i 1)))
                         \s (continue)

                         (compileError
                          (++ "Unknown format character: " (a/init [c] [:array 1 :u8]))))
                       (:close-brace State)
                       (switch c
                         \}
                         (do
                           (= state (:start State))
                           (= start-index i))

                         (compileError "Single '}' encountered in format string"))))
       (comptime
        (do
          (when (!= (:len args) next-arg)
            (compileError "Unused arguments"))
          (when (!= state (:start State))
            (compileError (++ "Incomplete format string: " format)))))
       (when (< start-index (:len format))
         (k/try ((:write self) (a/slice format start-index (:len format)))))
       (k/try ((:flush self)))))

   (a/fn- write [:error-union :void]
          [[self [:* Writer]] [value [:slice-const :u8]]]
          (= :_ self)
          (= :_ value))

   (a/fn print-value [:error-union :void]
     [[self [:* Writer]] [value :anytype]]
     (= :_ self)
     (= :_ value))

   (a/fn- flush [:error-union :void]
          [[self [:* Writer]]]
          (= :_ self))])
