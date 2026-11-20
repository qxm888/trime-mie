# 内置资源许可声明

本目录（`app/src/main/res/raw/`）下的以下**二进制**资源无法内嵌 SPDX 注释，
其许可在此统一声明。

## 声明

以下资源由咩版作者 **qxm888（luminous-spirit）** 独立创作，
未取自上游同文（[osfans/trime](https://github.com/osfans/trime)）或
[CatTrime](https://github.com/lzlv312/CatTrime)。

| 文件 | 内容 | 说明 |
| --- | --- | --- |
| `mie_builtin_icons.zip` | 内置图标包 | 14 套内置主题配套图标 |
| `mie_premium_materials.zip` | 精雕一辑材质包（109 个 PNG，4.0 MB） | 霁银·柔光、深空·钛影、云汐·软屿、墨绒·格调、竹月·青釉 五套 |
| `mie_frost_background.png` | 雾凇背景图 | 内置主题背景 |
| `typing_orb.wav` | 打字游戏音效 | 发射音 |
| `typing_heart.wav` | 打字游戏音效 | 心形弹道音 |
| `typing_hit.wav` | 打字游戏音效 | 命中音 |

## 许可

```
SPDX-FileCopyrightText: 2026 qxm888 (luminous-spirit)
SPDX-License-Identifier: GPL-3.0-or-later OR LicenseRef-Proprietary
```

版权人（qxm888 / luminous-spirit）对本资源保留按专有条款另行授权的权利。

## 关于音效

`typing_*.wav` 三个文件是程序生成的合成音（非采样素材），
见 `ime/game/TypingGameSound.kt` 的加载方式与原始生成记录。

## 关于字体

本目录不含字体文件。主题若引用系统字体，其权利归字体原作者；
本仓库不分发用户皮肤或第三方字体。
