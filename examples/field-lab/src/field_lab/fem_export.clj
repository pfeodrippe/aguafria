(ns field-lab.fem-export
  "Portable static FEM results: original tetrahedra plus displacement/stress fields."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn von-mises
  "Equivalent stress in Pa from a symmetric row-major Cauchy stress tensor."
  [[xx xy xz _ yy yz _ _ zz]]
  (Math/sqrt (+ (* 0.5 (+ (Math/pow (- xx yy) 2.0)
                           (Math/pow (- yy zz) 2.0)
                           (Math/pow (- zz xx) 2.0)))
                (* 3.0 (+ (* xy xy) (* xz xz) (* yz yz))))))

(defn- data-array! [writer name type components rows]
  (.write writer (str "<DataArray type=\"" type "\" Name=\"" name
                      "\" NumberOfComponents=\"" components "\" format=\"ascii\">\n"))
  (doseq [row rows]
    (.write writer (str (str/join " " (if (sequential? row) row [row])) "\n")))
  (.write writer "</DataArray>\n"))

(defn write-vtu!
  "Write ParaView/VTK XML. Coordinates remain undeformed; use Warp By Vector
  with displacement_m and scale 1 for physical displacement. Stress is per tet."
  [result path]
  (let [{:keys [points cells]} (get-in result [:job :mesh])
        target (io/file path)]
    (io/make-parents target)
    (with-open [writer (io/writer target)]
      (.write writer "<?xml version=\"1.0\"?>\n<VTKFile type=\"UnstructuredGrid\" version=\"0.1\" byte_order=\"LittleEndian\">\n<UnstructuredGrid>\n")
      (.write writer (str "<Piece NumberOfPoints=\"" (count points) "\" NumberOfCells=\"" (count cells) "\">\n<Points>\n"))
      (data-array! writer "reference_position_m" "Float64" 3 points)
      (.write writer "</Points>\n<Cells>\n")
      (data-array! writer "connectivity" "Int64" 1 (flatten cells))
      (data-array! writer "offsets" "Int64" 1 (map #(* 4 %) (range 1 (inc (count cells)))))
      (data-array! writer "types" "UInt8" 1 (repeat (count cells) 10))
      (.write writer "</Cells>\n<PointData Vectors=\"displacement_m\">\n")
      (data-array! writer "displacement_m" "Float64" 3 (:displacements-m result))
      (data-array! writer "reaction_N" "Float64" 3 (:reactions-N result))
      (.write writer "</PointData>\n<CellData Scalars=\"von_mises_Pa\" Tensors=\"stress_Pa\">\n")
      (data-array! writer "stress_Pa" "Float64" 9 (:stress-Pa result))
      (data-array! writer "von_mises_Pa" "Float64" 1 (map von-mises (:stress-Pa result)))
      (.write writer "</CellData>\n</Piece>\n</UnstructuredGrid>\n</VTKFile>\n"))
    (.getCanonicalPath target)))
