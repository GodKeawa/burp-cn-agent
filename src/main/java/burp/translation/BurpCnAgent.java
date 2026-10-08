package burp.translation;

import burp.translation.advisor.ComponentAdvice;
import burp.translation.engine.TranslationEngine;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.BiFunction;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * 现代化 Burp Suite 汉化 Java Agent 入口类。
 * 基于 ByteBuddy 字节码插桩，支持任意高版本 JDK (Java 17/21/25/27+)。
 */
public class BurpCnAgent {

    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("=============================");
        System.out.println("  Burp Suite zh_cn Agent  ");
        System.out.println("=============================");

        boolean enableHan = true;
        boolean debug = false;
        boolean dump = false;
        String dumpPath = null;
        String customDir = null;

        // 检查系统属性与环境变量
        String envDump = System.getenv("BURP_CN_DUMP");
        if (envDump == null) {
            envDump = System.getenv("BURP_DUMP_UNTRANSLATED");
        }
        if ("1".equals(envDump) || "true".equalsIgnoreCase(envDump)) {
            dump = true;
        }

        String propDump = System.getProperty("burp.cn.dump");
        if (propDump != null) {
            dump = true;
            if (!propDump.equalsIgnoreCase("true") && !propDump.equals("1")) {
                dumpPath = propDump;
            }
        }

        if (agentArgs != null) {
            String[] args = agentArgs.split(",");
            for (String arg : args) {
                String a = arg.trim();
                if ("no-han".equalsIgnoreCase(a) || "false".equalsIgnoreCase(a)) {
                    enableHan = false;
                } else if ("debug".equalsIgnoreCase(a) || "debug=true".equalsIgnoreCase(a)) {
                    debug = true;
                } else if ("dump".equalsIgnoreCase(a) || "dump=true".equalsIgnoreCase(a)) {
                    dump = true;
                } else if (a.startsWith("dump=")) {
                    dump = true;
                    dumpPath = a.substring(5).trim();
                } else if (a.startsWith("dir=")) {
                    customDir = a.substring(4).trim();
                }
            }
        }

        if (!enableHan) {
            System.out.println("[BurpCn] 汉化已根据启动参数禁用。");
            return;
        }

