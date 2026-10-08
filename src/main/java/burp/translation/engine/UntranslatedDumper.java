package burp.translation.engine;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * 未汉化文本收集与导出器。
 * 捕获 UI 中新出现的未翻译文本，自动去噪、去重并异步落盘，
 * 生成直接可编辑的 <原文>\t<译文> 词条格式，便于迅速扩充汉化词库。
 */
public class UntranslatedDumper {

    private static final UntranslatedDumper INSTANCE = new UntranslatedDumper();

    private final Set<String> collectedSet = ConcurrentHashMap.newKeySet();
    private final BlockingQueue<String> pendingQueue = new LinkedBlockingQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private boolean enabled = false;
    private File outputFile;

    // 常用噪音过滤正则
    private static final Pattern HAS_LETTER = Pattern.compile("[a-zA-Z]");
    private static final Pattern PURE_DIGITS_OR_SYMBOLS = Pattern.compile("^[0-9\\s\\.,:;!?'\"\\-_/\\\\+=*&^%$#@!~`|\\[\\]{}()<>]+$");
    private static final Pattern NUMBER_WITH_UNIT = Pattern.compile("^\\d+(\\.\\d+)?\\s*(ms|s|m|h|B|KB|MB|GB|%|px|pt|items?|reqs?|bytes?)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern HASH_OR_HEX = Pattern.compile("^[a-f0-9]{32,}$|^[a-f0-9-]{36}$", Pattern.CASE_INSENSITIVE);
    private static final Pattern IP_OR_PORT = Pattern.compile("^(\\d{1,3}\\.){3}\\d{1,3}(:\\d+)?$");
    private static final Pattern DATE_TIME = Pattern.compile("^\\d{4}[-/]\\d{2}[-/]\\d{2}.*");

    public static UntranslatedDumper getInstance() {
        return INSTANCE;
    }

    private UntranslatedDumper() {
    }

    public synchronized void init(boolean enabled, String customPath) {
        this.enabled = enabled;
        if (!enabled) {
            return;
        }

        String defaultPath = new File(System.getProperty("java.io.tmpdir", "/tmp"), "untranslated.txt").getAbsolutePath();
        String path = (customPath != null && !customPath.trim().isEmpty()) ? customPath.trim() : defaultPath;
        this.outputFile = new File(path);

        // 加载已存在的文件中的词条，避免重复收集
        loadExistingFile();

        this.running.set(true);

        // 启动后台定时落盘线程
        Thread flusherThread = new Thread(this::flushLoop, "BurpCn-Dumper-Thread");
        flusherThread.setDaemon(true);
        flusherThread.start();

        // 注册 JVM 退出钩子
        Runtime.getRuntime().addShutdownHook(new Thread(this::onShutdown, "BurpCn-Dumper-ShutdownHook"));

        System.out.printf("[BurpCn] 未汉化文本导出功能已开启！目标文件: %s (已加载历史词条 %d 条)%n",
                outputFile.getAbsolutePath(), collectedSet.size());
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 记录一条未汉化的文本（带智能过滤）
     */
    public void record(String text) {
        if (!enabled || text == null) {
            return;
        }

        String trimmed = text.trim();
        if (shouldFilterOut(trimmed)) {
            return;
        }

        if (collectedSet.add(trimmed)) {
            pendingQueue.offer(trimmed);
        }
    }

    private boolean shouldFilterOut(String text) {
        int len = text.length();
        if (len < 2 || len > 250) {
            return true;
        }

        // 必须包含字母
        if (!HAS_LETTER.matcher(text).find()) {
            return true;
        }

        // 已经包含中文
        if (ContextFilter.containsChinese(text)) {
            return true;
        }

        // 纯数字或标点
        if (PURE_DIGITS_OR_SYMBOLS.matcher(text).matches()) {
            return true;
        }

        // 数字带单位 (例如 "25 ms", "10.5 KB", "100%")
        if (NUMBER_WITH_UNIT.matcher(text).matches()) {
            return true;
        }

        // 哈希、UUID、MD5
        if (HASH_OR_HEX.matcher(text).matches()) {
            return true;
        }

        // IP、端口或时间
        if (IP_OR_PORT.matcher(text).matches() || DATE_TIME.matcher(text).matches()) {
            return true;
        }

        // 文件路径或 URL
        if (text.startsWith("/") || text.startsWith("file:") || text.startsWith("http:") || text.startsWith("https:")) {
            return true;
        }

        return false;
    }

    private void loadExistingFile() {
        if (outputFile == null || !outputFile.exists() || !outputFile.isFile()) {
            return;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(outputFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\t", 2);
                if (parts.length > 0 && !parts[0].trim().isEmpty()) {
                    collectedSet.add(parts[0].trim());
                }
            }
        } catch (Exception e) {
            System.err.println("[BurpCn] 加载已有未汉化文件失败: " + e.getMessage());
        }
    }

    private void flushLoop() {
        while (running.get()) {
            try {
                Thread.sleep(5000);
                flushPending();
            } catch (InterruptedException e) {
                break;
            } catch (Throwable t) {
                // 忽略异常保证线程存活
            }
        }
    }

    private synchronized void flushPending() {
        if (pendingQueue.isEmpty() || outputFile == null) {
            return;
        }

        List<String> toWrite = new ArrayList<>();
        pendingQueue.drainTo(toWrite);
        if (toWrite.isEmpty()) {
            return;
        }

        boolean fileExists = outputFile.exists() && outputFile.length() > 0;
        try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(outputFile, true), StandardCharsets.UTF_8))) {

            if (!fileExists) {
                writer.println("# ==============================================================================");
                writer.println("# Burp Suite 未汉化文本导出文件");
                writer.printf("# 创建时间: %s%n", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
                writer.println("# 使用说明: 每行格式为「原文<TAB>译文」，在制表符后填入翻译后可直接合并入 cn.txt");
                writer.println("# ==============================================================================");
            }

            for (String item : toWrite) {
                writer.println(item + "\t");
            }
            writer.flush();
        } catch (Exception e) {
            System.err.println("[BurpCn] 写入未汉化文本文件失败: " + e.getMessage());
        }
    }

    private void onShutdown() {
        running.set(false);
        flushPending();
        if (enabled && outputFile != null) {
            System.out.printf("[BurpCn] 已将所有捕获的未汉化文本保存至: %s (累计收集 %d 条)%n",
                    outputFile.getAbsolutePath(), collectedSet.size());
        }
    }
}
