# 音乐播放器 (Android 2.2 / API 8)

一个面向 **Android 2.2 (Froyo, API level 8)** 的基础音乐播放器。
仓库内含已编译产物 `MusicPlayer-1.0-android2.2.apk`。

## 功能

- 扫描指定目录（默认 `/sdcard/Music`）中的音频文件，支持子目录递归
- 列表显示歌曲名、艺术家、时长，当前播放项高亮
- 播放 / 暂停、上一首 / 下一首
- 拖动进度条跳转，实时显示 `当前时间 / 总时长`
- 音频焦点处理：来电等打断时自动暂停，恢复后继续；被短暂压低音量（duck）时自动降低音量
- 支持格式：mp3、m4a、aac、wav、ogg、flac、mid、amr、3gp、mp4

运行时把 mp3 放到 `/sdcard/Music`，打开应用后点“扫描”；音乐在别处时，
在输入框填写目录路径再点“扫描”。

## 项目结构

```
app/
  AndroidManifest.xml                    清单：minSdkVersion=8, targetSdkVersion=8
  src/com/example/musicplayer/
    MusicPlayerActivity.java             主界面：扫描、播放、进度、音频焦点
    Track.java                           单首歌曲的数据模型
  res/
    layout/main.xml                      主界面布局
    layout/row.xml                       列表行布局
    values/strings.xml                   文案（UTF-8 中文，编译进 resources.arsc）
    values/colors.xml                    配色
    drawable-{l,m,h,xh}dpi/ic_launcher.png   图标（由 tools/make_icons.py 生成）
```

## 编译

不使用 Gradle，直接调用 Android SDK 工具链构建，因此不依赖网络、也不引入
AndroidX 等依赖。

```powershell
# 1) 首次：下载工具链（JDK 8 + platform-8 + build-tools 25.0.3，约 220 MB）
powershell -NoProfile -ExecutionPolicy Bypass -File tools\fetch_toolchain.ps1

# 2) 构建 APK
powershell -NoProfile -ExecutionPolicy Bypass -File tools\build_apk.ps1

# 3) 校验 APK（清单、内容、签名、对齐）
powershell -NoProfile -ExecutionPolicy Bypass -File tools\verify_apk.ps1
```

构建链路：`aapt`（生成 R.java + 打包资源）→ `javac`（`-source/-target 1.6`，
`-bootclasspath android.jar`）→ `dx`（生成 classes.dex）→ `aapt add`（把 dex 加入
APK）→ `apksigner`（仅 v1 签名）→ `zipalign`。

## 兼容性说明（为什么这样构建）

- **Java 6 字节码**：JDK 8 已不支持 `-source 1.6`，会退化为 Java 8 字节码
  （major 52），而 Android 2.2 的 `dx` 无法处理。编译需使用仍支持 `-source 1.6`
  的 **JDK 8u504**，产出 major 50 的 class 文件，已用 `dexdump` 逐类校验。
- **只签 v1 方案**：APK Signature Scheme v2 是 Android 7.0 才引入的，
  Android 2.2 只能识别 v1（JAR）签名，所以构建时显式关闭 v2。
- **不用 AndroidX / 支持库**：只依赖 `android.jar`（API 8），避免引入需要更高
  API 的依赖，APK 体积仅 27 KB。
- **图标为 PNG**：XML 矢量/自适应图标需要更高 API，这里为四种密度各生成一张 PNG。
- **绕开 `dx.bat` / `apksigner.bat`**：这两个批处理通过 `find_java.bat` 定位
  Java，而 build-tools 25 已不再提供该文件，导致它们不报错也不干活（静默退出 0）。
  构建脚本改为直接调用 `java -jar lib/dx.jar` 与 `java -jar lib/apksigner.jar`。

## 已知限制

- **真机验证情况**：已在 Android 2.2 真机上完成安装，并确认基础功能正常
  （扫描、列表、播放/暂停、切歌、进度条）。界面渲染与音频输出的细节以实际体验为准。
- 未申请 `wakeLock` / 前台服务，应用切到后台会暂停播放；这是“基础功能”版本的
  有意取舍。
- 目录扫描是解析文件名（标题取文件名、艺术家显示“未知艺术家”），
  未读取 ID3 标签；如需显示真实标签与时长，可再接入 `MediaStore` 查询。
- 使用 debug 签名，适合自用与测试，不用于上架。
