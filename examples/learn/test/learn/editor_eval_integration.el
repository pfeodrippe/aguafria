;;; Actual CIDER integration test -*- lexical-binding: t; -*-
;; Run from the Aguafria repository root.
(require 'package)
(package-initialize)
(load-file (expand-file-name "editors/emacs/aguafria-eval.el"))
(setq cider-repl-pop-to-buffer-on-connect nil
      cider-show-error-buffer nil
      cider-interactive-eval-output-destination 'repl-buffer
      aguafria-eval-delay 0.01)
(let* ((root (or (getenv "AGUAFRIA_TEST_OUTPUT_DIRECTORY")
                 (make-temp-file "aguafria-editor-test-" t)))
       (port (string-to-number (or (getenv "AGUAFRIA_TEST_NREPL_PORT")
                                   (error "Set AGUAFRIA_TEST_NREPL_PORT"))))
       (project (expand-file-name "examples/learn/"))
       ordinary-evals
       (observe (lambda (form _callback bounds &rest _)
                  (unless (and (string-prefix-p "^{:aguafria.explain/source " form)
                               (string-suffix-p (concat "(aguafria.zig/explain! "
                                                        (apply #'buffer-substring-no-properties bounds) ")")
                                                form))
                    (error "Expected explain! around the original source region: %s" form))
                  (push form ordinary-evals))))
  (make-directory root t)
  (cider-connect-clj (list :host "127.0.0.1" :port port :project-dir project))
  (nrepl-sync-request:eval
   "(do (require 'aguafria.zig.repl :reload) (require 'aguafria.zig.explain) (alter-var-root #'aguafria.zig.explain/*verbose* (constantly true)))"
   (cider-current-connection) "user")
  (advice-add 'cider-interactive-eval :before observe)
  (aguafria-eval-namespaces
   '("learn.example.values" "learn.example.test-vector"
     "learn.example.test-single-item-pointer" "learn.example.test-pointer-arithmetic"
     "learn.example.test-optional-type" "learn.example.test-multidimensional-arrays"))
  (let ((deadline (+ (float-time) 360)))
    (while (and aguafria-eval--run (< (float-time) deadline))
      (accept-process-output nil 0.05)))
  (advice-remove 'cider-interactive-eval observe)
  (unless (= 20 (length ordinary-evals))
    (error "Expected 20 calls through cider-interactive-eval, got %s" (length ordinary-evals)))
  (when (get-buffer "*Aguafria Eval*") (error "Custom output buffer was created"))
  (unless (= 1 (length (cider-repls))) (error "The runner created another REPL"))
  (with-current-buffer (cider-current-connection)
    (write-region (point-min) (point-max) (expand-file-name "emacs-transcript.txt" root) nil 'silent)
    (dolist (text '("1 + 1 = 2" "value: hi" "value: 1234"))
      (unless (string-match-p (regexp-quote text) (buffer-string))
        (error "Output missing from the ordinary CIDER REPL: %s" text))))
  (with-temp-file (expand-file-name "emacs-results.json" root)
    (insert (json-encode aguafria-eval-last-results)))
  (when aguafria-eval--run (error "Editor evaluation timed out"))
  (unless (= 20 (length aguafria-eval-last-results))
    (error "Expected 20 completed bodies/initializers, got %s" (length aguafria-eval-last-results)))
  (dolist (result aguafria-eval-last-results)
    (unless (equal "evaluated" (alist-get 'status result))
      (error "Body evaluation failed: %S" result))
    (let ((header (format "\n[aguafria] >>> BEGIN explain %s:%s\n%s\n\n"
                          (alist-get 'file result) (alist-get 'line result)
                          (alist-get 'expression result))))
      (unless (string-prefix-p header (alist-get 'out result))
        (error "Explain! did not preserve the exact source text/location: %S" result))
      (unless (string-suffix-p "[aguafria] <<< END explain\n\n" (alist-get 'out result))
        (error "Explain! missing its end boundary: %S" result))
      (with-current-buffer (cider-current-connection)
        (unless (string-match-p (regexp-quote header) (buffer-string))
          (error "Explain header missing from the ordinary REPL: %s" header)))))
  (let* ((values (cl-remove-if-not
                  (lambda (r) (string-suffix-p "/values.clj" (alist-get 'file r)))
                  aguafria-eval-last-results))
         ;; Zig debug output is stderr. The new stdout form listing also
         ;; contains format-string literals; those are not repeated execution.
         (output (mapconcat (lambda (r) (alist-get 'err r)) values "")))
    (unless (= 6 (length values)) (error "Values did not evaluate its initializer and five body forms"))
    (dolist (text '("1 + 1 = 2" "7.0 / 3.0 =" "optional 1" "optional 2"
                    "error union 1" "error union 2"))
      (let ((start 0) (count 0))
        (while (string-match (regexp-quote text) output start)
          (cl-incf count) (setq start (match-end 0)))
        (unless (= 1 count) (error "%s was printed %s times" text count)))))
  ;; An ordinary Clojure fixture also proves load-once, intact let scope,
  ;; stock CIDER error handling, and no evaluation after an error.
  (let* ((file (make-temp-file (expand-file-name ".aguafria/editor-eval-" project) nil ".clj"))
         (namespace (format "aguafria.editor.fixture%d" (emacs-pid)))
         (body "(let [x (do (swap! events conj :binding) 42)] ; unsaved 😀 comment\n\t(swap! events conj x)\n    x)")
         (connection (cider-current-connection)))
    (unwind-protect
        (progn
          (with-temp-file file
            (insert (format "(ns %s)\n(def events (atom [:loaded]))\n(defn main []\n  (let [x (do (swap! events conj :binding) 42)] (swap! events conj x) x)\n  (throw (Exception. \"intentional editor test\"))\n  (swap! events conj :must-not-run))\n(comment (main))\n" namespace)))
          (with-current-buffer (find-file-noselect file)
            ;; Display must use the unsaved buffer, not stale disk contents.
            (goto-char (point-min))
            (forward-line 3)
            (back-to-indentation)
            (let ((start (point)))
              (forward-sexp)
              (delete-region start (point))
              (insert body))
            (aguafria-eval-buffer))
          (let ((deadline (+ (float-time) 30)))
            (while (and aguafria-eval--run (< (float-time) deadline))
              (accept-process-output nil 0.05)))
          (unless (equal '("evaluated" "failed")
                         (mapcar (lambda (r) (alist-get 'status r)) aguafria-eval-last-results))
            (error "CIDER error did not stop the queue: %S" aguafria-eval-last-results))
          (unless (string-prefix-p (format "\n[aguafria] >>> BEGIN explain %s:4\n%s\n\n" file body)
                                   (alist-get 'out (car aguafria-eval-last-results)))
            (error "Unsaved formatting/comment was lost"))
          (with-temp-buffer
            (insert-file-contents file)
            (when (string-match-p "unsaved" (buffer-string)) (error "Runner modified the source file")))
          (unless (string-prefix-p (format "\n[aguafria] >>> BEGIN explain %s:7\n(throw" file)
                                   (alist-get 'out (cadr aguafria-eval-last-results)))
            (error "Failing form/source was not reported before the error"))
          (unless (string-suffix-p "[aguafria] <<< END explain\n\n"
                                   (alist-get 'out (cadr aguafria-eval-last-results)))
            (error "Failure did not close the explanation block"))
          (let ((response (nrepl-sync-request:eval (format "@%s/events" namespace) connection "user")))
            (unless (equal "[:loaded :binding 42]" (nrepl-dict-get response "value"))
              (error "Initialization/bindings were repeated or the third form ran: %S" response))))
      (when-let ((buffer (get-file-buffer file)))
        (with-current-buffer buffer (set-buffer-modified-p nil))
        (kill-buffer buffer))
      (delete-file file)))
  ;; Normal quiet mode must hide successful cache reuse and its summaries,
  ;; without suppressing the program's own output or any fallback work.
  (nrepl-sync-request:eval
   "(alter-var-root #'aguafria.zig.explain/*verbose* (constantly false))"
   (cider-current-connection) "user")
  (aguafria-eval-namespaces '("learn.example.values" "learn.example.test-pointer-casting"))
  (let ((deadline (+ (float-time) 120)))
    (while (and aguafria-eval--run (< (float-time) deadline))
      (accept-process-output nil 0.05)))
  (unless (= 9 (length aguafria-eval-last-results)) (error "Quiet run did not finish"))
  (dolist (result aguafria-eval-last-results)
    (unless (equal "evaluated" (alist-get 'status result)) (error "Quiet eval failed: %S" result))
    (when (string-match-p "^\\[aguafria\\] \\(bundle-cache-hit\\|memory-cache-hit\\|bundle-loaded\\|[0-9]+ \\)"
                          (alist-get 'out result))
      (error "Quiet mode printed successful cache hits/summary: %S" result)))
  (with-temp-file (expand-file-name "quiet-results.json" root)
    (insert (json-encode aguafria-eval-last-results)))
  (princ "REAL CIDER: 20 verbose source reports plus 9 quiet evaluations; formatting, boundaries, error stop and output routing verified.\n"))
