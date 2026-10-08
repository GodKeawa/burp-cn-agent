package burp.translation.engine;

import javax.swing.text.JTextComponent;
import java.util.regex.Pattern;

/**
 * 上下文敏感的过滤器，防止污染用户输入或 HTTP 流量报文。
 */
public final class ContextFilter {

    private static final Pattern CHINESE_PATTERN = Pattern.compile("[\\u4e00-\\u9fa5]");

    // 常见网络协议与报文特征，避免误翻译正在查看的流量内容
    private static final String[] TRAFFIC_PREFIXES = {
            "http://", "https://", "ws://", "wss://",
            "HTTP/1.", "HTTP/2", "GET ", "POST ", "PUT ", "DELETE ", "HEAD ", "OPTIONS ",
            "CONNECT ", "TRACE ", "PATCH ", "Content-Type:", "Host:", "User-Agent:",
            "{\" ", "{\"", "<?xml", "<!DOCTYPE", "<html"
    };

    private ContextFilter() {
    }

    public static boolean containsChinese(String text) {
        return CHINESE_PATTERN.matcher(text).find();
    }

    public static boolean shouldIgnoreComponent(Object target) {
        if (target == null) {
            return false;
        }

        // 核心保护：凡是可编辑的文本组件，绝不翻译
        if (target instanceof JTextComponent) {
            JTextComponent textComp = (JTextComponent) target;
            if (textComp.isEditable()) {
                return true;
            }
            // 多行大段文本区域（如报文查看器），默认跳过
            String className = target.getClass().getName();
            if (className.contains("JTextArea") || className.contains("JEditorPane")
                    || className.contains("PlainDocument") || className.contains("SyntaxTextArea")) {
                return true;
            }
        }

        return false;
    }

    public static boolean looksLikeTrafficOrCode(String text) {
        if (text == null || text.length() > 500) {
            // 大于 500 字符的通常是报文或代码，直接跳过
            return true;
        }
        for (String prefix : TRAFFIC_PREFIXES) {
            if (text.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
