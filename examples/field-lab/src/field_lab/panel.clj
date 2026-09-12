(ns field-lab.panel
  (:require [aguafria.c :as ac]
            [clojure.java.io :as io]))

(let [header (io/file "native/panel.h")
      output (io/file "generated/field_lab/panel_api.clj")]
  (ac/translate-header! header output {:namespace 'field-lab.panel-api :overwrite? true})
  (ac/load-bindings! output))
