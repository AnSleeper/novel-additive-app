# 小说加料app（FleshOut 安卓移植版）

> ## ⚠️ 声明：本人不是项目原作者
>
> - **原作品**：FleshOut 0.2.0，由原作者开发的 PC（Windows / Tauri 2）桌面程序。
> - **本仓库**：仅是对**群聊里流传的 PC 安装包**做的一次**逆向移植**——把它的前端资源与后端行为搬到 Android 上，用 Kotlin 重写了原生后端。
> - 仓库内的前端页面、提示词、图标等素材**版权归原作者所有**，此处仅作学习交流用途，**不用于任何商业目的**。
> - 本仓库没有、也不可能代表原作者的任何授权或背书。
> - 如原作者认为侵权，请提 Issue 或联系，我会立即删除仓库与 Release。
>
> 请支持原作者。如果你需要原版 PC 程序，请通过正规渠道获取。

原项目：群聊流传的 FleshOut 0.2.0（Tauri 2 桌面应用），逆向提取的命令清单与表结构见 `逆向分析/`。
目标：功能与内容不变，仅移植为安卓 APP（WebView 容器 + 原生 Kotlin 后端，前端业务代码零修改）。

**下载**：见 [Releases](../../releases/latest)。当前版本 0.3.8。

- 应用名：**小说加料app**
- 图标：自适应图标（前景图缩至安全区内，圆形遮罩不裁内容），另附 legacy 方形与圆形图标
- 自动更新：启动时读 GitHub Releases 最新版本，有新版弹窗提示并提供下载


---

## 一、目录结构

```
FleshOut安卓版\
├─ dist\                        交付 APK
├─ app图标\app图标.jpg           图标源图
├─ 测试用样本\                   测试小说（版权原因未入库）
├─ android-app\                 Android Gradle 工程
│  ├─ app\src\main\assets\      前端资源（业务代码未改动）
│  │  ├─ tauri-shim.js          Tauri IPC 兼容层
│  │  └─ wizard-patch.js        向导第 2 步补丁（见「移植补丁」）
│  ├─ app\src\main\java\com\fleshout\android\
│  │  ├─ MainActivity.kt        WebView 容器 / 返回键 / 自检
│  │  ├─ Ipc.kt                 命令分发（57 个命令 + 文件选择 + fs）
│  │  ├─ Manual.kt              手动模式流式对话 + 4 个工具
│  │  ├─ Db.kt                  SQLite 15 张表 + 预设数据
│  │  ├─ Repo.kt                数据访问层
│  │  ├─ Pipeline.kt            五阶段流水线
│  │  ├─ AiClient.kt            OpenAI 兼容 / Ollama（含流式）
│  │  ├─ Prompts.kt             提示词构建 / JSON 解析
│  │  ├─ BookParser.kt          EPUB / TXT / MOBI / AZW3
│  │  ├─ FileStore.kt           文件与 FileProvider
│  │  └─ Util.kt                字数统计 / UUID
│  ├─ _tools\
│  │  ├─ gradle-8.7\            离线 Gradle（不入库）
│  │  ├─ jdk17\                 Temurin JDK 17（不入库）
│  │  └─ keystore\              签名密钥（不入库，自行生成）
├─ 逆向分析\                     原 exe 提取的命令清单、表结构、内置提示词
└─ README.md
```

---

## 二、构建

```bash
cd android-app

# JDK 17（AGP 8.5.2 必需）
export JAVA_HOME=/path/to/jdk-17

# 离线 Gradle（或直接用系统 gradle）
./_tools/gradle-8.7/bin/gradle assembleRelease --console=plain
```

产物：`android-app/app/build/outputs/apk/release/app-release.apk`

> 项目路径含中文时 AGP 会拒绝，需在 `gradle.properties` 开 `android.overridePathCheck=true`。
> `java.io.tmpdir` 必须指向纯 ASCII 路径（properties 解析会吃掉中文）。
> 签名密钥不入库；`_tools/keystore/` 不存在时自动退回默认签名，clone 下来也能构建。

---

