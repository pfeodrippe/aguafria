(ns learn.example.poc-print-fn
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defstruct Writer
  [(a/fn print [:error-union :anyerror :void]
     "Calls print and then flushes the buffer."
     [[self [:* Writer]]
      [format {:attrs #{k/comptime}} [:slice-const :u8]]
      [args :anytype]]
     (let [State (a/enum
                  [:start
                   :open-brace
                   :close-brace])
           start-index (k/var 0 :usize {:attrs #{k/comptime}})
           state (k/var (:start State) nil {:attrs #{k/comptime}})
           next-arg (k/var 0 :usize {:attrs #{k/comptime}})]
       (a/inline-for [c format i (a/range 0)]
                     (k/switch state
                       (:start State)
                       (k/switch c
                         \{
                         (do
                           (when (k/< start-index i)
                             (try ((:write self) (a/slice format start-index i))))
                           (k/= state (:open-brace State)))
                         \}
                         (do
                           (when (k/< start-index i)
                             (try ((:write self) (a/slice format start-index i))))
                           (k/= state (:close-brace State)))
                         (do))
                       (:open-brace State)
                       (k/switch c
                         \{
                         (do
                           (k/= state (:start State))
                           (k/= start-index i))
                         \}
                         (do
                           (try ((:print-value self) (a/get args next-arg)))
                           (k/+= next-arg 1)
                           (k/= state (:start State))
                           (k/= start-index (k/+ i 1)))
                         \s (k/continue)

                         (k/compileError
                          (k/++ "Unknown format character: " (a/init [c] [:array 1 :u8]))))
                       (:close-brace State)
                       (k/switch c
                         \}
                         (do
                           (k/= state (:start State))
                           (k/= start-index i))

                         (k/compileError "Single '}' encountered in format string"))))
       (k/comptime
        (do
          (when (k/!= (:len args) next-arg)
            (k/compileError "Unused arguments"))
          (when (k/!= state (:start State))
            (k/compileError (k/++ "Incomplete format string: " format)))))
       (when (k/< start-index (:len format))
         (try ((:write self) (a/slice format start-index (:len format)))))
       (try ((:flush self)))))

   (a/fn- write [:error-union :void]
          [[self [:* Writer]] [value [:slice-const :u8]]]
          (k/= :_ self)
          (k/= :_ value))

   (a/fn print-value [:error-union :void]
     [[self [:* Writer]] [value :anytype]]
     (k/= :_ self)
     (k/= :_ value))

   (a/fn- flush [:error-union :void]
          [[self [:* Writer]]]
          (k/= :_ self))])
