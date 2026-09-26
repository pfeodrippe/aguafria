(ns learn.example.poc-print-fn
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defstruct Writer
  [(az/fn print [:error-union :anyerror :void]
     "Calls print and then flushes the buffer."
     [[self [:* Writer]]
      [format {:attrs #{k/comptime}} [:slice-const :u8]]
      [args :anytype]]
     (let [State (az/enum
                  [:start
                   :open-brace
                   :close-brace])
           start-index (k/var 0 :usize {:attrs #{k/comptime}})
           state (k/var (:start State) nil {:attrs #{k/comptime}})
           next-arg (k/var 0 :usize {:attrs #{k/comptime}})]
       (az/inline-for [c format i (az/range 0)]
                      (az/switch-stmt state
                                      (case [(:start State)]
                                        (az/switch-stmt c
                                                        (case [\{]
                                                          (do
                                                            (when (k/< start-index i)
                                                              (try ((:write self) (az/slice format start-index i))))
                                                            (k/= state (:open-brace State))))
                                                        (case [\}]
                                                          (do
                                                            (when (k/< start-index i)
                                                              (try ((:write self) (az/slice format start-index i))))
                                                            (k/= state (:close-brace State))))
                                                        (az/case-else (do))))
                                      (case [(:open-brace State)]
                                        (az/switch-stmt c
                                                        (case [\{]
                                                          (do
                                                            (k/= state (:start State))
                                                            (k/= start-index i)))
                                                        (case [\}]
                                                          (do
                                                            (try ((:print-value self) (az/get args next-arg)))
                                                            (k/+= next-arg 1)
                                                            (k/= state (:start State))
                                                            (k/= start-index (k/+ i 1))))
                                                        (case [\s] (k/continue))
                                                        (az/case-else
                                                         (k/compileError
                                                          (k/++ "Unknown format character: " (az/init [c] [:array 1 :u8]))))))
                                      (case [(:close-brace State)]
                                        (az/switch-stmt c
                                                        (case [\}]
                                                          (do
                                                            (k/= state (:start State))
                                                            (k/= start-index i)))
                                                        (az/case-else
                                                         (k/compileError "Single '}' encountered in format string"))))))
       (az/comptime-stmt
        (do
          (when (k/!= (:len args) next-arg)
            (k/compileError "Unused arguments"))
          (when (k/!= state (:start State))
            (k/compileError (k/++ "Incomplete format string: " format)))))
       (when (k/< start-index (:len format))
         (try ((:write self) (az/slice format start-index (:len format)))))
       (try ((:flush self)))))

   (az/fn- write [:error-union :void]
           [[self [:* Writer]] [value [:slice-const :u8]]]
           (k/= :_ self)
           (k/= :_ value))

   (az/fn print-value [:error-union :void]
     [[self [:* Writer]] [value :anytype]]
     (k/= :_ self)
     (k/= :_ value))

   (az/fn- flush [:error-union :void]
           [[self [:* Writer]]]
           (k/= :_ self))])