## 三、AI 服务商预设

首次安装即内置三条模型记录，用户只需填 API Key：

| 名称 | provider | Base URL | 默认模型 |
|---|---|---|---|
| DeepSeek 官方 · V4.1 Flash | `deepseek` | `https://api.deepseek.com` | `deepseek-v4-flash` |
| DeepSeek 官方 · V4 Pro | `deepseek` | `https://api.deepseek.com` | `deepseek-v4-pro` |
| 硅基流动 · DeepSeek V3 | `siliconflow` | `https://api.siliconflow.cn/v1` | `deepseek-ai/DeepSeek-V3` |

- 模型管理页的「服务提供商」下拉新增 **DeepSeek 官方**、**硅基流动**，选中后自动填好端点与默认模型。
- `deepseek` 走 OpenAI 兼容协议；请求体默认带 `"thinking": {"type": "disabled"}`，改写/总结用不上思考模式，可显著省 token。
- 「测试连接」改为最小探针：无 system 提示词、单字输入、`max_tokens=16`，一次约 20 token。
- provider 留空时后端按预设补默认端点与模型，前端不填也能跑。

---

## 四、内置提示词模板

原版**没有任何内置模板**，而向导第 5 步强制要求「至少一个识别类别 + 破甲提示词 + 改写通用指导」，等于用户凭空写三段专业提示词才能建项目。

本移植版内置一套「通用扩写（内置）」：2 个识别类别（亲密与暧昧场面 / 情绪张力场面）、通用改写指导、破甲提示词。可在「提示词管理」里改，也可以完全不用、自建模板。

---

## 五、TXT 章节识别模式

向导第 2 步有三档：**简易规则**（行首标识）/ **正则表达式**（自定义）/ **智能识别（推荐）**。

智能识别在 `BookParser.splitSmart` 里，7 条正则共用同一套前置条件。

**前置条件（全部满足才算标题）**：trim 后整行匹配、行长 ≤ 78、不以 `。，！？` 结尾。

| # | 规则 | 正则要点 | 能认 |
|---|---|---|---|
| 1 | 第X<单位> | 数字 1~12 位（半角/全角/中文「零一二三四五六七八九十百千万两〇」），单位只认 **章节回卷集幕话篇** | 第1章、第十二章 初次见面、第一百零八回 尾声、第5卷 风云再起、第3话 转生 |
| 2 | 特殊篇名 | 序章/楔子/引子/前言/后记/尾声/终章/大结局/番外/序言，以及独立的「序」 | 序章、番外 一、序 |
| 3 | Chapter N | 阿拉伯数字或 I/V/X/L/C 罗马数字，1~8 位 | Chapter 12、chapter IV、CHAPTER 3 The End |
| 4 | 数字+分隔符 | 半角数字 1~4 位，分隔符只能是 `、 . ．` | 1、开端、12.标题、3．标题 |
| 5 | (数字)标题 | 左括号可选（（(【[），右括号必须有（)）】]） | （1）开端、(12)标题、【3】番外、[4]xxx |
| 6 | 数字+冒号 | 半角数字 1~4 位 + `：` 或 `:`（**额外补充**，网文常见） | 001：晚安，我的替罪羊 |
| 7 | EP+数字 | 大小写不敏感，允许 `.` `-` `—` `：` 等分隔 | EP0004、ep4、EP.4、EP 7、EP0004 初次见面 |

**不认的**（会并进上一章）：第3部 / 第2季 / 第5册 / 第7段（单位不在 8 个字里）、行末有句号、`1、` 后面没内容、Chapter One、Chap.3、`EP2的剧情很精彩`（数字后直接跟汉字）、标题超 60 字、整行超 78 字。

**兜底**：全书命中 < 2 个标记 → 整本当一章「正文」；超过 6 万字按 6000 字一段切成「第N节」。首个章节前内容 > 60 字单独成「序」。单章超 6 万字按段落再切，标题加「（1）（2）」。

