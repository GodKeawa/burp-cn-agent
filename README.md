# Burp Suite zh_cn Agent

基于 ByteBuddy 的 Burp Suite 汉化 Java Agent。Agent 会在 Burp Suite 启动时插桩 Swing 组件，并使用内置词典替换界面文本。

支持更新版本的BurpSuite (Current: 2026.9)

## 构建

```bash
mvn clean package
```

构建完成后会生成 `target/burp-modern-cn-agent-1.0.0.jar`，并自动复制为项目根目录的 `burpsuite-pro-cn-loader.jar`。

## 使用

在启动 Burp Suite 时添加 Agent：

```bash
java -javaagent:burpsuite-pro-cn-loader.jar -jar burpsuite-pro.jar
```

如果项目根目录已有启动脚本，直接运行该脚本即可。Agent 必须放在 Burp Suite 使用的工作目录中，或将 `-javaagent` 改为绝对路径。

## 启动参数

多个参数使用英文逗号分隔：

```bash
java -javaagent:burpsuite-pro-cn-loader.jar=debug,dump -jar burpsuite-pro.jar
```

- `debug`：输出字典加载和 ByteBuddy 插桩调试信息。
- `no-han`：禁用中文化。
- `dump`：记录未匹配到的英文文本。
- `dump=/path/to/file.txt`：将未翻译文本写入指定文件。
- `dir=/path/to/dict`：加载指定目录中的外部词典。

也可以使用系统属性或环境变量启用未翻译文本收集：

```bash
java -Dburp.cn.dump=/path/to/untranslated.txt \
	-javaagent:burpsuite-pro-cn-loader.jar \
	-jar burpsuite-pro.jar
```

## 自定义词典

内置词典位于 `src/main/resources/`。运行时可在当前目录、`cn/` 目录或 `dir` 参数指定的目录中放置：

- `cn*.txt`：翻译词典，每行格式为 `英文<TAB>中文`。
- `white*.txt`：白名单正则，每行一条规则。

## Link

- [BurpSuite-Pro-zh_cn](https://github.com/GodKeawa/BurpSuite-Pro-zh_cn): AUR PKGBUILD

## Credit

* [Leon406 (BurpSuiteCN-Release)](https://github.com/Leon406/BurpSuiteCN-Release): Original Translation Text and Loader
