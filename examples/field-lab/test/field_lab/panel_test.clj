(ns field-lab.panel-test
  "Standalone export contract probe. Run in a private directory, never over a live run."
  (:require [aguafria.zig :as az]
            [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [field-lab.panel :as panel]))

(az/defn main
  {:attrs #{:public}}
  :- :void []
  (let [file (panel/export-begin! 0.45 0.62 3.5 9.81 0.78 0.35 0.025 1.1 0.25 1.5
                                  2 (/ 1.0 240.0) 2 10000.0)]
    (debug/assert (ak/!= file null))
    (debug/assert (ak/== (panel/export-scene-source! file "bad" 2 (/ 1.0 240.0)) 0))
    (debug/assert (ak/== (panel/export-scene-source! file
                          "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef" 2 (/ 1.0 240.0)) 1))
    (debug/assert (ak/== (panel/export-scene-body! file 0 0.62 10000 0.4 0 -9.81 0 1 0.35) 1))
    (debug/assert (ak/== (panel/export-scene-body! file 1 1.25 20000 0.3 1 0 2 0 0.1) 1))
    (debug/assert (ak/== (panel/export-reference! file 1 7 0.1 -2.25 3.75) 1))
    (debug/assert (ak/== (panel/export-cell! file 1 2 0 1 2 3) 1))
    (debug/assert (ak/== (panel/export-sample! file 1 0.125 1 2 3 4 5 6 7 8 9 0 0 0 1 2.5 10) 1))
    (debug/assert (ak/== (panel/export-particle! file 1 7 0.125 1 2 3 4 5 6) 1))
    (debug/assert (ak/== (panel/export-end! file) 1)))
  (debug/assert (ak/== (panel/export-end! null) 0)))
