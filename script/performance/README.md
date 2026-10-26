# 真机性能测量

本轮工具为Debug自托管Instrumentation，Android 7+；功能回归工具部分场景要求Android 10+。无需安装单独测试组件。Release不包含这些类或Instrumentation声明。

1. 构建并安装咩2：`JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew app:assembleDebug -PbuildABI=arm64-v8a -PvariantTag=v2`。
2. 手机保持解锁，不同时操作应用；固定刷新率、电源模式，避免充电温度变化。原始数据用于观察波动。
3. `python script/performance/measure.py capture --serial PHONE --label baseline --output /tmp/perf-baseline`。
4. 安装候选包，同一设备执行 `capture --label candidate --output /tmp/perf-candidate`。
5. `python script/performance/measure.py compare --baseline /tmp/perf-baseline/baseline[123].json --candidate /tmp/perf-candidate/candidate[123].json --output /tmp/comparison.json`。

capture会私下备份应用files/shared_prefs，结束时恢复并校验SHA256，恢复原默认/启用输入法。备份不进入性能报告；恢复失败会保留本机临时备份。脚本仅执行合成模型和自己的测试编辑框，报告不收集用户输入文本。

每轮固定40键模型：20次预热+80样本；实际当前键盘完整重建：10次预热+40样本。FrameMetrics覆盖整个重建压力场景（包含预热，排除FIRST_DRAW_FRAME），记录DEADLINE及丢失报告；这不是正常连续打字的掉帧率。设置页6次进程冷启动，首轮预热舍弃，报告5次。

`refresh_to_ondraw`是触发刷新至CPU进入OnDraw的时间，不能称作屏幕呈现或按键上屏延迟。内存为结束时Java已用堆/原生分配快照，包含测试工具，并非PSS或峰值。Debug开启日志和调试功能，绝对值不能替代Release测试。系统调度、温度、频率均可能影响前后结果，不以小幅波动宣布加速。

功能回归可直接运行 `adb -s PHONE shell am instrument -w com.osfans.trime.debug.v2/dev.codex.trime.smoke.Runner`，另有ModeRunner、ClipboardRunner、OptimizationRunner和BdsRunner。后者需先将用户授权的两份皮肤放入应用cache/bds1.bds、cache/bds2.bds；回归会修改配置，请先单独备份并在结束后恢复。

下一阶段可增加独立的非Debug、profileable基准构建及Macrobenchmark/Perfetto，对真实按键→commit→显示、冷/热键盘弹出和长时间打字做对照。本工具并未声称已完成这些测量。
