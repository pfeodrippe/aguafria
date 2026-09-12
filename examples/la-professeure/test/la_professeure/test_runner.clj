(ns la-professeure.test-runner
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.java.io :as io]
            [la-professeure.dialogue :as dialogue]
            [la-professeure.build :as build]
            [la-professeure.sync :as sync]))

(def message {:version 1 :sequence 0 :revision 0 :clip "rain"
              :frames 8 :fps 8 :seconds 0 :playing? true})

(deftest audio-bindings-use-literal-member-keywords
  (let [directory (.toFile (java.nio.file.Files/createTempDirectory
                            "professeure-bindings-"
                            (make-array java.nio.file.attribute.FileAttribute 0)))
        output (with-redefs [build/root (constantly directory)]
                 (build/bindings!))
        forms (with-open [reader (java.io.PushbackReader. (io/reader output))]
                (binding [*read-eval* false]
                  (loop [forms []]
                    (let [form (read {:eof ::eof} reader)]
                      (if (= ::eof form)
                        forms
                        (recur (conj forms form)))))))
        exports (drop 2 forms)]
    (is (= (count build/audio-api) (count exports)))
    (doseq [[form native-name] (map vector exports build/audio-api)]
      (is (= native-name (second form)))
      (is (= (list 'az/field 'c-api (keyword native-name)) (last form))))))

