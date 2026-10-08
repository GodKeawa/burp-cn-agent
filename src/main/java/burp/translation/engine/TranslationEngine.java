package burp.translation.engine;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 核心翻译引擎：支持精确字面量匹配、预编译正则替换、白名单过滤、多级 LRU 缓存以及未汉化文本收集。
 */
public class TranslationEngine {

    private static final TranslationEngine INSTANCE = new TranslationEngine();

    public static class RegexRule {
        public final Pattern pattern;
        public final String replacement;

        public RegexRule(Pattern pattern, String replacement) {
            this.pattern = pattern;
            this.replacement = replacement;
        }
    }

    private final Map<String, String> literalMap = new ConcurrentHashMap<>(16384);
    private final List<RegexRule> regexRules = new ArrayList<>(512);
    private final List<Pattern> whiteList = new ArrayList<>(256);

    private final LruCache<String, String> translateLru = new LruCache<>(16384);
    private final LruCache<String, Boolean> whiteLru = new LruCache<>(4096);

    private static final Pattern HAS_CAPTURE_GROUP = Pattern.compile(".*\\$\\d.*");

    private boolean debug = false;

    public static TranslationEngine getInstance() {
        return INSTANCE;
    }

    private TranslationEngine() {
    }

    public synchronized void init(String customDir, boolean debug, boolean dump, String dumpPath) {
        this.debug = debug;
        this.literalMap.clear();
        this.regexRules.clear();
        this.whiteList.clear();
        this.translateLru.clear();
        this.whiteLru.clear();

        // 1. 初始化未汉化文本收集器
        UntranslatedDumper.getInstance().init(dump, dumpPath);

        // 2. 加载内置字典
        loadResourceFile("/white.txt", this::parseWhiteLine);
        loadResourceFile("/cn.txt", this::parseCnLine);

        // 3. 扫描并加载外部字典目录
        List<File> searchDirs = new ArrayList<>();
        if (customDir != null && !customDir.trim().isEmpty()) {
            searchDirs.add(new File(customDir));
        }
        searchDirs.add(new File("."));
        searchDirs.add(new File("cn"));

        for (File dir : searchDirs) {
            if (dir.exists() && dir.isDirectory()) {
                loadExternalFiles(dir);
            }
        }

        System.out.printf("[BurpCn] 字典就绪: 精确词条 %d 条, 正则规则 %d 条, 白名单 %d 条%n",
                literalMap.size(), regexRules.size(), whiteList.size());
    }

    private void loadResourceFile(String path, java.util.function.Consumer<String> lineConsumer) {
        try (InputStream in = getClass().getResourceAsStream(path)) {
            if (in == null) {
                if (debug) System.out.println("[BurpCn] 未找到内置资源文件: " + path);
                return;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                reader.lines().forEach(lineConsumer);
            }
        } catch (Exception e) {
            System.err.println("[BurpCn] 读取内置资源失败 " + path + ": " + e.getMessage());
        }
    }

    private void loadExternalFiles(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (!file.isFile()) continue;
            String name = file.getName();
            if (name.startsWith("white") && name.endsWith(".txt")) {
                loadExternalFile(file, this::parseWhiteLine);
            } else if (name.startsWith("cn") && name.endsWith(".txt")) {
                loadExternalFile(file, this::parseCnLine);
            }
        }
    }

    private void loadExternalFile(File file, java.util.function.Consumer<String> lineConsumer) {
        if (debug) System.out.println("[BurpCn] 加载外部字典: " + file.getAbsolutePath());
        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            reader.lines().forEach(lineConsumer);
        } catch (Exception e) {
            System.err.println("[BurpCn] 加载外部文件失败 " + file.getName() + ": " + e.getMessage());
        }
    }

    private void parseWhiteLine(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return;
        }
        try {
            whiteList.add(Pattern.compile(trimmed));
        } catch (Exception e) {
            if (debug) System.err.println("[BurpCn] 非法白名单正则: " + trimmed);
        }
    }

    private void parseCnLine(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return;
        }
        String[] parts = line.split("\t", 2);
        if (parts.length != 2) {
            return;
        }
        String origin = parts[0].trim();
        String trans = parts[1].trim();

        if (origin.isEmpty() || trans.isEmpty()) {
            return;
        }

        // 判断是否为正则表达式规则（包含捕获组引用 $1 或正则标志 (?i) 等）
        if (HAS_CAPTURE_GROUP.matcher(trans).matches() || origin.contains("(?i)") || origin.startsWith(".*") || origin.startsWith("(.*)")) {
            try {
                regexRules.add(new RegexRule(Pattern.compile(origin), trans));
            } catch (Exception e) {
                if (debug) System.err.println("[BurpCn] 非法正则规则: " + origin);
            }
        } else {
            literalMap.put(origin, trans);
        }
    }

    public String translate(Object target, String text) {
        if (text == null || text.length() < 2) {
            return text;
        }

        // 1. 已包含中文，直接跳过
        if (ContextFilter.containsChinese(text)) {
            return text;
        }

        // 2. 检查组件类型是否应跳过（保护输入框及报文查看器）
        if (ContextFilter.shouldIgnoreComponent(target)) {
            return text;
        }

        // 3. 检查文本特征是否为 HTTP 数据包或代码
        if (ContextFilter.looksLikeTrafficOrCode(text)) {
            return text;
        }

        // 4. LRU 缓存命中检查
        if (whiteLru.containsKey(text)) {
            return text;
        }
        String cached = translateLru.get(text);
        if (cached != null) {
            return cached;
        }

        // 5. 白名单正则过滤
        for (Pattern wp : whiteList) {
            if (wp.matcher(text).matches()) {
                whiteLru.put(text, Boolean.TRUE);
                return text;
            }
        }

        // 6. 分隔符组合文本处理 (例如 " | " 或 "\n")
        if (text.contains(" | ")) {
            String[] segments = text.split(" \\| ");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < segments.length; i++) {
                if (i > 0) sb.append(" | ");
                sb.append(translateSingle(segments[i]));
            }
            String result = sb.toString();
            translateLru.put(text, result);
            return result;
        }

        if (text.contains("\n") && text.length() < 150) {
            String[] lines = text.split("\r\n|\n");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lines.length; i++) {
                if (i > 0) sb.append("\n");
                sb.append(translateSingle(lines[i]));
            }
            String result = sb.toString();
            translateLru.put(text, result);
            return result;
        }

        // 7. 单句翻译处理
        String translated = translateSingle(text);
        translateLru.put(text, translated);
        return translated;
    }

    private String translateSingle(String text) {
        if (text == null || text.length() < 2) {
            return text;
        }

        String trimmed = text.trim();

        // 精确匹配字典
        String matched = literalMap.get(trimmed);
        if (matched != null) {
            if (text.length() == trimmed.length()) {
                return matched;
            }
            // 保留前缀和后缀空白字符
            return text.replace(trimmed, matched);
        }

        // 正则规则匹配
        for (RegexRule rule : regexRules) {
            Matcher m = rule.pattern.matcher(text);
            if (m.matches()) {
                return m.replaceAll(rule.replacement);
            }
        }

        // 未命中任何汉化规则：捕获为未汉化文本输出
        UntranslatedDumper.getInstance().record(trimmed);

        return text;
    }
}
