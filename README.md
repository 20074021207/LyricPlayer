# LyricPlayer · 悬浮歌词音乐播放器

极简 Android 本地音乐播放器，特点是**在屏幕顶层悬浮显示当前歌词**：退出应用、切到其他 App、锁屏，歌词依然悬浮在所有界面之上并随播放逐句滚动。

## 功能

- 扫描本地音乐库（MediaStore），列表点播，播完自动下一首；顶栏可手动刷新
- **文件夹浏览选歌**：媒体库扫描不可用（部分电视盒子/定制机的系统扫描损坏）时，点顶栏文件夹图标直接遍历文件系统，从存储列表（本机存储/U盘）开始；点击某首从该首开始播放、**长按文件夹或底部按钮可直接播放整个文件夹**
- **播放模式**：底部栏模式按钮循环切换 顺序播放 / 单曲循环 / 列表循环 / 随机播放
- **删除文件**：主列表或文件夹页**长按音频文件**，确认后删除（需存储写入权限）
- 前台服务后台播放，通知栏提供 播放/暂停/关闭 按钮
- **系统播控已接入（MediaSession）**：下拉控制中心/通知栏媒体卡片/锁屏播控/耳机线控/蓝牙耳机均可控制播放、上下曲与进度条
- **音频焦点**：来电/其他应用抢声音自动暂停（焦点归还自动恢复），短时提示音自动闪避不中断
- **拔耳机/断开蓝牙自动暂停**
- **应用内进度条**：底栏进度条可查看/拖动跳转（歌词同步跳句）
- **状态记忆**：播放模式、桌面歌词开关、悬浮窗位置、播放队列与进度（重启应用后点播放从上次位置继续）
- **列表后台加载**：媒体库+文件扫描+标签补全均在后台线程，大容量U盘不卡界面
- 自动加载与歌曲同名的 `.lrc` 歌词文件（UTF-8/GBK 等编码自动识别），按时间轴逐句切换
- 顶层桌面歌词：默认显示在**屏幕顶部居中**，拖动可移位，单击暂停/继续；无歌词时显示歌名；顶栏开关可随时启停

## 运行与构建

- 环境：Android Studio（Hedgehog 或更新）+ JDK 17，SDK Platform 34
- 用 Android Studio 打开本目录，等 Gradle Sync 自动下载依赖后直接 Run
- 或命令行构建：`./gradlew assembleDebug`（Windows 用 `gradlew.bat`），
  APK 输出在 `app/build/outputs/apk/debug/`
- 支持范围：Android 7.0（API 24）~ Android 14（API 34）

## 首次使用的权限流程

| 权限 | 用途 | 时机 |
|---|---|---|
| 音乐和音频 / 存储读取 | 扫描本地音乐 | 启动时弹窗申请 |
| 显示在其他应用上层 | 顶层悬浮歌词 | 点「桌面歌词」开关时跳转系统设置 |
| 通知 | 显示播放通知（拒绝不影响播放） | Android 13+ 随启动申请 |

## 歌词文件放置规则

与歌曲放在**同一目录**、**同名**（忽略大小写），扩展名 `.lrc`：

```
/sdcard/Music/晴天.mp3
/sdcard/Music/晴天.lrc
```

编码自动识别：UTF-8 / UTF-8 BOM / UTF-16 / GBK / GB2312（按 BOM 与严格 UTF-8 校验判断，国内常见 GBK 歌词不会乱码）。

支持的 lrc 格式（兼容 `[mm:ss]`、`[mm:ss.xx]`、`[mm:ss.xxx]`，一行多个时间标签）：

```
[ti:晴天]
[ar:周杰伦]
[00:00.00]晴天 - 周杰伦
[00:12.35]故事的小黄花
[00:16.88]从出生那年就飘着童年
```

## 代码结构

```
app/src/main/java/com/mini/lyricplayer/
├── App.kt               # Application：初始化播放器并注册悬浮歌词监听
├── MainActivity.kt      # 主界面：权限申请、歌曲列表、底部播放控制
├── FolderActivity.kt    # 文件夹浏览选歌（不依赖系统媒体扫描）
├── MusicRepository.kt   # MediaStore 扫描本地音乐
├── MusicService.kt      # 前台服务：保活 + 播放通知
├── PlayerManager.kt     # 单例播放器：MediaPlayer、播放队列、进度轮询
├── LrcParser.kt         # .lrc 解析 + 当前行查找（二分）
└── LyricOverlay.kt      # WindowManager 顶层悬浮歌词（拖动/点击）
```

## 外部控制（可选）

MusicService 已导出，可通过 intent 远程控制播放（通知栏按钮即走此路径）：

```
adb shell am startservice -n com.mini.lyricplayer/.MusicService -a com.mini.lyricplayer.action.TOGGLE  # 播放/暂停
adb shell am startservice -n com.mini.lyricplayer/.MusicService -a com.mini.lyricplayer.action.CLOSE   # 停止
```

播放状态可通过 `adb logcat -s LPState` 观察（每次状态变化输出曲目、进度与当前歌词行）。

## 实测记录

已在 Android 9（Amlogic 电视盒，API 28）上真机验证：扫描列表、播放、逐句歌词时间轴（7.8s 命中第 4 句）、悬浮窗置顶于桌面、悬浮窗单击暂停/继续、拖动、自动切歌、播完自动收尾、通知栏内容与操作均通过。
部分运营商定制盒子的后台清理器会杀死后台应用（无视前台服务与白名单），属设备侧限制。

## 已知限制（有意保持极简）

- 无进度条拖动、无上一首按钮、无歌单管理
- 不读取 MP3 内嵌歌词，只支持同名 .lrc
- 歌词样式（字号/颜色）固定，未做设置页
