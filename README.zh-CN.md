# Nothing Music

**一个专注、不打扰的 Android 音乐播放器。**

本地曲库与云盘音乐放在一处 —— 5 段均衡器、逐句歌词、睡眠定时器，以及克制的单色点阵界面。

![Kotlin 2.2](https://img.shields.io/badge/Kotlin-2.2-purple)
![Android 26+](https://img.shields.io/badge/Android-26%2B-blue)
![Media3/ExoPlayer 1.3.1](https://img.shields.io/badge/Media3%2FExoPlayer-1.3.1-9cf)
![MIT](https://img.shields.io/badge/License-MIT-green)

> [English](README.md) | 简体中文

---

## 简介

Nothing Music 是一个用 **Kotlin** 与 **Jetpack Compose**（Material 3）编写的原生 Android 音乐播放器，通过 **Media3/ExoPlayer** 播放本地音频和远程来源上的音乐 —— App 本身不需要注册账号，也没有广告。本地文件索引进 **Room** 数据库；远程曲库同步进独立的数据库，按需流式播放。整体界面是克制、高对比的点阵风格。

## 功能

- **本地曲库** —— 通过存储访问框架扫描文件夹，元数据持久化到本地 Room/SQLite 数据库。支持 MP3、AAC、M4A、FLAC、OGG、Opus、WAV、ALAC、AIFF、WMA、DSF、DFF。
- **远程来源** —— 接入云盘与自建服务，同步曲库后按需流式播放：
  - **阿里云盘** —— OpenAPI 客户端，签名直链解析，内嵌歌词/封面探测。
  - **OpenList / AList** —— AList v3 接口（`/api/fs/list`、`/api/fs/get`），OpenList 挂载的任何网盘都能接。
  - **WebDAV** —— HTTP Range 流式读取，支持旁挂歌词与封面。
  - **SMB** —— 直连 NAS 共享会话流式播放。
  - **Navidrome** —— Subsonic 协议兼容的音乐服务器。
- **逐句歌词** —— 优先读内嵌歌词，其次读旁挂文件；两者都没有时回退到 [LRCLIB](https://lrclib.net) 在线匹配。歌词内联显示在播放页。
- **前台播放服务** —— `mediaPlayback` 前台服务（Media3 `MediaSessionService`），息屏继续播放，带唤醒锁。
- **5 段均衡器** —— 每段 ±15 dB。
- **睡眠定时器** —— 淡出后按设定时间停止播放。
- **歌单与收藏** —— 可创建歌单、收藏曲目。
- **即时搜索** —— 边输入边搜索曲目、艺术家、专辑。
- **中文本地化** —— 完整 `values-zh` 翻译，并可在设置里手动切换语言与主题。

## 环境要求

- Android 8.0+（API 26），目标 API 36
- JDK 17（推荐 Temurin，Gradle 由自带 wrapper 提供）
- Android SDK 36（`local.properties` → `sdk.dir`）

## 构建

项目自带 Gradle wrapper，无需本地安装 Gradle。把 `local.properties` 指向你的 Android SDK（或设置 `ANDROID_HOME`）—— 该文件与机器相关，已在 gitignore 中：

```
sdk.dir=/path/to/android-sdk
```

```bash
./gradlew app:assembleDebug      # debug 构建（包名后缀 `.debug`）
./gradlew app:assembleRelease    # release 构建（混淆 + 资源压缩）
```

产物位于 `app/build/outputs/apk/.../app-*.apk`，可直接安装到设备或模拟器。

## 测试

```bash
./gradlew app:testDebugUnitTest  # 单元测试（JUnit + Robolectric）
./gradlew app:lintDebug          # Android lint
```

## 项目结构

```
NothingMusic/
├── build.gradle.kts           # 根构建脚本 —— 声明共享插件
├── settings.gradle.kts        # Gradle 多模块配置
├── gradlew                    # Gradle wrapper
├── gradle/
│   ├── libs.versions.toml     # 版本目录（依赖与插件）
│   └── wrapper/               # wrapper jar 与配置
└── app/
    ├── build.gradle.kts       # App 模块（Android + Kotlin + Compose + KSP）
    ├── proguard-rules.pro     # Room 实体与 Media3 的 keep 规则
    └── src/main/
        ├── AndroidManifest.xml      # 权限、Activity、媒体服务、封面 Provider
        ├── java/com/nothingmusic/
        │   ├── MainActivity.kt      # 入口（Hilt + NavGraph）
        │   ├── data/                # Room 数据库、仓库、歌词解析、音频扫描
        │   ├── domain/              # 领域模型与仓库接口
        │   ├── remote/              # 远程曲目与播放层的映射、水合
        │   ├── service/             # 播放服务、控制器、睡眠定时器
        │   ├── ui/                  # Compose 页面、组件、主题、导航
        │   └── util/                # 常量、文件/权限工具、设置、主题模式
        ├── java/com/mica/music/     # 远程来源引擎（移植部分）
        │   ├── data/remote/         # 来源模型、凭据存储、同步引擎、
        │   │                        #   封面/歌词传输、播放路由
        │   │   ├── aliyundrive/     # 阿里云盘 OpenAPI 客户端
        │   │   ├── alist/           # OpenList / AList v3 客户端
        │   │   ├── navidrome/       # Navidrome（Subsonic）客户端
        │   │   ├── webdav/          # WebDAV 客户端
        │   │   └── smb/             # SMB 客户端
        │   └── media/               # MicaRoutingDataSource（mica-remote:// 路由）
        └── res/                     # 图标、字体、主题、字符串（含 values-zh）
```

## 架构说明

- **Hilt** 负责依赖注入（`di/AppModule.kt`）：单例 `MusicRepositoryImpl` 持有本地 Room 数据库，`AudioScanner` 扫描入库，`PlayerViewModel` 把播放状态暴露给 Compose 界面。
- **远程曲库**放在独立的 Room 数据库（`RemoteDatabase`）里，由 `RemoteCatalogRepository` 统一管理。每种来源各有同步引擎（`AliyunDriveSourceSync`、`AListSourceSync`、WebDAV、SMB、Navidrome），遍历远端目录后原子发布曲库快照；元数据与封面只在播放时按需探测，不做全量下载。
- **播放**经由 `MicaRoutingDataSource`，把 `mica-remote://` URI 路由到对应来源适配器解析出的有时效直链。`PlaybackService`（`service/PlaybackService.kt`）是持有 ExoPlayer `PlayerController` 与媒体会话的前台服务，与 UI 解耦。
- **凭据**保存在 `AndroidKeystoreRemoteCredentialStore`，不写进普通偏好设置。
- **歌词**由 `LrcLyricsResolver` 按「内嵌标签 → 旁挂文件 → LRCLIB 在线匹配」顺序解析，并按版本落盘缓存。
- **导航**使用 `androidx.navigation.compose`（`ui/navigation/NavGraph.kt`），路由包含曲库、云盘、来源管理、设置、播放页、播放队列、均衡器、文件夹选择、专辑详情与歌单详情。
- **主题**（`ui/theme/`）是自定义的单色 Material 3 主题，配点阵标题字体，并支持手动切换深色/浅色。

## 自建来源注意事项

- **OpenList / AList**：App 对接的是 AList v3 接口。服务器地址填 `http(s)://主机:端口` —— 带路径或查询参数会被拒绝。用户名与密码同时留空表示游客访问，需要服务器端已放开游客读取权限；否则请用一个能读取音乐目录的账号。
- **HTTP / 自签名证书**：自建服务通常跑在局域网 `http://` 上，而 Android 9 起默认拦截明文流量，因此 App 在清单中放行了明文 HTTP。
- **阿里云盘**需要 `refresh_token`；由于个人开发者凭据申请已暂停，令牌刷新走中转服务。

## 参与贡献

这是一个开源项目，环境配置、构建命令与 PR 规范见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 许可证

基于 **MIT 许可证** 发布，详见 [LICENSE](LICENSE)。
