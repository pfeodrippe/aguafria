(ns learn.example.inline-assembly
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

;; This executable uses the x86-64 Linux syscall ABI.
(az/defconst sys-write 1)
(az/defconst sys-exit 60)
(az/defconst stdout-fileno 1)

(az/defn syscall1 :usize [[number :usize] [argument :usize]]
  (k/asm "syscall"
          {:attrs #{k/volatile}
           :outputs [[:result "={rax}" {:type :usize}]]
           :inputs [[:number "{rax}" number] [:argument "{rdi}" argument]]
           :clobbers {:rcx true :r11 true}}))

(az/defn syscall3 :usize
  [[number :usize] [first-argument :usize] [second-argument :usize]
   [third-argument :usize]]
  (k/asm "syscall"
          {:attrs #{k/volatile}
           :outputs [[:result "={rax}" {:type :usize}]]
           :inputs [[:number "{rax}" number]
                    [:first "{rdi}" first-argument]
                    [:second "{rsi}" second-argument]
                    [:third "{rdx}" third-argument]]
           :clobbers {:rcx true :r11 true}}))

(az/defn main :noreturn []
  (let [message "hello world\n"]
    (k/= :_ (syscall3 sys-write stdout-fileno
                      (k/intFromPtr message) (az/field message :len)))
    (k/= :_ (syscall1 sys-exit 0))
    (k/unreachable)))

(comment
  (main))
