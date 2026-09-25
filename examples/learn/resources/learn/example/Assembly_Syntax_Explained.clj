(ns learn.example.Assembly-Syntax-Explained
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

;; x86-64 Linux: syscall number in rax, first argument in rdi, result in rax.
(az/defn syscall1 :usize [[number :usize] [argument :usize]]
  ;; Assembly is an expression: the declared output type supplies its result.
  ;; Volatile keeps the syscall even when its result is unused.
  ;; The comptime template can refer to %[result], %[number] or %[argument].
  ;; A literal percent sign is escaped as %%; this template needs neither.
  (ak/asm (az/multiline-string ["syscall"])
          {:attrs #{ak/volatile}
     ;; The output name identifies a template operand; ={rax} selects rax.
     ;; {:type :usize} returns the register instead of assigning a local binding.
     ;; Register constraints follow LLVM/GCC's inline-assembly conventions.
           :outputs [[:result "={rax}" {:type :usize}]]
     ;; Load number into rax and argument into rdi before executing the template.
           :inputs [[:number "{rax}" number] [:argument "{rdi}" argument]]
     ;; The kernel overwrites these registers. Inputs/outputs are not clobbers.
     ;; A :memory clobber would also declare arbitrary undeclared memory writes.
           :clobbers {:rcx true :r11 true}}))
