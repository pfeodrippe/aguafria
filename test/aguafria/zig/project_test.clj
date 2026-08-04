(ns aguafria.zig.project-test
  (:require [aguafria.zig.project :as project]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(deftest converted-module-assets-materialize-beside-cached-source-test
  (let [root (.toFile
              (java.nio.file.Files/createTempDirectory
               "aguafria-project-assets"
               (make-array java.nio.file.attribute.FileAttribute 0)))
        module (str "fixture.project-assets-" (gensym))
        catalog (io/file root "aguafria-project.edn")
        bundled (io/file root ".aguafria-assets/pkg/note.txt")
        target (io/file root "cache/dependencies/module/hash")]
    (io/make-parents bundled)
    (spit bundled "generated asset\n")
    (spit catalog
          (pr-str {:schema-version 1
                   :asset-root ".aguafria-assets"
                   :asset-files ["pkg/note.txt"]
                   :modules {module {:relative-path "pkg/module.zig"}}}))
    (project/load-catalog! catalog)
    (project/materialize-module-assets!
     module
     "const note = @embedFile(\"note.txt\");"
     target)
    (is (= "generated asset\n" (slurp (io/file target "note.txt"))))))

(deftest converted-module-assets-localize-parent-embed-test
  (let [root (.toFile
              (java.nio.file.Files/createTempDirectory
               "aguafria-project-parent-assets"
               (make-array java.nio.file.attribute.FileAttribute 0)))
        module (str "fixture.project-parent-assets-" (gensym))
        catalog (io/file root "aguafria-project.edn")
        bundled (io/file root ".aguafria-assets/pkg/data/protocol.json")
        target (io/file root "cache/dependencies/module/hash")
        source "const protocol = @embedFile(\"../data/protocol.json\");"
        localized-path ".aguafria-assets/pkg/data/protocol.json"]
    (io/make-parents bundled)
    (spit bundled "{\"version\": 1}\n")
    (spit catalog
          (pr-str {:schema-version 1
                   :asset-root ".aguafria-assets"
                   :asset-files ["pkg/data/protocol.json"]
                   :modules {module {:relative-path "pkg/cdp/Connection.zig"}}}))
    (project/load-catalog! catalog)
    (let [localized (project/localize-module-assets module source)]
      (is (= (str "const protocol = @embedFile(\"" localized-path "\");")
             localized))
      (project/materialize-module-assets! module localized target)
      (is (= "{\"version\": 1}\n"
             (slurp (io/file target localized-path)))))))
