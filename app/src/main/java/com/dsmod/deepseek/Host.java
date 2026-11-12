package com.dsmod.deepseek;

/**
 * Holds the host (DeepSeek) ClassLoader.
 *
 * Module classes are loaded by LspModuleClassLoader, which cannot see the
 * host APK's classes. Every reflective lookup of a host class (MMKV,
 * PowCalculator, ...) must go through the host loader captured in
 * handleLoadPackage, otherwise Class.forName throws ClassNotFoundException.
 */
final class Host {
    private static volatile ClassLoader classLoader;

    private Host() {}

    static void set(ClassLoader cl) {
        if (cl != null) classLoader = cl;
    }

    static ClassLoader classLoader() {
        return classLoader;
    }

    static Class<?> forName(String name) throws ClassNotFoundException {
        ClassLoader cl = classLoader;
        if (cl != null) {
            try {
                return Class.forName(name, false, cl);
            } catch (Throwable ignore) { }
        }
        return Class.forName(name);
    }
}
