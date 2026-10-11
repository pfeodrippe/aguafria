;;; aguafria-eval.el --- Step through bodies using CIDER -*- lexical-binding: t; -*-

;;; Commentary:
;; Ordinary CIDER loading, evaluation, result overlays and output destinations.
;; Aguafria supplies source ranges; this file only sequences and highlights them.

;;; Code:
(require 'cl-lib)
(require 'json)
(require 'cider)

;; clojure-mode also consults the unqualified name for arbitrary ns aliases.
(put-clojure-indent 'switch 1)

(defgroup aguafria-eval nil "Visible, sequential body evaluation." :group 'cider)
(defcustom aguafria-eval-delay 0.1
  "Seconds to keep the current form highlighted after CIDER finishes evaluating it."
  :type 'number :group 'aguafria-eval)

(cl-defstruct aguafria-eval--state
  connection buffers source tick rows index request timer overlay preview)
(defface aguafria-eval-preview '((t :inherit highlight))
  "Whole forms selected by the evaluation preview."
  :group 'aguafria-eval)
(defvar-local aguafria-eval--preview-overlays nil)
(defvar aguafria-eval--run nil)
(defvar aguafria-eval-last-results nil
  "Responses observed during the last run, in evaluation order.")

(defun aguafria-eval--current-p (state)
  (and (eq state aguafria-eval--run)
       (buffer-live-p (aguafria-eval--state-source state))
       (= (aguafria-eval--state-tick state)
          (with-current-buffer (aguafria-eval--state-source state)
            (buffer-chars-modified-tick)))))

(defun aguafria-eval--check-connection (state)
  ;; CIDER can broadcast cljc evaluations. Do not evaluate a form twice or
  ;; accidentally change connections partway through this sequence.
  (unless (equal (cider-repls (cider-repl-type-for-buffer))
                 (list (aguafria-eval--state-connection state)))
    (user-error "Select a single JVM CIDER connection for this buffer")))

(defun aguafria-eval--failed-p (response)
  (or (nrepl-dict-get response "ex")
      (cl-intersection '("eval-error" "error" "namespace-not-found" "interrupted")
                       (nrepl-dict-get response "status") :test #'equal)))

(defun aguafria-eval--follow (state handler finished)
  "Forward every response to CIDER's HANDLER, then notify FINISHED once on done."
  (let (failure completed (value "") (out "") (err ""))
    (lambda (response)
      (unless completed
        (when (eq state aguafria-eval--run)
          (setf (aguafria-eval--state-request state) (nrepl-dict-get response "id")))
        (setq failure (or failure (aguafria-eval--failed-p response))
              value (concat value (nrepl-dict-get response "value"))
              out (concat out (nrepl-dict-get response "out"))
              err (concat err (nrepl-dict-get response "err")))
        ;; No output capture/redirection and no replacement result renderer.
        (funcall handler response)
        (when (member "done" (nrepl-dict-get response "status"))
          (setq completed t)
          (when (eq state aguafria-eval--run)
            (setf (aguafria-eval--state-request state) nil)
            (save-current-buffer
              (funcall finished `((status . ,(if failure "failed" "evaluated"))
                                  (value . ,value) (out . ,out) (err . ,err))))))))))

(defun aguafria-eval--decode (value)
  (json-parse-string (json-parse-string value)
                     :object-type 'alist :array-type 'list
                     :null-object nil :false-object nil))

(defun aguafria-eval--plan (state)
  "Query source ranges only; this request does not load or evaluate the source."
  (if (not (aguafria-eval--current-p state))
      (aguafria-eval-stop)
    (with-current-buffer (aguafria-eval--state-source state)
      (let* ((payload `((namespace . ,(cider-current-ns))
                        (source . ,(save-restriction
                                     (widen)
                                     (buffer-substring-no-properties (point-min) (point-max))))))
             (encoded (base64-encode-string
                       (encode-coding-string (json-encode payload) 'utf-8) t))
             (code (format "(do (require 'aguafria.zig.repl 'aguafria.zig) (aguafria.zig.repl/plan-json \"%s\"))"
                           encoded))
             value failure)
        (setf (aguafria-eval--state-request state)
              (nrepl-request:eval
               code
               (lambda (response)
                 (when (member "done" (nrepl-dict-get response "status"))
                   (nrepl--mark-id-completed (nrepl-dict-get response "id")))
                 (when (eq state aguafria-eval--run)
                   (when-let ((v (nrepl-dict-get response "value"))) (setq value v))
                   (setq failure (or failure (aguafria-eval--failed-p response)))
                   (when (member "done" (nrepl-dict-get response "status"))
                     (setf (aguafria-eval--state-request state) nil)
                     (condition-case error
                         (if (or failure (not value))
                             (error "Could not locate body forms: %s" failure)
                           (setf (aguafria-eval--state-rows state)
                                 (alist-get 'rows (aguafria-eval--decode value)))
                           (save-current-buffer
                             (if (aguafria-eval--state-preview state)
                                 (aguafria-eval--show-preview state)
                               (aguafria-eval--next state))))
                       (error (aguafria-eval-stop) (message "%s" (error-message-string error)))))))
               (aguafria-eval--state-connection state) "user" nil nil nil t))))))

(defun aguafria-eval--position (line column)
  (goto-char (point-min))
  (forward-line (1- line))
  ;; Reader columns count UTF-16 units; Emacs positions count characters.
  (let ((units 0))
    (while (and (< units (1- column)) (not (eolp)))
      (cl-incf units (if (> (char-after) #xffff) 2 1))
      (forward-char)))
  (point))

(defun aguafria-eval--highlight (state row)
  (let ((buffer (aguafria-eval--state-source state)))
    (select-window (or (get-buffer-window buffer) (display-buffer buffer)))
    (set-buffer buffer)
    (widen)
    (let* ((start (aguafria-eval--position (alist-get 'line row) (alist-get 'column row)))
           (end (aguafria-eval--position (alist-get 'end-line row) (alist-get 'end-column row))))
      (when (overlayp (aguafria-eval--state-overlay state))
        (delete-overlay (aguafria-eval--state-overlay state)))
      (setf (aguafria-eval--state-overlay state) (make-overlay start end buffer))
      (overlay-put (aguafria-eval--state-overlay state) 'face 'highlight)
      (goto-char end)
      (list start end))))

;;;###autoload
(defun aguafria-eval-clear-highlights (&rest _)
  "Clear this buffer's evaluation preview, without changing its contents."
  (interactive)
  (mapc #'delete-overlay aguafria-eval--preview-overlays)
  (setq aguafria-eval--preview-overlays nil)
  (remove-hook 'after-change-functions #'aguafria-eval-clear-highlights t))

(defun aguafria-eval--show-preview (state)
  "Highlight every executable row in STATE, without evaluating any of them."
  (if (not (aguafria-eval--current-p state))
      (progn (aguafria-eval-stop) (message "Preview discarded: source changed"))
    (with-current-buffer (aguafria-eval--state-source state)
      (aguafria-eval-clear-highlights)
      (save-excursion
        (save-restriction
          (widen)
          (dolist (row (aguafria-eval--state-rows state))
            (unless (equal "skipped" (alist-get 'status row))
              (let* ((start (aguafria-eval--position (alist-get 'line row) (alist-get 'column row)))
                     (end (aguafria-eval--position (alist-get 'end-line row) (alist-get 'end-column row)))
                     (overlay (make-overlay start end)))
                (overlay-put overlay 'face 'aguafria-eval-preview)
                (overlay-put overlay 'help-echo
                             (format "Planned evaluation: %s" (alist-get 'declaration row)))
                (push overlay aguafria-eval--preview-overlays))))))
      (add-hook 'after-change-functions #'aguafria-eval-clear-highlights nil t)
      (message "Highlighted %d whole forms; %d skipped. No buffer code was loaded or evaluated."
               (length aguafria-eval--preview-overlays)
               (- (length (aguafria-eval--state-rows state)) (length aguafria-eval--preview-overlays)))))
  (when (eq state aguafria-eval--run) (aguafria-eval-stop)))

;;;###autoload
(defun aguafria-eval-highlight-buffer ()
  "Preview exactly the whole forms `aguafria-eval-buffer' would evaluate.
Uses a metadata-only CIDER query. Never loads or evaluates this buffer.
The namespace must already be loaded. Edits automatically clear the preview;
use `aguafria-eval-clear-highlights' to clear it manually."
  (interactive)
  (cider-ensure-connected)
  (aguafria-eval-stop)
  (aguafria-eval-clear-highlights)
  (setq aguafria-eval--run
        (make-aguafria-eval--state :connection (cider-current-connection)
                                  :source (current-buffer) :tick (buffer-chars-modified-tick)
                                  :preview t))
  (condition-case error
      (progn
        (aguafria-eval--check-connection aguafria-eval--run)
        (aguafria-eval--plan aguafria-eval--run))
    (error (aguafria-eval-stop) (signal (car error) (cdr error)))))

(defun aguafria-eval--explained-source (bounds &optional type-context)
  "Wrap BOUNDS, preserving source display and an initializer's TYPE-CONTEXT."
  (let* ((source (apply #'buffer-substring-no-properties bounds))
         (expression (if type-context
                         (concat "(aguafria.keyword/as " source " " type-context ")")
                       source)))
    ;; JSON string escaping is also valid in Clojure strings. Escaped newlines
    ;; in metadata do not shift the actual expression's original source lines.
    (concat "^{:aguafria.explain/source " (json-encode-string source) "} "
            "(aguafria.zig/explain! " expression ")")))

(defun aguafria-eval--next (state)
  (when (eq state aguafria-eval--run)
    (condition-case error
        (if (not (aguafria-eval--current-p state))
            (progn (aguafria-eval-stop) (message "Evaluation stopped: source changed"))
          (let ((row (nth (aguafria-eval--state-index state)
                          (aguafria-eval--state-rows state))))
            (cond
             ((null row) (aguafria-eval--next-buffer state))
             ((equal "skipped" (alist-get 'status row))
              (setq aguafria-eval-last-results (append aguafria-eval-last-results (list row)))
              (message "Skipped %s: %s" (alist-get 'declaration row) (alist-get 'reason row))
              (cl-incf (aguafria-eval--state-index state))
              (setf (aguafria-eval--state-timer state)
                    (run-at-time 0 nil #'aguafria-eval--next state)))
             (t
              (let ((bounds (aguafria-eval--highlight state row)))
                (aguafria-eval--check-connection state)
                (cider-interactive-eval
                 ;; Keep the prefix on the same line so CIDER's original line
                 ;; numbers also apply inside explain!. Never edit the buffer.
                 (aguafria-eval--explained-source bounds (alist-get 'type-context row))
                 (aguafria-eval--follow
                  state (cider-interactive-eval-handler nil bounds)
                  (lambda (result)
                    (setq aguafria-eval-last-results
                          (append aguafria-eval-last-results
                                  (list (append `((file . ,(buffer-file-name
                                                           (aguafria-eval--state-source state))))
                                                row result))))
                    (if (equal "failed" (alist-get 'status result))
                        (aguafria-eval-stop)
                      (cl-incf (aguafria-eval--state-index state))
                      ;; Retain the background highlight throughout the delay.
                      (setf (aguafria-eval--state-timer state)
                            (run-at-time aguafria-eval-delay nil #'aguafria-eval--next state)))))
                 bounds (cider--nrepl-pr-request-map)))))))
      (error (aguafria-eval-stop) (message "%s" (error-message-string error))))))

(defun aguafria-eval--next-buffer (state)
  (let ((buffer (pop (aguafria-eval--state-buffers state))))
    (if (null buffer)
        (progn (aguafria-eval-stop) (message "Aguafria evaluation complete"))
      (with-current-buffer buffer
        (aguafria-eval-clear-highlights)
        (setf (aguafria-eval--state-source state) buffer
              (aguafria-eval--state-tick state) (buffer-chars-modified-tick)
              (aguafria-eval--state-index state) 0)
        (aguafria-eval--check-connection state)
        (let ((cider-save-file-on-load nil))
          (cider-load-buffer
           buffer
           (aguafria-eval--follow
            state (cider-load-file-handler buffer)
            (lambda (result)
              (if (equal "failed" (alist-get 'status result))
                  (progn (setq aguafria-eval-last-results
                               (append aguafria-eval-last-results (list result)))
                         (aguafria-eval-stop))
                (aguafria-eval--plan state))))))))))

(defun aguafria-eval-stop ()
  "Stop scheduling forms. CIDER's usual interrupt command remains available."
  (interactive)
  (when-let ((state aguafria-eval--run))
    (setq aguafria-eval--run nil)
    (when (timerp (aguafria-eval--state-timer state))
      (cancel-timer (aguafria-eval--state-timer state)))
    (when (overlayp (aguafria-eval--state-overlay state))
      (delete-overlay (aguafria-eval--state-overlay state)))
    (when-let ((request (aguafria-eval--state-request state)))
      (nrepl-request:interrupt request #'ignore (aguafria-eval--state-connection state)))))

(defun aguafria-eval-buffers (buffers)
  "Evaluate BUFFERS using the current CIDER connection and its normal UI."
  (cider-ensure-connected)
  (aguafria-eval-stop)
  (setq aguafria-eval-last-results nil
        aguafria-eval--run
        (make-aguafria-eval--state :connection (cider-current-connection)
                                  :buffers buffers))
  (condition-case error
      (aguafria-eval--next-buffer aguafria-eval--run)
    (error (aguafria-eval-stop) (signal (car error) (cdr error)))))

;;;###autoload
(defun aguafria-eval-buffer ()
  "Load this buffer with CIDER, then evaluate each complete body form once."
  (interactive)
  (aguafria-eval-buffers (list (current-buffer))))

;;;###autoload
(defun aguafria-eval-namespaces (namespaces)
  "Evaluate NAMESPACES, a list of namespace strings, in order."
  (interactive (list (split-string (read-string "Namespaces (space/comma separated): ")
                                   "[, \t\n]+" t)))
  (cider-ensure-connected)
  (aguafria-eval-buffers
   (mapcar (lambda (namespace)
             (or (cider-find-file (cider-sync-request:ns-path namespace t))
                 (user-error "Cannot find namespace %s" namespace)))
           namespaces)))

(provide 'aguafria-eval)
;;; aguafria-eval.el ends here
