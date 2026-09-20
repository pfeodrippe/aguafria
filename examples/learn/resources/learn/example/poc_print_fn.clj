(ns learn.example.poc-print-fn
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defstruct Writer
  [(az/fn-decl print [:error-union :anyerror :void]
     "Print the formatted arguments, then flush the buffer."
     {:attrs #{:public}}
     [[self [:* Writer]]
      [format {:zig/prefix "comptime"} [:slice-const :u8]]
      [arguments :anytype]]
     (let [State (az/enum
                   [:start
                    :open-brace
                    :close-brace])
           start-index (ak/var 0 :usize {:zig/prefix "comptime"})
           state (ak/var (az/field State :start) nil {:zig/prefix "comptime"})
           next-argument (ak/var 0 :usize {:zig/prefix "comptime"})]
       (az/inline-for [[character format] [index (az/op ".." 0)]]
         (az/switch-stmt state
           (case [(az/field State :start)]
             (az/switch-stmt character
               (case [\{]
                 (do
                   (when (< start-index index)
                     (try ((az/field self :write) (az/slice format start-index index))))
                   (ak/= state (az/field State :open-brace))))
               (case [\}]
                 (do
                   (when (< start-index index)
                     (try ((az/field self :write) (az/slice format start-index index))))
                   (ak/= state (az/field State :close-brace))))
               (az/case-else (do))))
           (case [(az/field State :open-brace)]
             (az/switch-stmt character
               (case [\{]
                 (do
                   (ak/= state (az/field State :start))
                   (ak/= start-index index)))
               (case [\}]
                 (do
                   (try ((az/field self :print-value) (az/index arguments next-argument)))
                   (ak/+= next-argument 1)
                   (ak/= state (az/field State :start))
                   (ak/= start-index (+ index 1))))
               (case [\s] (ak/continue))
               (az/case-else
                 (ak/compileError
                  (ak/++ "Unknown format character: " (az/array-init [character] [:array 1 :u8]))))))
           (case [(az/field State :close-brace)]
             (az/switch-stmt character
               (case [\}]
                 (do
                   (ak/= state (az/field State :start))
                   (ak/= start-index index)))
               (az/case-else
                 (ak/compileError "Single '}' encountered in format string"))))))
       (az/comptime-stmt
         (do
           (when (ak/!= (az/field arguments :len) next-argument)
             (ak/compileError "Unused arguments"))
           (when (ak/!= state (az/field State :start))
             (ak/compileError (ak/++ "Incomplete format string: " format)))))
       (when (< start-index (az/field format :len))
         (try ((az/field self :write) (az/slice format start-index (az/field format :len)))))
       (try ((az/field self :flush)))))

   (az/fn-decl write [:error-union :void]
     [[self [:* Writer]] [value [:slice-const :u8]]]
     (ak/= :_ self)
     (ak/= :_ value))

   (az/fn-decl print-value [:error-union :void] {:attrs #{:public}}
     [[self [:* Writer]] [value :anytype]]
     (ak/= :_ self)
     (ak/= :_ value))

   (az/fn-decl flush [:error-union :void]
     [[self [:* Writer]]]
     (ak/= :_ self))])
