# 构建同文输入法咩版

本说明对应 **V3.0.7 / versionCode 20261205**。正式 APK 使用原发布证书。

## 1. 获取源码

```sh
git clone https://github.com/qxm888/trime-mie.git
cd trime-mie
```

此版本先以本地交接包提供：解压 `trime-mie-v3.0.7-source.zip`，进入 `repository/`。接手者完成 GitHub 发布后，可使用 `v3.0.7` 标签或对应 Release 附件；本说明不代表远端标签已经建立。

旧 mie.15 的初始标签仅包含文档，旧版完整源码需使用其附件或补充源码标签。

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
| Android NDK | 28.0.13004108 |
| Android SDK CMake | 3.22.1 |
| 本次发布 ABI | arm64-v8a |

安装 JDK 与 Android SDK 后设置 `JAVA_HOME`，并在项目根目录创建**仅本地使用**的 `local.properties`：

```properties
sdk.dir=/你的/Android/Sdk/绝对路径
```

也可设置 `ANDROID_HOME` 指向 SDK。请不要照抄示例目录。在 Android Studio SDK Manager 或 sdkmanager 中安装 `platforms;android-37`、`build-tools;36.0.0` 和 platform-tools，并按提示接受对应 SDK 许可。首次构建需要联网下载 Gradle 与 Maven 依赖。

仓库已包含以下构建依赖：

- `app/prebuilt/arm64-v8a/librime_jni.so`：沿用的 Rime JNI 预编译库。
- `app/libs/sherpa-onnx-1.13.4.aar`：离线语音运行时。

文件校验值见 [预编译依赖清单](docs/预编译依赖.json)。这条预编译构建路径不要求先初始化 Rime 原生子模块；V3.0.7起需安装`ndk;28.0.13004108`与`cmake;3.22.1`，仅编译独立启动桥`libtrime_startup.so`。它并不等于已经从源码重建上述两个二进制依赖；完整来源说明见 [NATIVE_BUILD.md](NATIVE_BUILD.md)。

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

- 发布快照保留ZIP无.git时可用的构建属性；包名、用户可见版本与文件名由app/build.gradle.kts确定。签名、时间、工具链和环境会使自行构建的APK哈希不同，不承诺逐字节复现。
- V3.0.7的正式构建和咩2构建记录随交付提供。本轮没有运行测试或手机回归；旧版检查结果不自动代表本版已通过。
- 原预编译Rime库的完整来源仍需补全，详见原生说明。独立启动桥从随包源码编译，不能据此宣称已重建原引擎。
- 使用传统rime目录；首次安装、修改方案词典、恢复数据或删除build后，由用户在设置右上角菜单手动选择“部署”。
