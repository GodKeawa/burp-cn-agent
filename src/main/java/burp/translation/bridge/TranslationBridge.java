package burp.translation.bridge;

import java.util.function.BiFunction;

/**
 * 桥接门面类，注入到 Bootstrap ClassLoader。
 * 允许由 Bootstrap ClassLoader 加载的 Swing/AWT 类通过标准字节码 invokestatic 直接调用，
 * 实现真正的零反射、JIT 友好。
 */
public final class TranslationBridge {

    private static volatile BiFunction<Object, String, String> delegate = (target, text) -> text;

    private TranslationBridge() {
    }

    public static void register(BiFunction<Object, String, String> impl) {
        if (impl != null) {
            delegate = impl;
        }
    }

    public static String translate(Object target, String text) {
        if (text == null || text.length() < 2) {
            return text;
        }
        try {
            return delegate.apply(target, text);
        } catch (Throwable t) {
            return text;
        }
    }
}
