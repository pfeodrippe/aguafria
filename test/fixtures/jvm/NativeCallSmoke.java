import clojure.java.api.Clojure;
import clojure.lang.IFn;
import clojure.lang.PersistentVector;

class NativeCallSmoke {
    public static void main(String[] args) {
        IFn require = Clojure.var("clojure.core", "require");
        require.invoke(Clojure.read("aguafria.std.debug"));
        require.invoke(Clojure.read("aguafria.std.math"));
        Clojure.var("clojure.core", "load-string").invoke("""
            (ns java-native-qa (:require [aguafria.zig :as az]))
            (az/defn- maximum T
              [[T {:zig/prefix "comptime"} :type] [left T] [right T]]
              (if (== T :bool) (or left right) (if (> left right) left right)))
            """);
        Object result = Clojure.var("java-native-qa", "maximum")
                               .invoke(Clojure.read(":bool"), false, true);
        if (!Boolean.TRUE.equals(result)) throw new AssertionError(result);
        Object root = Clojure.var("aguafria.std.math", "sqrt").invoke(16.0);
        if (!(root instanceof Double) || ((Double) root) != 4.0) throw new AssertionError(root);
        Object printed = Clojure.var("aguafria.std.debug", "print")
                                .invoke("Hello, {s}!\n", PersistentVector.create("Java"));
        if (printed != null) throw new AssertionError(printed);
        System.out.println("Java IFn -> native Zig: maximum=true, sqrt=4.0, print=nil");
        Clojure.var("clojure.core", "shutdown-agents").invoke();
    }
}
