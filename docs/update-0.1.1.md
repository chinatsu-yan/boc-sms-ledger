# 0.1.1：TWD / HKD 短信识别修复

日期：2026-10-29。版本名 0.1.1，versionCode 2；包名 local.boc.ledger，minSdk 26 / targetSdk 35 不变。

## 原因与最小改动

旧解析器的金额币种正则只列出人民币/RMB/CNY/USD/SGD/JPY，漏列 TWD、HKD。

使用替换卡尾、日期、商户和金额的合成短信分别复现：TWD 千分位两位小数和 HKD 普通金额均为 NEEDS_REVIEW；仅将币种替换为 USD，同样的商户、日期和金额格式均为 PARSED。旧版独立 Money 校验已能接受 TWD、HKD 两位小数，因此根因为模板币种列表，非千分位、连字符商户或来源去重。

0.1.1 只在既有金额模板补充这两个币种，并将解析器版本递增为 2；保留整条匹配、七类事件、BigDecimal 精确金额、未知待确认、来源幂等和原有重解析/人工覆盖规则。不新增拼接拆分逻辑，每条合成样例代表独立短信。没有数据库结构或权限变化，没有新增依赖。

真实反馈未写入仓库、测试或运行日志；结构等价用例见 [currency-variants.json](../fixtures/synthetic/currency-variants.json)。

## 验证

```sh
./gradlew --offline :core:test :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest
adb -s emulator-5556 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5556 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5556 shell am instrument -w -r -e class local.boc.ledger.DeviceSmokeTest local.boc.ledger.test/android.test.InstrumentationTestRunner
```

- 修改解析器前，新回归测试复现失败：14 项中 2 项失败；修改后 14 项全部通过，0 失败/错误/跳过。
- 原来的 9 条完整样例和 7 条边界输入不变，全部通过；补充 TWD / HKD 消费、TWD 撤销、HKD 退货，以及无效千分位、过多小数和未知币种待确认。TWD 与 HKD 各自汇总，不变为 CNY，不跨币相加。
- APK 和设备测试包构建成功；lint 为 No issues found。
- Android 8.0 / API 26 云端模拟器上，0.1.0 / code 1 覆盖安装到 0.1.1 / code 2 成功；安装前后 ledger.db 和 demo.db 的 SHA-256 均完全一致，未清数据。
- 5 项设备数据测试通过（2.044 秒）。新增用例模拟 v1 已落库的 NEEDS_REVIEW / event=NULL / parser_version=1 记录，调用现有 reparse 后变为识别成功、版本 2；同时验证人工金额覆盖、排除状态、原文、来源标识与行 ID、操作历史、重扫幂等及关闭重开数据库。
- 本次未操作真实手机，未补做 Android 15 界面验收；首版已记录的 Android 15 模拟器 System UI 阻塞与还款双通知关联限制未改变。

## 安装与已有记录

1. 直接覆盖安装新版 APK；不要卸载旧版或清空应用数据。
2. 打开手机账本；如在合成演示模式，先切回手机账本。
3. 选择「更多 → 重新解析（保留人工修正）」。本次两类币种导致的已保存未识别原文会重新识别，无需重复导入或删除记录。
4. 原有人工字段/分类覆盖仍优先展示，解析结果在下层更新；排除状态保留，有操作历史可追溯。已有确认关联的记录保持冻结，不会自动解除关联或覆盖人工结果。

如果短信此前未导入，仍使用原有历史范围导入/补扫；重复导入同来源不会新增交易。若状态是来源未确认，识别成功后仍需用户核对来源，未确认来源不会自动计入汇总。

## 安装包证据

构建输出 `app/build/outputs/apk/debug/app-debug.apk`；交付副本 `boc-sms-ledger-0.1.1-debug.apk`。

- APK SHA-256：`31604c66ea67222519eff785b40e143bb1f0048646af2cd27e26f625cfcab0c0`。
- 文件大小：914,610 字节。
- 新旧 APK 签名证书 SHA-256 一致：`b4fe7012bff1aaeb0a8b46f8252b753a94727aea357d73011e79f5c2e553468a`。
- 使用原有忽略的调试密钥；未修改签名配置、数据库版本或包名，未提交密钥、数据库或真实短信。
- 本次仅独立分支本地提交，未推送、合并或发布 release。
