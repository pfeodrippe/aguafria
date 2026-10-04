(ns ghostty-agua.core-test
  (:require [aguafria.zig :as a]
            [aguafria.zig.runtime :as runtime]
            [clojure.edn :as edn]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [ghostty-agua.core :as example]
            [ghostty-agua.generate :as generate]
            [ghostty-agua.hot-reload-benchmark :as hot]
            [ghostty-agua.live :as live]
            [ghostty-agua.native :as native]))

(deftest generated-project-summary-test
  (let [{:keys [input-root output-root report-output]} (generate/project-paths)
        report (edn/read-string (slurp report-output))]
    (is (.isDirectory (io/file input-root)))
    (is (generate/generated? output-root))
    (is (= 1051 (:file-count report)))
    (is (= 20292 (:declaration-count report)))
    (is (= (:declaration-count report)
           (:structural-declaration-count report)))
    (is (zero? (:raw-declaration-count report)))
    (is (zero? (:fallback-count report)))
    (is (zero? (:unresolved-syntax-count report)))))

(deftest same-jvm-native-terminal-test
  (let [session (native/open!)]
    (try
      (native/write! session
                     "Bonjour\r\n\u001b[34mbleu\u001b[0m\u001b]2;Ghostty Agua\u0007")
      (native/resize! session 100 30 9 18)
      (let [state (native/state session)]
        (is (= 100 (:cols state)))
        (is (= 30 (:rows state)))
        (is (= 900 (:width-px state)))
        (is (= 540 (:height-px state)))
        (is (= 1 (:cursor-y state)))
        (is (= "Ghostty Agua" (:title state)))
        (is (false? (:vt-processing-error? state))))
      (is (contains? (get (json/read-str (native/type-layout-json session)) "types")
                     "GhosttyStyle"))
      (finally
        (native/close! session)))))

(deftest macos-app-build-uses-upstream-swift-build-script-test
  (let [directory (.toFile (java.nio.file.Files/createTempDirectory
                           "ghostty-build-command-"
                           (make-array java.nio.file.attribute.FileAttribute 0)))
        app (io/file directory "macos/build/Debug/Ghostty.app")
        commands (atom [])]
    (with-redefs-fn
      {#'generate/project-paths (constantly {:standalone-root (str directory)})
       #'generate/materialize! (constantly {:file-count 1})
       #'generate/run-command! (fn [command cwd]
                                (swap! commands conj [command cwd])
                                (when (= "macos/build.nu" (first command))
                                  (.mkdirs app))
                                {:command command :exit 0})}
      (fn []
        (let [result (generate/build-macos-app!)]
          (is (= 2 (count @commands)))
          (is (= ["build" "-Demit-macos-app=false" "-Demit-xcframework=true"
                  "-Dxcframework-target=native" "-Demit-docs=false"]
                 (vec (rest (ffirst @commands)))))
          (is (= ["macos/build.nu" "--configuration" "Debug"]
                 (first (second @commands))))
          (is (= (.getAbsolutePath app) (:app result)))
          (is (every? #(= (str directory) (second %)) @commands)))))))

(deftest stop-closes-native-terminal-and-arena-test
  (let [session (example/start! {:replace? true})]
    (try
      (is (false? @(:closed? session)))
      (example/stop!)
      (is (true? @(:closed? session)))
      (is (not (.isAlive (.scope ^java.lang.foreign.Arena (:arena session)))))
      (is (nil? (example/session)))
      (is (nil? (example/stop!)))
      (finally
        (native/close! session)
        (example/stop!)))))

(deftest compatible-native-edit-retains-terminal-test
  (let [original (:aguafria/declaration (meta #'live/title-version))]
    (try
      (a/await! 'ghostty-agua.live)
      (example/start! {:replace? true})
      (let [before (example/publish-hot-title!)
            changed (runtime/declaration-info (assoc original :body [2]))]
        (runtime/register-declaration! changed)
        (a/await! 'ghostty-agua.live)
        (let [after (example/publish-hot-title!)]
          (testing "the function body changes while real Ghostty state remains"
            (is (= 1 (:native-version before)))
            (is (= 2 (:native-version after)))
            (is (= (:terminal-address before) (:terminal-address after)))
            (is (= "Aguafria Ghostty hot generation 2" (:title after))))))
      (finally
        (runtime/register-declaration! original)
        (a/await! 'ghostty-agua.live)
        (example/stop!)))))

(deftest converted-focus-edit-changes-existing-caller-test
  (try
    (example/start! {:replace? true})
    (let [{:keys [change restore]} (hot/medium!)]
      (is (= (int \X) (get-in change [:verification :value])))
      (is (= (int \I) (get-in restore [:verification :value])))
      (is (true? (get-in change [:verification :same-terminal?])))
      (is (true? (get-in restore [:verification :same-terminal?]))))
    (finally
      (example/stop!))))

(deftest generic-queue-edit-changes-native-behavior-test
  (try
    (example/start! {:replace? true})
    (let [{:keys [change restore]} (hot/complex!)]
      (is (= 3 (get-in change [:verification :value])))
      (is (= 4 (get-in restore [:verification :value])))
      (is (true? (get-in change [:verification :same-terminal?])))
      (is (true? (get-in restore [:verification :same-terminal?]))))
    (finally
      (example/stop!))))
