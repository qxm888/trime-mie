# Unicode 数据快照

用于生成离线表情与符号目录，不在运行时联网下载。

- Emoji Keyboard/Display Test Data，Version 18.0：<https://www.unicode.org/Public/emoji/latest/emoji-test.txt>（本次取得后固定为随包快照，SHA-256 见 catalog-report.json）。
- UnicodeData 18.0：<https://www.unicode.org/Public/18.0.0/ucd/UnicodeData.txt>。
- 许可：Unicode License V3，全文在 `app/src/main/assets/licenses/unicode-data-LICENSE.txt`。

重新生成：在仓库根运行 `python3 tools/build_symbol_catalog.py`。需 Python 3，使用随包快照及既有 `output/keyboard-mint/symbols.json`，无网络依赖。表情只选 fully-qualified 和 component，整条保留 ZWJ、肤色、VS16、旗帜及 tag 序列。旧非 Emoji 分类全部保留；按明确字符块补充已分配符号，不声称包含 Unicode 的全部文字。

输出为一份紧凑 JSON，五色键盘共享，不复制成数百页键盘布局。生成器会核对旧分类条目包含关系并写入数量及来源哈希。
