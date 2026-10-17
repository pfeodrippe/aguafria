(ns learn.example.inline-assembly
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defconst SYS_write 1)
(a/defconst SYS_exit 60)
(a/defconst STDOUT_FILENO 1)

(a/defn syscall1 :usize [[number :usize] [arg1 :usize]]
  (asm "syscall"
       {:attrs #{volatile}
        :outputs [[:ret "={rax}" {:type :usize}]]
        :inputs [[:number "{rax}" number] [:arg1 "{rdi}" arg1]]
        :clobbers {:rcx true :r11 true}}))

(a/defn syscall3 :usize
  [[number :usize] [arg1 :usize] [arg2 :usize]
   [arg3 :usize]]
  (asm "syscall"
       {:attrs #{volatile}
        :outputs [[:ret "={rax}" {:type :usize}]]
        :inputs [[:number "{rax}" number]
                 [:arg1 "{rdi}" arg1]
                 [:arg2 "{rsi}" arg2]
                 [:arg3 "{rdx}" arg3]]
        :clobbers {:rcx true :r11 true}}))

(a/defn main :noreturn []
  (let [msg "hello world\n"]
    (= :_ (syscall3 SYS_write STDOUT_FILENO
                    (intFromPtr msg) (:len msg)))
    (= :_ (syscall1 SYS_exit 0))
    (unreachable)))

(comment
  (main))
