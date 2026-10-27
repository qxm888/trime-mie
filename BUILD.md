# 构建同文输入法咩版

本说明对应 **V2.0.17 / versionCode 20261119**。正式 APK 由本仓库的稳定发布源码构建，使用原发布证书。

## 1. 获取源码

```sh
git clone https://github.com/qxm888/trime-mie.git
cd trime-mie
```

需要固定到已发布版本时，从 [Release](https://github.com/qxm888/trime-mie/releases/tag/v2.0.17) 下载明确命名的 `trime-mie-v2.0.17-source.zip`，解压后进入 `trime-mie-source`。

明确命名的 source.zip 附件以及 `v2.0.17` 标签都包含本次应用源码。旧 mie.15 的初始标签仅包含文档，旧版完整源码需使用其附件或补充源码标签。

## 2. 环境

以下为当前工作区构建成功时使用的版本，不表示已验证所有系统组合：

| 工具 | 版本 |
| --- | --- |
| JDK | 21 |
| Gradle | 9.8.1，由 Wrapper 下载 |
| Android Gradle Plugin | 9.3.0 |
| Kotlin | 2.3.21 |
| Android SDK Platform | 37 |
| Android SDK Build Tools | 36.0.0 |
| 本次发布 ABI | arm64-v8a |

安装 JDK 与 Android SDK 后设置 `JAVA_HOME`，并在项目根目录创建**仅本地使用**的 `local.properties`：

```properties
sdk.dir=/你的/Android/Sdk/绝对路径
```

也可设置 `ANDROID_HOME` 指向 SDK。请不要照抄示例目录。在 Android Studio SDK Manager 或 sdkmanager 中安装 `platforms;android-37`、`build-tools;36.0.0` 和 platform-tools，并按提示接受对应 SDK 许可。首次构建需要联网下载 Gradle 与 Maven 依赖。

仓库已包含以下构建依赖：

- `app/prebuilt/arm64-v8a/librime_jni.so`：沿用的 Rime JNI 预编译库。
- `app/libs/sherpa-onnx-1.13.4.aar`：离线语音运行时。

文件校验值见 [预编译依赖清单](docs/预编译依赖.json)。这条预编译构建路径不要求先初始化 Rime 原生子模块，也不要求安装 NDK/CMake。它并不等于已经从源码重建这两个二进制依赖；完整来源说明见 [NATIVE_BUILD.md](NATIVE_BUILD.md)。

## 3. 构建可直接安装的开发 APK

macOS/Linux：

```sh
chmod +x gradlew
./gradlew :app:assembleDebug -PbuildABI=arm64-v8a
```

Windows 使用 `gradlew.bat` 代替 `./gradlew`。

默认 Debug 包名为 `com.osfans.trime.mie.debug`，与正式版独立。产物位于 `app/build/outputs/apk/debug/`。

需要沿用咩2的独立包名时：

```sh
./gradlew :app:assembleDebug -PbuildABI=arm64-v8a -PvariantTag=v2
```

咩2包名为 `com.osfans.trime.debug.v2`。Debug 包包含开发回归组件，面向开发使用。

## 4. 构建 Release

```sh
./gradlew :app:assembleRelease -PbuildABI=arm64-v8a
```

正式包名为 `com.osfans.trime.mie`。Release 启用代码和资源压缩，产物目录为 `app/build/outputs/apk/release/`。**未配置自己的签名时，产物为未签名 APK，不能直接安装。**

发布者的签名私钥不随源码公开。自行构建的签名不同，不能覆盖安装官方同包名版本；可先使用独立 Debug 包，或在备份数据后处理签名冲突。

### 使用自己的签名

用 Android Studio 生成自己的签名密钥，在根目录创建 `keystore.properties`：

```properties
storeFile=/你的/签名文件绝对路径/release.jks
storePassword=填写你自己的密码
keyAlias=填写你自己的别名
keyPassword=填写你自己的密码
```

这些均为占位说明，不是现成凭据。该文件及密钥应保持本地私密并备份，不要加入版本控制。配置后重新运行 Release 命令。

公开版本签名证书 SHA-256（只用于核对）：

```text
b22fc84964778b646c395c87d93a323d21655eaffd53718a814035adf476d5bf
```

## 5. 安装与首次使用

```sh
adb install -r /路径/到/你构建的.apk
```

在系统键盘设置启用对应应用，再导入自己的 Rime 方案、词库并部署。当前资源中没有完整基础方案、词库与 OpenCC 数据；用户已有的外部 rime 数据可按[使用指南](docs/使用指南.md)配置。

语音模型单独在应用内下载或导入，不包含在 APK 中。在线引擎需要用户自己的 API Key。

## 6. 构建信息与复现边界

- 发布快照保留编译所需的版本信息属性，ZIP 中没有 `.git` 目录也可读取这些属性。
- 当前发布源码与旧工作区快照的应用/构建输入逐文件比对一致；本次只新增发布文档与视觉素材，不改应用逻辑，也不重新声明进行了编译或测试。
- 官方 APK 的 SHA-256 为 `5d9d69dd960230efa623e95d430a6357e1816bbaeadebe2c327b633410c6b97d`。签名、构建时间、环境等会使自行构建 APK 的哈希不同，不承诺逐字节复现。
- `.15` 的既有验证记录：404 项单元测试通过；Debug/Release 构建成功；Lint 无错误。该结果来自发布前工作区，不代表本次在干净环境重新构建通过。
- 预编译 Rime 库的完整来源仍需补全，详见原生说明。不能把本次源码上传当作所有二进制依赖对应源码已经核验完毕。

## mie.16 发布范围

本次发布保留传统 `rime` 目录，不包含嵌套目录迁移及万象开发渠道。新增按键外观与布局选项、主题视频入口，以及复制提示和菜单布局修复。详见 [键盘定制](docs/键盘定制.md)。

## V2.0.17 发布范围

应用版本统一为 V2.0.17，正式 arm64 产物为 `app/build/outputs/apk/release/trime-mie-v2.0.17.apk`。候选悬浮球默认关闭。继续沿用预编译 Rime 引擎，不包含新目录迁移及万象开发渠道；详细更新见 [版本说明](docs/V2.0.17更新说明.md)。
