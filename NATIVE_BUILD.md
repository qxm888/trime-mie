# 原生依赖、源码来源与已知缺口

## 当前构建路径

咩版 3.3.13-mie.15 延用既有 `app/prebuilt/arm64-v8a/librime_jni.so`，没有在本轮从头重编译 Rime 原生引擎。Android 前端、JNI 接口代码、CMake 配置和修改后的构建脚本已随仓库公开。

预编译 Rime JNI SHA-256：

```text
b05302072dbb4ecf4a68bed0967d711ffa7efc5ea9bf5a8e906e8badc6982690
```

## 子模块来源

`.gitmodules` 保留上游仓库地址，[submodules.lock.json](docs/upstream/submodules.lock.json) 列出前端基线 `f5008260` 记录的确切子模块提交。Git 仓库中保留相应 gitlink，可用以下命令取得这些上游源码：

```sh
git submodule update --init --recursive
```

ZIP 用户请按锁定清单取得对应仓库和提交，再递归初始化各自子模块；不要用各仓库当前最新分支替代锁定值。预编译的前端构建不依赖此步骤。

**这些提交是前端基线的锁定值，并非已经证实生成了当前 SO 的完整原生构建记录。** 现有工作区缺少预编译库确切来源、全部依赖提交及原始构建参数，因此不能宣称当前源码包已经补齐该 SO 的全部对应源码。该问题仍需追溯原始构建来源，或在后续版本用来源完整记录的原生构建替换。

## 从源码构建原生库的方向

上游原生入口为 `app/src/main/jni/CMakeLists.txt`，Gradle 原生约定插件在 `app/prebuilt` 存在时优先使用预编译路径。要实验源码构建，应在独立副本中初始化全部子模块、核对 NDK/CMake 与 Boost 等依赖设置，再移走该副本的 `app/prebuilt`，按上游构建流程处理补丁并编译。

这条完整原生重建路径没有在本次发布中验证，不能把上述步骤当作已保证成功的构建配方。请勿在保存唯一原生库的工作区直接删除文件。

## 离线语音依赖

`app/libs/sherpa-onnx-1.13.4.aar` 对应 [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 运行时，按 Apache-2.0 使用；实际文件哈希见[依赖清单](docs/预编译依赖.json)。相关声明见 `app/src/main/assets/licenses/`。

模型文件不随源码或 APK 发布，应在应用内下载或导入，并遵守各模型的许可。BiBi Keyboard 适配的来源和声明见[第三方声明](docs/第三方声明.md)。

## 分发状态

此次公开了现有应用源码及构建材料，不上传签名私钥、用户数据或 API Key。项目采用 GPL-3.0-or-later；预编译依赖来源缺口仍需解决，保留许可和致谢不能替代补全对应源码。

## mie.16

本次继续使用上述传统预编译引擎，不含新目录支持及迁移功能。

## V3.0.7 启动桥

原预编译引擎哈希及来源边界不变。新增`app/src/main/native-startup/`从源码编译`libtrime_startup.so`，动态取得原引擎公开的`rime_get_api`；普通启动只初始化与注册任务，不触发维护。桥的C API头文件及BSD许可保存在vendor/，来源提交有记录。预编译模式使用NDK28.0.13004108、CMake3.22.1（可通过原有ndkVersion属性覆盖NDK），完整引擎源码模式也包含该目标。桥未替换JNI、不修改API函数表或原引擎机器码。
