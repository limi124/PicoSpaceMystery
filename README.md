# PICO Space Program 2026（赛道 A）· AI 连环实景密室 (PicoSpaceMystery)

基于 **PICO Spatial SDK 6.2.8 (Kotlin + Jetpack Compose Spatial UI + ECS)** 构建的 **360° 多 Agent 协同三层连环 3D 密室逃脱应用**。

---

## ✨ 核心特性

1. **高自由度世界观与角色定制 (`HomeStage.kt`)**
   - 内置 4 大主题独立三层剧本（赛博朋克实验室、民国雨夜古宅、深海克苏鲁、魔法学院禁书地窖），并支持**自由输入任意脑洞世界观**；
   - 支持切换剧情风格（悬疑烧脑、微恐惊悚、搞笑荒诞、硬核推理）、自定义 3 间密室的驻场嫌疑人，或点击 **「🎲 AI 随机脑洞」** 一键生成。

2. **4 个 AI Sub-Agent 实时协同编排 (`MultiAgentOrchestrator.kt` & `ArtTextureAgent.kt`)**
   - 🎬 **剧情总控 Agent**：接入大模型（如华师大 `ecnu-plus` / `ecnu-max` 或任意 OpenAI 兼容接口），每次现场生成全新的 3 层递进式连环密室剧本、随机 3 位密码、12 条三元二次推理线索与审讯证物链；
   - 🎨 **美术贴图 Sub-Agent**：接入硅基流动 `Kwai-Kolors/Kolors` 极速文生图（或本地烘焙光影程序化纹理引擎），并行生成 3 间密室的 AI 环境墙面概念画、驻场嫌疑人肖像立绘与线索道具插画；
   - 📐 **空间布局 Sub-Agent**：将 3D 线索道具分散藏匿到 360° 闭合空间（含第 4 面后墙与各房间差异化 3D 家具组）；
   - 🗣️ **角色演绎 Sub-Agent**：驱动各房间驻场 AI 嫌疑人进行免打字快捷审讯与 **「📢 出示关键证物击破心理防线」** 互动。

3. **360° 全闭合 3D 空间搜证、空间瞬移与双耳空间音效 (`EscapeRoom3D.kt` & `SpatialAudioSynthesizer.kt`)**
   - **360° 闭合房间 + 差异化 3D 家具**：四面环绕墙体、顶部天花板横梁、书桌复古台灯，以及三间房专属 3D 家具组（多层书架、药剂瓶阵列与双联服务器机柜、能量图腾巨柱）；
   - **4 机位空间瞬移探索**：点击地面 3D 发光瞬移圆环或 HUD 机位按钮，在 `📍 全景中央`、`🔍 凑近书桌`、`🚪 左墙与大门`、`🗣️ 右墙与后方` 之间平滑移动；
   - **三元二次推理链 + 工具显影 + 拟物机械拨轮锁**：拾取勘查工具 → 用工具对加密墙面痕迹显影 → 向嫌疑人出示物证获取换算规则 → 免软键盘拨动 3 位机械转轮开锁，3D 大门绕门轴旋转推开进入下一间密室；
   - **原生 PCM 双耳空间音效**：基于 Android `AudioTrack` 零外部依赖实时合成左/右/后方位立体声水晶线索音、扫描显影琶音、机械拨轮咔哒声与石门轰鸣声。

---

## 🚀 快速运行指南（队友拉取仓库后）

### 1. 配置本地 `local.properties`（可选，已被 Git 忽略不会上传）
在项目根目录新建或编辑 `local.properties`：

```properties
sdk.dir=C\:\\Users\\<你的用户名>\\AppData\\Local\\Android\\Sdk
spatial.tools.dir=C\:\\Users\\<你的用户名>\\AppData\\Local\\PICO\\sdk
# 可选：填入大模型与硅基流动生图 API Key（不填也可直接运行内置 4 大主题精编剧本与烘焙光影贴图）
ECNU_API_KEY=你的大模型Key
IMAGE_API_KEY=你的硅基流动Key
```

### 2. 编译与启动模拟器

```powershell
# 启动 PICO 模拟器
pico-cli emulator start --avd Pico_Emulator_6_2

# 编译并安装 Debug APK
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.example.picospacemystery/.platform.LaunchActivity
```

### 3. 浏览器 3D 概念预览（无需 Android 环境）
产品/设计同学可直接用浏览器双击打开 `preview/index.html` 查看 3D 空间交互概念。
