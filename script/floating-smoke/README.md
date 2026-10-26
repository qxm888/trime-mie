# 手机功能回归

咩2 Debug包自托管Instrumentation，无需额外安装测试组件；正式版没有测试入口。手机启用并选择咩2后执行：

```bash
adb -s PHONE shell am instrument -w com.osfans.trime.debug.v2/dev.codex.trime.smoke.Runner
```

- Runner：3.3.13-mie.9起转调ModeWindowChecks，验证输入统计后两个真实按钮、原生悬浮卡片、候选栏同卡片、拖动、实际双指缩放、真实键位烘焙、Shift状态、触摸穿透、左右单手、等比滑条双向联动和底部拖动条。旧版独立Dialog/粉色外沿控件断言不再适用。
- ModeRunner：主题/自定义隔离、行高行距、背景/工具栏/字体/热力及本地语音回调，核对原主题YAML。
- ClipboardRunner：分组、二级搜索/删除菜单及输入桥；删除确认选择取消。
- OptimizationRunner：同一悬浮InputView的实际Shift+a上屏、布局切换、缩放、配置保存失败恢复、图片采样刷新和隔离测试目录迁移。
- BdsRunner：cache/bds1.bds和cache/bds2.bds两份已授权皮肤的导入/四页四变体/按压动画/九宫格/元数据往返/文件选择回调。导入转换等待上限90秒。

测试会临时编辑本应用配置/统计。测试前备份自己的files、shared_prefs和原默认输入法；收尾停写后恢复并校验。BdsRunner与目录迁移检查只删除自身本轮创建的资源；不使用用户原词库作删除测试。不截图、不读取其他应用界面；语音回调测试不录音。源目录run.sh保留独立组件模式，优先使用自托管命令。

统计基线及自动配置恢复工具见script/performance/README.md。
