(ns learn.example.inline-assembly
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defconst SYS_write 1)
(az/defconst SYS_exit 60)
(az/defconst STDOUT_FILENO 1)

(az/defn syscall1 :usize [[number :usize] [arg1 :usize]]
  (k/asm "syscall"
         {:attrs #{k/volatile}
          :outputs [[:ret "={rax}" {:type :usize}]]
          :inputs [[:number "{rax}" number] [:arg1 "{rdi}" arg1]]
          :clobbers {:rcx true :r11 true}}))

(az/defn syscall3 :usize
  [[number :usize] [arg1 :usize] [arg2 :usize]
   [arg3 :usize]]
  (k/asm "syscall"
         {:attrs #{k/volatile}
          :outputs [[:ret "={rax}" {:type :usize}]]
          :inputs [[:number "{rax}" number]
                   [:arg1 "{rdi}" arg1]
                   [:arg2 "{rsi}" arg2]
                   [:arg3 "{rdx}" arg3]]
          :clobbers {:rcx true :r11 true}}))

(az/defn main :noreturn []
  (let [msg "hello world\n"]
    (k/= :_ (syscall3 SYS_write STDOUT_FILENO
                      (k/intFromPtr msg) (:len msg)))
    (k/= :_ (syscall1 SYS_exit 0))
    (k/unreachable)))

(comment
  (main))
