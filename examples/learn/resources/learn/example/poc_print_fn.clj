(ns learn.example.poc-print-fn
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defstruct Writer
  [(az/fn print [:error-union :anyerror :void]
     "Print the formatted arguments, then flush the buffer."
     [[self [:* Writer]]
      [format {:attrs #{k/comptime}} [:slice-const :u8]]
      [arguments :anytype]]
     (let [State (az/enum
                   [:start
                    :open-brace
                    :close-brace])
           start-index (k/var 0 :usize {:attrs #{k/comptime}})
           state (k/var (az/field State :start) nil {:attrs #{k/comptime}})
           next-argument (k/var 0 :usize {:attrs #{k/comptime}})]
       (az/inline-for [[character format] [index (az/op ".." 0)]]
         (az/switch-stmt state
           (case [(az/field State :start)]
             (az/switch-stmt character
               (case [\{]
                 (do
                   (when (k/< start-index index)
                     (try ((az/field self :write) (az/slice format start-index index))))
                   (k/= state (az/field State :open-brace))))
               (case [\}]
                 (do
                   (when (k/< start-index index)
                     (try ((az/field self :write) (az/slice format start-index index))))
                   (k/= state (az/field State :close-brace))))
               (az/case-else (do))))
           (case [(az/field State :open-brace)]
             (az/switch-stmt character
               (case [\{]
                 (do
                   (k/= state (az/field State :start))
                   (k/= start-index index)))
               (case [\}]
                 (do
                   (try ((az/field self :print-value) (az/index arguments next-argument)))
                   (k/+= next-argument 1)
                   (k/= state (az/field State :start))
                   (k/= start-index (k/+ index 1))))
               (case [\s] (k/continue))
               (az/case-else
                 (k/compileError
                  (az/op "++" "Unknown format character: " (az/array-init [character] [:array 1 :u8]))))))
           (case [(az/field State :close-brace)]
             (az/switch-stmt character
               (case [\}]
                 (do
                   (k/= state (az/field State :start))
                   (k/= start-index index)))
               (az/case-else
                 (k/compileError "Single '}' encountered in format string"))))))
       (az/comptime-stmt
         (do
           (when (k/!= (az/field arguments :len) next-argument)
             (k/compileError "Unused arguments"))
           (when (k/!= state (az/field State :start))
             (k/compileError (az/op "++" "Incomplete format string: " format)))))
       (when (k/< start-index (az/field format :len))
         (try ((az/field self :write) (az/slice format start-index (az/field format :len)))))
       (try ((az/field self :flush)))))

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