        try {
            // 1. 初始化核心翻译引擎与未汉化收集器
            TranslationEngine engine = TranslationEngine.getInstance();
            engine.init(customDir, debug, dump, dumpPath);

            // 2. 将 TranslationBridge 注入到 Bootstrap ClassLoader
            injectBootstrapJar(inst);

            // 3. 在 Bootstrap ClassLoader 中注册翻译回调
            registerBridgeInBootstrap(engine);

            // 4. 配置 JPMS 模块读取边界 (针对 Java 9+)
            configureModuleEdges(inst);

            // 5. 构建并安装 ByteBuddy 转换器
            installTransformers(inst, debug);

            System.out.println("[BurpCn] 字节码插桩已成功安装！已支持现代 Java 运行时。");
        } catch (Throwable t) {
            System.err.println("[BurpCn] Agent 初始化发生异常:");
            t.printStackTrace();
        }
    }

    private static void injectBootstrapJar(Instrumentation inst) throws Exception {
        File tempJar = File.createTempFile("burp-cn-bridge-", ".jar");
        tempJar.deleteOnExit();

        String entryName = "burp/translation/bridge/TranslationBridge.class";
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(tempJar))) {
            jos.putNextEntry(new JarEntry(entryName));
            try (InputStream in = BurpCnAgent.class.getResourceAsStream("/" + entryName)) {
                if (in == null) {
                    throw new IllegalStateException("无法从 classpath 读取 " + entryName);
                }
                in.transferTo(jos);
            }
            jos.closeEntry();
        }

        inst.appendToBootstrapClassLoaderSearch(new JarFile(tempJar));
    }

    private static void registerBridgeInBootstrap(TranslationEngine engine) throws Exception {
        Class<?> bootBridgeClass = Class.forName("burp.translation.bridge.TranslationBridge", true, null);
        Method registerMethod = bootBridgeClass.getMethod("register", BiFunction.class);
        BiFunction<Object, String, String> translator = engine::translate;
        registerMethod.invoke(null, translator);
    }

    private static void configureModuleEdges(Instrumentation inst) {
        try {
            Module desktopModule = javax.swing.JLabel.class.getModule();
            Module baseModule = Object.class.getModule();
            Module unnamedModule = BurpCnAgent.class.getClassLoader().getUnnamedModule();

            Set<Module> readUnnamed = Collections.singleton(unnamedModule);
            inst.redefineModule(desktopModule, readUnnamed, Collections.emptyMap(), Collections.emptyMap(), Collections.emptySet(), Collections.emptyMap());
            inst.redefineModule(baseModule, readUnnamed, Collections.emptyMap(), Collections.emptyMap(), Collections.emptySet(), Collections.emptyMap());
        } catch (Throwable ignored) {
        }
    }

    private static void installTransformers(Instrumentation inst, boolean debug) {
        AgentBuilder builder = new AgentBuilder.Default()
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
            .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
            .ignore(ElementMatchers.nameStartsWith("java.lang.")
                .or(ElementMatchers.nameStartsWith("java.util."))
                .or(ElementMatchers.nameStartsWith("java.io."))
                .or(ElementMatchers.nameStartsWith("java.nio."))
                .or(ElementMatchers.nameStartsWith("java.security."))
                .or(ElementMatchers.nameStartsWith("jdk."))
                .or(ElementMatchers.nameStartsWith("sun.")));

        if (debug) {
            builder = builder.with(AgentBuilder.Listener.StreamWriting.toSystemOut());
        }

        builder
            // 1. 按钮与菜单项 (JButton, JMenuItem, JCheckBox, JRadioButton 等)
            .type(ElementMatchers.named("javax.swing.AbstractButton"))
            .transform((b, typeDescription, classLoader, module, domain) ->
                b.visit(Advice.to(ComponentAdvice.SetTextAdvice.class)
                    .on(ElementMatchers.named("setText")
                        .and(ElementMatchers.takesArguments(String.class)))))
            // 2. 静态文本标签
            .type(ElementMatchers.named("javax.swing.JLabel"))
            .transform((b, typeDescription, classLoader, module, domain) ->
                b.visit(Advice.to(ComponentAdvice.SetTextAdvice.class)
                    .on(ElementMatchers.named("setText")
                        .and(ElementMatchers.takesArguments(String.class)))))
            // 3. 顶级窗口与对话框标题
            .type(ElementMatchers.namedOneOf("java.awt.Frame", "java.awt.Dialog"))
            .transform((b, typeDescription, classLoader, module, domain) ->
                b.visit(Advice.to(ComponentAdvice.SetTextAdvice.class)
                    .on(ElementMatchers.named("setTitle")
                        .and(ElementMatchers.takesArguments(String.class)))))
            // 4. 组件悬浮提示 ToolTip
            .type(ElementMatchers.named("javax.swing.JComponent"))
            .transform((b, typeDescription, classLoader, module, domain) ->
                b.visit(Advice.to(ComponentAdvice.SetTextAdvice.class)
                    .on(ElementMatchers.named("setToolTipText")
                        .and(ElementMatchers.takesArguments(String.class)))))
            // 5. 选项卡面板 TabbedPane
            .type(ElementMatchers.named("javax.swing.JTabbedPane"))
            .transform((b, typeDescription, classLoader, module, domain) ->
                b.visit(Advice.to(ComponentAdvice.SetTextAdvice.class)
                    .on(ElementMatchers.namedOneOf("addTab", "insertTab")
                        .and(ElementMatchers.takesArgument(0, String.class))))
                 .visit(Advice.to(ComponentAdvice.SetTitleAtAdvice.class)
                    .on(ElementMatchers.named("setTitleAt")
                        .and(ElementMatchers.takesArguments(int.class, String.class)))))
            // 6. 下拉选择框 JComboBox
            .type(ElementMatchers.named("javax.swing.JComboBox"))
            .transform((b, typeDescription, classLoader, module, domain) ->
                b.visit(Advice.to(ComponentAdvice.AddItemAdvice.class)
                    .on(ElementMatchers.named("addItem")
                        .and(ElementMatchers.takesArguments(Object.class)))))
            // 7. 分组面板边框标题 TitledBorder
            .type(ElementMatchers.named("javax.swing.border.TitledBorder"))
            .transform((b, typeDescription, classLoader, module, domain) ->
                b.visit(Advice.to(ComponentAdvice.SetTextAdvice.class)
                    .on(ElementMatchers.named("setTitle")
                        .and(ElementMatchers.takesArguments(String.class)))))
            // 8. 文本组件 JTextComponent（只读模式下翻译）
            .type(ElementMatchers.named("javax.swing.text.JTextComponent"))
            .transform((b, typeDescription, classLoader, module, domain) ->
                b.visit(Advice.to(ComponentAdvice.SetTextAdvice.class)
                    .on(ElementMatchers.named("setText")
                        .and(ElementMatchers.takesArguments(String.class)))))
            // 9. 表格列标题 TableColumn.setHeaderValue
            .type(ElementMatchers.named("javax.swing.table.TableColumn"))
            .transform((b, typeDescription, classLoader, module, domain) ->
                b.visit(Advice.to(ComponentAdvice.SetHeaderValueAdvice.class)
                    .on(ElementMatchers.named("setHeaderValue")
                        .and(ElementMatchers.takesArguments(Object.class)))))
            .installOn(inst);
    }
}