(deftest native-bootstrap-is-serialized
  (let [loaded (atom false) calls (atom [])]
    (with-redefs [build/native-loaded loaded
                  build/prepare! #(do (swap! calls conj :prepare) (Thread/sleep 20))
                  build/bindings! (constantly :test-bindings)
                  aguafria.c/load-bindings! #(swap! calls conj %)
                  aguafria.zig/configuration (constantly {})
                  aguafria.zig/configure! (fn [_] (swap! calls conj :configure))]
      (let [requests (doall (repeatedly 12 #(future (build/load-native!))))]
        (doseq [request requests] @request))
      (is @loaded)
      (is (= [:prepare :test-bindings :configure] @calls))
      (build/load-native!)
      (is (= 3 (count @calls)) "Later requires do not re-import native types")))
  (let [loaded (atom false)]
    (with-redefs [build/native-loaded loaded
                  build/prepare! #(throw (ex-info "Prepare failed" {}))]
      (is (thrown? clojure.lang.ExceptionInfo (build/load-native!)))
      (is (false? @loaded) "A failed preparation can be retried"))))

(deftest markdown-dialogue
  (let [doc (dialogue/parse "# Scène\n#∆V Bonjour. [id:hello]\n:: Entrer.\n  #∆M Entrez ! ^enter\n  :: Parler.\n    #M Oui.\n:: Partir.\n  Au revoir.")
        root (:key (first (:nodes doc)))
        page (dialogue/view doc root)
        branch (dialogue/view doc (:key (first (:choices page))))]
    (is (= "Bonjour." (:text (first (:passages page)))))
    (is (= "hello" (:id (first (:passages page)))))
    (is (= 2 (count (:choices page))))
    (is (= "la mangue" (:voice (first (:passages branch)))))
    (is (= ["Parler."] (mapv :text (:choices branch))))
    (doseq [bad ["# S\n  :: Orphan" "# S\n#∆Q Unknown" "# S\n::" "Before heading"
                 "# S\n#V Hi ^same\n#M Bye ^same" "# S\n\t#V Tab"]]
      (is (thrown? clojure.lang.ExceptionInfo (dialogue/parse bad))))))

(deftest stable-recording-identities
  (let [manifest #(dialogue/reconcile %1 (dialogue/parse %2))
        original (manifest nil "# Scène\n#V Bonjour.\n#M Salut.")
        ids (mapv :recording-id (:passages original))
        inserted (manifest original "# Scène\n#V Avant.\n\n#M Salut.\n#V Bonjour.\n\n#V Après.")
        edited (manifest original "# Scène\n#V Bonjour à vous.\n#M Salut.")]
    (is (= (set ids) (set (map :recording-id (filter #(#{"Bonjour." "Salut."} (:text %)) (:passages inserted))))))
    (is (= ids (mapv :recording-id (:passages edited))))
    (is (= :needs-review (:status (first (:passages edited)))))
    (is (= 2 (:revision (first (:passages edited)))))
    (is (= original (manifest original "# Scène\n#V Bonjour.\n#M Salut."))))
  (let [before (dialogue/reconcile nil (dialogue/parse "# S\n#V One. [id:one]\n#V Two. ^two"))
        after (dialogue/reconcile before (dialogue/parse "# Different scene\n#V Rewritten. ^two\n#V Entirely new wording. [id:one]"))
        removed (dialogue/reconcile before (dialogue/parse "# S\n#V One. ^one"))]
    (is (= ["two" "one"] (mapv :recording-id (:passages after))))
    (is (= [2 2] (mapv :revision (:passages after))))
    (is (= ["two"] (mapv :recording-id (:archived removed)))))
  (is (thrown? clojure.lang.ExceptionInfo
               (dialogue/reconcile
                (dialogue/reconcile nil (dialogue/parse "# S\n#V One.\n#V Two."))
                (dialogue/parse "# S\n#V Changed one.\n#V Changed two.")))))

(deftest native-markdown-parity
  (is (thrown? clojure.lang.ExceptionInfo (dialogue/parse-native "# Same\n#V One.\n# Same\n#M Two.")))
  (doseq [source [(slurp "resources/dialogue/le-seuil.md")
                  (slurp "resources/dialogue/la-voiture.md")
                  "# Scène\n#∆V Bonjour. [id:hello]\n:: Entrer.\n  #ΔM Entrez. ^enter\n  :: Parler.\n    #M Oui.\n:: Partir.\n  Au revoir."
                  "# One ^one\n#V Longue\nligne.\n\n#M Salut.\n# Two ^two\n#V Fin."]]
    (is (= (dialogue/parse source) (dialogue/parse-native source))))
  (doseq [bad ["# S\n  :: Orphan" "# S\n#∆Q Unknown" "# S\n::" "Before heading"
               "# S\n#V Hi ^same\n#M Bye ^same" "# S\n\t#V Tab"]]
    (is (thrown? clojure.lang.ExceptionInfo (dialogue/parse-native bad))))
  (is (= (dialogue/parse "# S\n#V Recovered.")
         (dialogue/parse-native "# S\n#V Recovered."))))

(deftest authored-markdown-choices
  (let [tabs "# S\n#V Bonjour\t!\n:: Choisir\tici\n\t#M Oui."
        spaces (clojure.string/replace tabs "\t" "    ")]
    (is (= (dialogue/parse-native spaces) (dialogue/parse-native tabs)))
    (is (= (dialogue/parse tabs) (dialogue/parse-native tabs))))
  (let [doc (dialogue/parse-native (slurp "resources/dialogue/la-voiture.md"))
        root (dialogue/view doc (:key (first (:nodes doc))))
        inspect (dialogue/view doc (:key (second (:choices root))))
        open (dialogue/view doc (:key (first (:choices inspect))))]
    (is (= ["Se retourner." "Inspecter la voiture."] (mapv :text (:choices root))))
    (is (= ["Ouvrir la poignée." "Frapper à la porte."] (mapv :text (:choices inspect))))
    (is (= ["Lire l’autocollant."] (mapv :text (:choices open))))))

(deftest dialogue-source-selection
  (let [directory (.toFile (java.nio.file.Files/createTempDirectory
                            "la-professeure-source-" (make-array java.nio.file.attribute.FileAttribute 0)))
        config (io/file directory "dialogue.edn")
        source (io/file directory "authored.md")]
    (spit source "# Author's scene\n:: Author's choice")
    (spit config "{:source \"authored.md\"}")
    (with-redefs [build/root (constantly directory)]
      (is (= (.getCanonicalFile source) (build/story-source)))
      (spit config "{:source \"missing.md\"}")
      (is (thrown? clojure.lang.ExceptionInfo (build/story-source))))))

(deftest sync-contract
  (let [{:keys [accepted? state]} (sync/accept sync/initial-state message)]
    (is accepted?)
    (is (false? (:connected? state)))
    (is (= :stale-sequence (:reason (sync/accept state message))))
    (is (= state (:state (sync/accept state (assoc message :frames 0)))))
    (doseq [bad [(assoc message :fps ##NaN) (assoc message :seconds ##Inf)
                 (assoc message :version 2) (assoc message :sequence -1)
                 (assoc message :playing? "yes") (assoc message :frames 5000)]]
      (is (false? (:accepted? (sync/accept state bad)))))
    (is (= 3 (sync/frame-at (:timeline state) 0.4)))
    (is (= 0 (sync/frame-at (:timeline state) 1.0)))
    (is (= 0 (sync/frame-at (assoc (:timeline state) :playing? false) 1.4)))
    (let [new-state (:state (sync/accept state (assoc message :sequence 1 :revision 2)))]
      (is (= :stale-revision (:reason (sync/accept new-state (assoc message :sequence 2 :revision 1))))))))

(deftest shader-compile-transaction
  (let [directory (.toFile (java.nio.file.Files/createTempDirectory
                            "la-professeure-shader-test-"
                            (make-array java.nio.file.attribute.FileAttribute 0)))
        fragment (io/file directory "resources/shaders/mesh.frag")
        originals (into {} (for [name ["mesh.vert" "mesh.frag"]]
                             [name (slurp (io/file (build/root) "resources/shaders" name))]))]
    (io/make-parents fragment)
    (doseq [[name source] originals]
      (spit (io/file directory "resources/shaders" name) source))
    (with-redefs [build/root (constantly directory)]
      (is (= :prepared (build/shaders!)))
      (let [targets (mapv #(io/file directory "resources/shaders" (str % ".spv"))
                          ["mesh.vert" "mesh.frag"])
            bytes #(mapv (fn [f] (vec (java.nio.file.Files/readAllBytes (.toPath f)))) targets)
            working (bytes)]
        (spit fragment "#version 450\nThis is intentionally invalid GLSL.\n")
        ;; Explicit timestamps keep the failure test independent of filesystem resolution.
        (.setLastModified fragment (+ 2000 (System/currentTimeMillis)))
        (is (thrown? clojure.lang.ExceptionInfo (build/shaders!)))
        (is (= working (bytes)) "Neither published stage changes after a compiler failure")
        (spit fragment (get originals "mesh.frag"))
        (.setLastModified fragment (+ 2000 (System/currentTimeMillis)))
        (is (= :prepared (build/shaders!)))
        (is (= working (bytes)) "Correcting the source recovers the original valid binaries")))))

(defn -main [& _]
  (let [result (run-tests 'la-professeure.test-runner)]
    (shutdown-agents)
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