**实测**：《雨夜书店》5 章正确识别（2ms）；6 MB 样本 254 章（1.2s）。样本有 11 个标题行末带句号被前置条件挡掉，所以是 254 而非 265——这是「不含句末标点」这条规则带来的必然结果，符合规格。

EPUB 不走这套，由 OPF spine + NCX/nav 目录决定章节。

---

## 六、移植补丁：wizard-patch.js

向导第 2 步（章节拆分规则）的原逻辑：规则对象只在表单变化时才写回 `formData`；而底部「下一步」走父组件的纯跳步逻辑，不调用子组件的 `next()`。

结果：用户不改任何规则直接点下一步，第 3 步必然报「未配置 TXT 拆分规则」，项目无法创建。

补丁在每次进入该步骤时自动触发一次开关切换（取消→恢复，值不变），让 watch 有机会写回规则。视觉上几乎无感。

---

## 七、移植中修掉的问题

1. **`chapters.index` 是 SQL 关键字** —— `ContentValues.insert` 生成的 SQL 语法错误。列名改 `chapter_index`，DB 版本 2 重建。
2. **嵌套 Cursor 查询死锁** —— `projectJson` / `chapterJson` 在 Cursor 未关闭时又查询，SQLite 连接池直接卡住。改为「先读行 → 关 Cursor → 补关联」。
3. **`Repo.clearRewriteReason` / `setStageIgnored` / `setNeedsRewrite` 缺失** —— 补齐。
4. **`PipelineEngine.Job` 是 private 却被 public 方法当参数** —— 改 public。
5. **`RegexOption.DOT_MATCHES_ALL and IGNORE_CASE`** —— enum 没有 `and`，改 `setOf(...)`。
6. **`File(rewrittenFile(outputPath), "")`** —— 少传 `index` 的死代码，删除。
7. **keystore 路径指向不存在目录** —— 改 `_tools/keystore/fleshout.jks` 并重新生成。
8. **`gradle.properties` tmpdir 是乱码路径** —— 换 ASCII 路径。
9. **Manifest 缺 FileProvider** —— 补 `provider` + `res/xml/file_paths.xml`。
10. **命令前缀不匹配** —— shim 加 `cmd:`，Ipc 未剥，全部命令返回 NOT_IMPLEMENTED（表现为「共 0 个项目」）。
11. **桌面端插件命令报错** —— `plugin:window|is_maximized` 等一律降级返回，不再污染错误提示。
12. **重复选同一本书产生「书名 (2).txt」** —— 同名同大小直接复用缓存文件。
13. **向导第 2 步规则丢失** —— 见「移植补丁」。
14. **应用名/图标仍是 FleshOut** —— 改「小说加料app」+ 橘猫自适应图标。

---

## 八、验证记录（android-33 x86_64 模拟器）

### 向导全流程（真实 UI 操作，已通过）

```
选文件 → 雨夜书店.txt / TXT
工作目录 → /storage/emulated/0/Android/data/com.fleshout.android/files/FleshOut/books/workspace-...
TXT 拆分 → preview_txt_chapters 返回 5 章
预览信息 → 雨夜书店 / 中文 / UTF-8 / 5 章 / 842 字
模型配置 → DeepSeek 官方 · V4.1 Flash（deepseek-v4-flash）
提示词策略 → 自动加载「通用扩写（内置）」
确认配置 → 5 章节 / 自动模式 / 并发 3 / 加料 4000 字
开始创建 → 成功进入工作区（5 章 · 0 已完成 / 第一章 打烊 188 字）
```

### 大文件解析（6 MB 真实样本）

```
默认规则     1757ms → 仅 4 段（本书不是「第X章」格式，不适用）
自定义规则   594ms  → 265 章（^\s*\d{3}\s*[：:]，与文件名 _1_265_ 完全一致）
                       前言 7683 字 / 001：晚安，我的替罪羊 4019 字 / 002：…… 3004 字
```

### 后端链路自检

`create_manual_project` → 解析 3 章 → `list_manual_chapters` → `get_project` → `create_conversation` → `get_manual_project_info` → `get_category_config` → `export_manual_project` 全部成功。

### AI 全链路（DeepSeek 官方 · deepseek-v4-flash，实测通过）

