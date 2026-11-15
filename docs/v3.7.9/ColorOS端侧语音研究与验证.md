# ColorOS 端侧语音研究与验证

2026-11-15。V3.7.9 / versionCode 20271123，经用户授权随 `v3.7.9` 正式发布。

## 结论与边界

本轮加入的是 Android 标准系统端侧语音适配。当前连接的 PKX110（Android 16 / ColorOS V16.1.0）即使安装了“系统设置 → AI → 端侧大模型”，也没有向第三方应用提供标准端侧语音识别服务。真实系统语音转文字未打通，不能将此适配称为“ColorOS 专属 ASR 已接入”。

## 官方接口核查

- [AIUnit 业务介绍](https://open.oppomobile.com/documentation/page/info?id=11630)：统一 AI 能力平台，支持端云协同。不能由“端侧 AI”推断所有能力始终离线。
- [AIUnit 开发指南](https://open.oppomobile.com/documentation/page/info?id=11670)：需注册企业开发者、创建应用并申请开通。示例依赖 core / vision / nlp / common 1.0.6；Maven 仓库密码需申请，初始化有鉴权结果回调。
- [AIUnit 产品手册](https://open.oppomobile.com/documentation/page/info?id=11669)：本轮公开目录和正文只找到人脸、OCR、图像、日程解析和扫码示例，未找到 ASR 类、音频流输入或语音转文字 Demo。公开页面没有给出可直接集成的 ASR 契约，无法确认所谓“申请 ASR 就能调用 Find X9 Pro 端侧模型”的说法。
- [SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer)：Android 12+ 提供 isOnDeviceRecognitionAvailable / createOnDeviceSpeechRecognizer；要求主线程调用并及时 destroy。
- [RecognizerIntent](https://developer.android.com/reference/android/speech/RecognizerIntent)：EXTRA_PREFER_OFFLINE 可被实现忽略，单独设置此标志不能作为端侧证据。因此本项目仅创建专用端侧识别器，不创建普通识别器作为回退。

## 当前手机的实际结果

- 标准端侧服务配置 config_defaultOnDeviceSpeechRecognitionService 为空。
- 公开 RecognitionService 查询仅发现说点啥与 Claude；两者不是 ColorOS 的端侧模型接口。
- 独立应用中 isOnDeviceRecognitionAvailable 返回 false。
- createOnDeviceSpeechRecognizer 抛出 UnsupportedOperationException，验证系统确实拒绝创建端侧识别器。
- 无障碍语音包的 SubtitleService 受 oppo.permission.OPPO_COMPONENT_SAFE 保护，系统声明保护级别为 signature。摘要服务要求 AI_APP（signature|privileged），翻译服务要求 AUTHENTICATE（signature）。系统自带功能不等于第三方可调用并回传文字的 SDK；本轮未调用这些受保护服务，也未绕过权限。

## 本地实现

设置 → 语音输入 → 语音识别服务商 → 系统端侧识别。入口显示系统能力状态，可选择语言、重新检测，并打开系统语音设置。Android 13+ 在系统提供服务时查询已安装、待安装和支持的端侧语言；检测不录音、不下载模型，五秒无响应结束。

录音仅由系统服务负责，咩版不会同时启动第二个 AudioRecord。识别器按单次手势创建、在取消/错误/结果后释放；保留现有临时文字替换、编辑器归属保护和可选 AI 后处理。系统自行结束录音时界面进入等待结果状态，不重复停止服务。失败不会自动换成在线识别。在线 AI 后处理仍会按用户设置发送识别文本，不能视为全链路离线。

未选择该服务时不初始化识别器，也不加载系统模型；没有新增 SDK 或模型依赖。低于 Android 12 的设备显示版本限制，保留原有语音引擎。

## 验证范围

- 15 项语音相关单元测试通过，包括取消后的旧结果、提前松手、系统自动结束、启动/停止异常、资源释放与模型注册表。
- 独立测试包在该手机通过公开 API 不可用验证、工厂无回退处理和设置页面渲染。测试未录音、未改变当前输入法，也未覆盖正式包。
- 当前手机不提供标准端侧识别，无法实测真实语音转写效果、延迟、耗电或语言支持。支持该 API 的其他设备仍需识别实测。

OPPO 专属方案若继续推进，需要厂商实际提供 ASR SDK / Demo、输入音频与结果回调契约，以及 com.osfans.trime.mie 和发布签名的开通条件。现有公开资料不足以实现这部分；用户安装系统模型不会授予第三方应用上述能力。
