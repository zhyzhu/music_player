# 音乐播放器 (Android 2.2 / API 8)

一个面向 **Android 2.2 (Froyo, API level 8)** 的基础音乐播放器。
仓库内含已编译产物 `MusicPlayer-1.0-android2.2.apk`。

## 功能

- **自动发现歌曲**，不需要用户输入任何路径：
  - 优先通过 `MediaStore` 读取系统媒体库，显示真实 ID3 标签（标题／艺术家／专辑）与时长
  - 媒体库为空时自动回退为遍历 SD 卡的 `Music` 文件夹，用于尚未被扫描器索引的文件
- **搜索**：按歌名或歌手即时过滤列表；清空按钮可一键还原
- 列表显示歌曲名、艺术家、时长，当前播放项高亮
- 播放 / 暂停、上一首 / 下一首
- **后台播放**：播放由前台 Service 承担，退出界面或切到别的应用不会被中断；
  通知栏常驻显示当前曲目，点击可回到播放界面
- 拖动进度条跳转，实时显示 `当前时间 / 总时长`
- 音频焦点处理：来电等打断时自动暂停；被短暂压低音量（duck）时自动降低音量
- 支持格式：mp3、m4a、aac、wav、ogg、flac、mid、amr、3gp、mp4

启动即自动扫描。搜索只影响列表显示：播放开始后队列固定，输入搜索词不会打断当前曲目；
播放开始前，队列跟随列表，点播放就播当前看到的结果。

## 项目结构

```
app/
  AndroidManifest.xml                    清单：minSdkVersion=8, targetSdkVersion=8
  src/com/example/musicplayer/
    MusicPlayerActivity.java             界面：搜索过滤、列表、进度条、与服务绑定
    TrackPlayer.java                     前台 Service：播放引擎、音频焦点、通知栏
    MediaLibrary.java                    MediaStore 查询与目录遍历
    Track.java                           单首歌曲的数据模型（Parcelable，支持文件路径或 content:// URI）
  res/
    layout/main.xml                      主界面布局
    layout/row.xml                       列表行布局
    drawable/search_box.xml              搜索框背景（深色输入框，见下）
    values/strings.xml                   文案（UTF-8 中文，编译进 resources.arsc）
    values/colors.xml                    配色
    drawable-{l,m,h,xh}dpi/ic_launcher.png   启动图标（由 tools/make_icons.py 生成）
    drawable-{l,m,h,xh}dpi/ic_stat_music.png 通知栏图标（由 tools/make_stat_icon.py 生成）
```

配色不是凭感觉调的：`tools/check_contrast.py` 按 WCAG 标准计算各元素文字与背景的
对比度。搜索框必须自带深色背景 —— 平台默认的 `EditText` 背景是浅色，配白字会
变成白底白字（这个 bug 真的发生过）。当前最差的一对是搜索提示文字，5.46（AA）。

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
  API 的依赖，APK 体积仅 36 KB。
- **图标为 PNG**：XML 矢量/自适应图标需要更高 API，这里为四种密度各生成一张 PNG。
- **绕开 `dx.bat` / `apksigner.bat`**：这两个批处理通过 `find_java.bat` 定位
  Java，而 build-tools 25 已不再提供该文件，导致它们不报错也不干活（静默退出 0）。
  构建脚本改为直接调用 `java -jar lib/dx.jar` 与 `java -jar lib/apksigner.jar`。

## 已知限制

- **真机验证情况**：基础版本已在 Android 2.2 真机上完成安装，播放、切歌、进度条
  确认正常；接入 `MediaStore` 的版本在此基础上新增了音乐库与真实标签，
  界面渲染与音频输出的细节以实际体验为准。
- **发现机制依赖系统媒体扫描器**：只有已被索引的文件才会出现。
  媒体库为空时会自动回退为遍历 SD 卡 `Music` 文件夹，所以新拷贝进去但尚未入库的文件
  也能播放，只是此时标题取文件名、艺术家显示“未知艺术家”、时长显示 `--:--`
  （该路径不读取标签）。用户无法指定其它目录 —— 这是有意的，普通用户不该被要求输入路径。
  `MediaStore` 的 `IS_MUSIC` 过滤会排除短的提示音与录音（因此也一并屏蔽手机自带铃声）。
- **搜索范围是歌名与歌手**，不含专辑名；且只在播放开始前影响播放队列。
- 未申请 `wakeLock` 锁屏保活：播放期间通过前台 Service 保持存活，但屏幕关闭后
  是否继续播放取决于系统策略，未做额外保活。
- **通知栏没有播放控制按钮**：API level 8 的通知不支持操作按钮（可展开通知是
  API 11 才引入的），因此通知只是一个点击回到界面的入口，暂停/切歌需在应用内操作。
- **不响应耳机线控**：未注册 `ACTION_MEDIA_BUTTON` 接收器，耳机按键不会切歌。
- 使用 debug 签名，适合自用与测试，不用于上架。
