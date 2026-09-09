(ns la-professeure.sync
  "Development animation-authoring contract, not game-state sync. No direct iPad transport yet."
  (:refer-clojure :exclude [apply]))

(def initial-state {:connected? false :sequence -1 :revision -1 :timeline nil})

(defn valid-message? [m]
  (and (= 1 (:version m))
       (every? #(and (integer? (% m)) (<= 0 (% m))) [:sequence :revision])
       (string? (:clip m)) (<= 1 (count (:clip m)) 128)
       (integer? (:frames m)) (<= 1 (:frames m) 4096)
       (number? (:fps m)) (<= 0.1 (:fps m) 240)
       (number? (:seconds m)) (<= 0 (:seconds m) 86400)
       (boolean? (:playing? m))))

(defn accept
  "Validate before publishing; duplicates, older revisions and malformed data
  leave the previous timeline intact. Receiving a fixture does not imply connection."
  [state message]
  (cond
    (not (valid-message? message)) {:accepted? false :reason :invalid :state state}
    (<= (:sequence message) (:sequence state)) {:accepted? false :reason :stale-sequence :state state}
    (< (:revision message) (:revision state)) {:accepted? false :reason :stale-revision :state state}
    :else {:accepted? true
           :state (assoc state :sequence (:sequence message) :revision (:revision message)
                         :timeline (select-keys message [:clip :frames :fps :seconds :playing?]))}))

(defn frame-at
  "Elapsed time since this snapshot must come from a local monotonic clock."
  [{:keys [frames fps seconds playing?]} elapsed]
  (mod (long (Math/floor (* fps (+ seconds (if playing? (max 0 elapsed) 0))))) frames))

(comment
  (accept initial-state {:version 1 :sequence 0 :revision 0 :clip "rain"
                         :frames 8 :fps 8 :seconds 0 :playing? true}))
