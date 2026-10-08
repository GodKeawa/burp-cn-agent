package burp.translation.advisor;

import burp.translation.bridge.TranslationBridge;
import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy 方法切面 Advice 定义：直接在方法执行前重写入参，零反射，高性能。
 */
public final class ComponentAdvice {

    private ComponentAdvice() {
    }

    /**
     * 针对单字符串参数（参数索引 0）的方法拦截：
     * 包括：setText(String), setTitle(String), setToolTipText(String), addTab(String, ...), insertTab(String, ...)
     */
    public static class SetTextAdvice {
        @Advice.OnMethodEnter
        public static void onEnter(
                @Advice.This(optional = true) Object self,
                @Advice.Argument(value = 0, readOnly = false) String text) {
            if (text != null) {
                text = TranslationBridge.translate(self, text);
            }
        }
    }

    /**
     * 针对 JTabbedPane.setTitleAt(int index, String title) 的拦截（参数索引 1）
     */
    public static class SetTitleAtAdvice {
        @Advice.OnMethodEnter
        public static void onEnter(
                @Advice.This(optional = true) Object self,
                @Advice.Argument(value = 1, readOnly = false) String title) {
            if (title != null) {
                title = TranslationBridge.translate(self, title);
            }
        }
    }

    /**
     * 针对 JComboBox.addItem(Object item) 的拦截
     */
    public static class AddItemAdvice {
        @Advice.OnMethodEnter
        public static void onEnter(
                @Advice.This(optional = true) Object self,
                @Advice.Argument(value = 0, readOnly = false) Object item) {
            if (item instanceof String) {
                item = TranslationBridge.translate(self, (String) item);
            }
        }
    }

    /**
     * 针对 TableColumn.setHeaderValue(Object headerValue) 的拦截
     */
    public static class SetHeaderValueAdvice {
        @Advice.OnMethodEnter
        public static void onEnter(
                @Advice.This(optional = true) Object self,
                @Advice.Argument(value = 0, readOnly = false) Object headerValue) {
            if (headerValue instanceof String) {
                headerValue = TranslationBridge.translate(self, (String) headerValue);
            }
        }
    }
}
