# 音乐播放器 (Android 2.2 / API 8)

一个可以在 **Android 2.2 (Froyo, API level 8)** 上运行的基础音乐播放器。
已编译出可直接安装的 APK：

```
MusicPlayer-1.0-android2.2.apk
```

## 功能

- 扫描指定目录（默认 `/sdcard/Music`）中的音频文件，支持子目录递归
- 列表显示歌曲名、艺术家、时长，当前播放项高亮
- 播放 / 暂停、上一首 / 下一首
- 拖动进度条跳转，实时显示 `当前时间 / 总时长`
- 音频焦点处理：来电等打断时自动暂停，恢复后继续；被短暂压低音量（duck）时自动降低音量
- 支持格式：mp3、m4a、aac、wav、ogg、flac、mid、amr、3gp、mp4

## 安装

```bash
adb install -r MusicPlayer-1.0-android2.2.apk
```

或把 APK 复制到手机存储中，用文件管理器点击安装
（需要在系统设置中允许“未知来源”）。

首次使用：把 mp3 放到 `/sdcard/Music`，打开应用后点“扫描”。
如果音乐在别的目录，在输入框里填写该目录路径再点“扫描”。

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
    drawable-{l,m,h,xh}dpi/ic_launcher.png   图标（由脚本生成）
tools/
  fetch_toolchain.ps1                    下载 JDK 8 + Android SDK（平台 8、构建工具 25.0.3）
  build_apk.ps1                          完整构建流程
  verify_apk.ps1                         校验 APK（清单、内容、签名、对齐）
  install_git.ps1                        下载便携版 MinGit（本机无 git 时用）
  serve_apk.py                           局域网分发 APK 给手机安装
  check_usb.ps1                          排查 Android USB 设备/驱动状态（只读）
  make_icons.py                          生成各密度启动图标
build/                                   中间产物与构建日志（可删除，不入库）
```

## 重新编译

```powershell
# 1) 首次：下载工具链（JDK 8 + platform-8 + build-tools 25.0.3，约 220 MB）
powershell -NoProfile -ExecutionPolicy Bypass -File tools\fetch_toolchain.ps1

# 2) 构建 APK
powershell -NoProfile -ExecutionPolicy Bypass -File tools\build_apk.ps1

# 3) 校验
powershell -NoProfile -ExecutionPolicy Bypass -File tools\verify_apk.ps1
```

构建链路：`aapt`（生成 R.java + 打包资源）→ `javac`（`-source/-target 1.6`，
`-bootclasspath android.jar`）→ `dx`（生成 classes.dex）→ `aapt add`（把 dex 加入
APK）→ `apksigner`（仅 v1 签名）→ `zipalign`。

## 兼容性说明（为什么这样构建）

- **Java 6 字节码**：本机 JDK 8 已不支持 `-source 1.6`，会退化为 Java 8 字节码
  （major 52），而 Android 2.2 的 `dx` 无法处理。实际编译使用的是同系列
  **JDK 8u504**，它仍支持 `-source 1.6`，产出 major 50 的 class 文件，
  已用 `dexdump` 逐类校验。
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
- 使用 debug 签名（本地生成的 `tools/debug.keystore`，不入库），
  适合自用与测试，不用于上架。

## 版本控制

`.gitignore` 已配置，以下内容**不会**入库：

- `tools/jdk/`、`tools/sdk/`、`tools/mingit/`、`tools/downloads/`（约 630 MB 工具链）
- `tools/*.keystore`（签名私钥）
- `build/`（构建中间产物）

因此克隆后需先执行 `tools/fetch_toolchain.ps1` 重建工具链，再构建。

### 推送到 GitHub

本机对 `github.com:443` 直连可能超时。若 `git push` 报
`Failed to connect to github.com:443 after 21066 ms`，请为 git 配置代理：

```bash
git config --global http.proxy http://127.0.0.1:7890   # 端口按你的代理修改
# 或改用 SSH：
git remote set-url origin git@github.com:用户名/仓库名.git
```

## 手机端安装（不走 USB 调试）

老机型的 USB 枚举可能失败（Windows 报「未知 USB 设备（设备描述符请求失败）」），
此时可用局域网分发代替：

```powershell
python tools\serve_apk.py      # 监听 0.0.0.0:8000
```

然后用手机浏览器打开 `http://<电脑局域网IP>:8000/`，点击下载并安装。