```
test_ai_connection     1010 ms  成功
单章总结（188 字）      正常返回中文总结，stage2 completed
识别阶段                hasTargetContent=true → needsRewrite=true
改写阶段               188 字 → 939 字，anchor 精确定位后拼接，stage4 completed
手动模式对话           流式返回，assistant 消息正常入库
工具调用 write_chapter  章节 188 → 192 字，正文末尾确认为「雨停了。」
```

代码路径与桌面端一致。测试总消耗约 6~8k token（flash 档，成本几分钱）。


---

## 九、已知限制（安卓平台差异）

| 项 | 桌面端 | 安卓端 |
|---|---|---|
| 选择工作目录 | 系统目录选择器 | 不弹选择器，直接用 `Android/data/com.fleshout.android/files/FleshOut/books/workspace-<时间戳>` 并 Toast 提示。SAF 目录授权拿不到可写真实路径 |
| `show_in_folder` | 打开资源管理器定位 | 复制目录路径到剪贴板 + Toast |
| `open_path` | 默认程序打开 | `ACTION_VIEW` + FileProvider |
| 返回键 | — | 第一次交给前端关弹窗，2 秒内再按退出 |
| 缩放 | 可缩放 | 已禁用双击/捏合缩放（避免误触打乱桌面布局） |
| 布局 | 桌面宽屏左右分栏 | 原样缩放，手机竖屏偏挤（未做移动端重排，遵循「内容不改」） |
| MOBI | 支持 HUFF/CDIC | 明确报错提示转 EPUB/TXT |

**关于「001：标题」这类书**：默认规则不适用，需在向导第 2 步选「正则表达式」模式填 `^\s*\d{3}\s*[：:]`。已验证可拆 265 章。

---

## 十、调试入口

```powershell
# 后端自检：建项目 → 解析 → 列章节 → 建会话 → 导出
adb shell am start -n com.fleshout.android/.MainActivity --es selftest 1
# 大文件解析性能
adb shell am start -n com.fleshout.android/.MainActivity --es selftest sample
adb logcat -s FleshOutSelfTest:V
```

debug 包支持注入 JS（release 不可用）：

```powershell
adb push script.js /sdcard/Android/data/com.fleshout.android/files/inject.js
adb shell am start -n com.fleshout.android/.MainActivity --es jsfile "/sdcard/Android/data/com.fleshout.android/files/inject.js"
```

IPC 往返打 `FleshOutIpc` 日志（debug 与 release 均有）。

---

## 十一、移动端适配（0.2.3 新增）

原版是桌面应用，界面上有明显的 PC 痕迹。0.2.3 起加了 `mobile.css` + `mobile-shell.js` 两个移植层文件（在 `assets/`，不改业务代码）。

**清掉的桌面痕迹**：

| 问题 | 处理 |
|---|---|
| 右上角最小化 / 还原 / 关闭三个窗口按钮 | 只隐藏 `.window-controls`，标题栏保留原高度（前端布局依赖它占位，直接 `display:none` 会导致垂直居中的容器裁掉顶部） |
| 底部「Enter 快速继续」键盘提示 | 隐藏 `.hint-text` |
| 找不到设置 / API 配置入口 | 在标题栏右侧注入三个胶囊按钮：工作台 / 提示词 / 设置 |

三个入口走前端自己的 hash 路由：

- 工作台 → `#/projects`
- 提示词 → `#/prompt-management`
- 设置 → `#/model-management`（填 API Key、选模型、调温度/maxTokens/超时）

当前页面对应的按钮高亮为琥珀色。

**为什么不用底部导航**：试过，`#app` 高度是 100vh + 内部垂直居中，减高度会裁掉顶部标题，加 `padding-bottom` 又压不动内部滚动区，最后选择放在标题栏——不占内容高度、不遮挡、也不会挤坏原布局。

**没做的**：内容的响应式重排（卡片网格仍是桌面断点），手机竖屏偏挤但可用。要彻底改成单列布局需要动前端组件，属于业务改动，暂未做。

