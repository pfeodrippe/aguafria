(ns pitoco-plugin.abi
  (:require [aguafria.c :as ac]))

(ac/translate-header! "../../native/sdk/pitoco.h" "generated/pitoco_plugin/sdk.clj"
                      {:namespace 'pitoco-plugin.sdk :overwrite? true})

(ac/load-bindings! "generated/pitoco_plugin/sdk.clj")
