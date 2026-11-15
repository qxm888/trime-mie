# V3.7.11 Android 16 背景玻璃兜底

2026-11-15 经用户授权发布，标签 `v3.7.11`，版本码 20271125，包名 `com.osfans.trime.mie`，沿用原签名。Release 提供 arm64-v8a APK、完整标签源码、V3.7.10 起增量补丁、验证记录与 SHA-256 校验值。

## 根因

用户反馈第三方 YAML 主题「机械键盘A-晶透」（Chopper 系列）打开「背景玻璃」后，键盘背后不模糊。检查配置确认 `backdrop.enabled=true`、`systemBlur=true`，系统 `isCrossWindowBlurEnabled` 也为 true，但屏幕上看不到任何模糊。

原因是该主题的 `backdrop.publicWindow` 取默认值 `false`，代码于是走「区域模糊」路径：反射调用 @hide 的 `ViewRootImpl.createBackgroundBlurDrawable()`。**Android 16（API 36）已删除该方法**，反射抛出 `NoSuchMethodException`，被 `runCatching` 吞掉后 `obtain()` 返回 null——不崩溃、不报错、不打日志，只是安静地什么都不画。这类静默失败在用户侧表现为「开关打开了但没效果」，很难自行定位。

内置「液态玻璃·动态」不受影响，因为它的 YAML 显式写了 `publicWindow: true`，走的是公开的 `Window.setBackgroundBlurRadius()`。

## 设备实测证据

在反馈设备（Android 16 / ColorOS 16，API 36）上用独立最小探针验证：

```
ViewRootImpl 类存在，declaredMethods = 64
名字含 blur 的声明方法数 = 0
getDeclaredMethod("createBackgroundBlurDrawable") → NoSuchMethodException
Window.setBackgroundBlurRadius → 公开 API 存在，调用成功
isCrossWindowBlurEnabled = true
```

同时 `dumpsys window` 中 `mBlurEnabled=true`，`dumpsys SurfaceFlinger` 的 `backgroundBlurRadius` 字段存在，说明系统模糊能力本身正常，问题只在调用通道。

## 修复

- `SystemBackdrop.regionBlurAvailable()`：显式探测 `createBackgroundBlurDrawable` 是否可反射，结果缓存一次（方法表在进程内不变）。
- `PanelBackdrop.resolvePublicWindow()`：`publicWindow` 为 true 时一定走公开子窗口；为 false 时若区域模糊不可用则自动改道公开子窗口。旧配置缺少该字段仍按 false 解码，靠兜底继续工作，用户不需要修改主题 YAML。
- 键盘面板（`InputView.applyPanelBackdrop`）与悬浮候选窗（`CandidatesView.withPanelBackdrop`、`styleWindow`）三处调用点统一改用 `resolvePublicWindow()`。
- 外观设置「键盘整体 · 真模糊背景（背板）」补上「强制使用公开窗口模糊」开关，并在标题里标出当前系统实际走的通道，避免用户以为开关无效。
- 反射失败时的诊断日志补充 `region` 字段，便于区分「接口被移除」与「ROM 策略拦截」。

内置两套液态玻璃主题的 YAML 未改动。

## 验证

- `compileDebugKotlin` 通过；全量单元测试 **688 项通过，0 失败、0 跳过**。
- 新增用例 `region blur falls back to the public window on Android 16`：覆盖显式开启公开窗口、旧配置自动兜底、JSON 缺字段解码三种情形。
- Release APK 构建通过，签名证书 SHA-256 `b22fc84964778b646c395c87d93a323d21655eaffd53718a814035adf476d5bf`，与原发布证书一致，可覆盖升级；16 KB ZIP 对齐与包名版本号校验通过。
- 真机：Android 16 / ColorOS 16 安装后由用户确认主题背景玻璃恢复正常模糊。

## 限制

区域模糊在 Android 12–15 仍可用，本版不改变那条路径的行为。公开子窗口通道受设备与系统状态约束（省电模式、系统模糊开关、厂商窗口策略），系统关闭模糊时仍回退到自带背景，不宣称所有机型一致。未穷尽其它系统版本与第三方主题的实测。
