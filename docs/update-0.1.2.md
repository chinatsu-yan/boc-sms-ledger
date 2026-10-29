# 0.1.2：模板内的通用币种识别

日期：2026-10-29。应用 0.1.2 / versionCode 3，解析器版本 3；包名 local.boc.ledger、minSdk 26、targetSdk 35、SQLite 表结构和数据库版本 1 均保持不变。

## 修复前的真实范围

0.1.1 只自动识别 CNY、USD、SGD、JPY、TWD、HKD，人民币/RMB 规范为 CNY；两位币种严格限制小数位，JPY 的 `.00` 整数填充也会拒绝。EUR、GBP、AUD、KWD、BHD 等均被少量币种列表挡住，未知代码只保留原文，没有结构化金额草稿。

合成对照已复现上述限制，未使用用户真实短信、卡号、日期、商户或金额。

## 当前规则与边界

- 在既有七类中行模板内识别金额前的大写三字母代码，不再逐个添加币种列表；人民币/RMB/CNY 别名不变。币种位置由“消费/撤销/成功退货/购汇转入/购汇转出”等已确认模板锚定，不扫描商户末尾字母，不将裸金额默认为人民币。
- 确认依据是**本机 java.util.Currency 可用货币注册表中有非负单位位数的记录**。不同 Android/JVM 的资料版本可以不同，也可能包括历史货币；本机注册表不是最新货币有效性或银行支持情况的保证。代码不在表中，或单位规则不明确时，不能只凭三字母就当作已确认货币。
- 能匹配模板且金额语法有效的未确认代码保留原代码、BigDecimal 精确金额、日期等草稿和原文，明确为 NEEDS_REVIEW，默认用途待确认。合法但旧平台尚未收录的代码也使用这一路径，不判成人民币或静默丢弃。
- 未确认币种不会进入消费、收入、偿债、退款或撤销汇总，不能参与关联；购汇还款任一侧未确认时，双侧金额均保留，整条不进确认汇总，也不猜汇率方向。人工分类不能绕过未确认状态。
- 已确认代码按单位位数校验；JPY 等零单位币种接受整数或纯零小数填充，`JPY12,345.00` 精确保存为 `12345.00`，不改写为别的数。USD/EUR 等两位、KWD/BHD 等三位及本机其它单位规则共用同一机制。超出精度的非零小数、无效千分位等仍待确认，不舍入或截断原金额；格式异常保留原文。
- 确认标记随金额 JSON 持久保存。读取一个“未确认”草稿不会因当前平台现在认识代码就暗中将其变成消费；用户显式重解析时才重新评估。旧 JSON 没有该字段仍可加载。
- 没有增加新银行、新短信事件类型、多语言/区域数字格式框架、任意货币符号识别或云端货币资料同步。并非全币种、全模板、全银行保证。

平台 API 依据：[Android Currency](https://developer.android.com/reference/java/util/Currency)、[Java 21 Currency](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/Currency.html)。

## 验证结果

```sh
./gradlew --offline :core:test :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest
adb -s emulator-5556 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5556 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5556 shell am instrument -w -r -e class local.boc.ledger.DeviceSmokeTest local.boc.ledger.test/android.test.InstrumentationTestRunner
```

- 构建和测试 APK 成功；lint：No issues found。
- JVM 20 项测试通过，0 失败/错误/跳过。原 21 条合成输入状态预期保持；未知 ZZZ 的断言新增验证结构草稿保留，不再要求 event 为空。新 [通用样例](../fixtures/synthetic/general-currencies.json)有 19 条正例、10 条边界，合计已有 50 条 fixture 输入。
- 固定样例覆盖 USD/EUR/GBP/AUD/CAD/CHF/HKD/TWD/SGD/CNY、人民币/RMB、JPY 整数及 `.00`、KWD/BHD 三位小数；退款、撤销、购汇还款共用规则。覆盖千分位、商户末尾 ISO 字母、裸金额、未知三字母、无单位规则、失败通知和超精度不舍入。
- 额外逐码测试当前 JVM 表中 218 个有单位规则的记录，以及 Android 8.0 / API 26 表中 296 个有单位规则的记录。数量是这些测试运行时的表快照，可能含历史货币，不是全球当前流通货币数量或跨系统承诺。
- 模拟旧平台缺失合法 EUR 资料：原代码 EUR 与金额 1234.50 保留，状态待确认、分类待定，不进入消费汇总。未知代码在消费、撤销、退货及购汇任一侧均不进汇总；有效原币各自统计。
- Android 8.0 / API 26 的 8 项数据测试全部通过（5.194 秒），包括旧 JSON 兼容、未确认标记持久化、未知草稿保存/关闭重开、人工分类阻断、重解析、原人工覆盖/排除状态/非交易状态/来源幂等保留。
- 模拟器实际从 0.1.1 / code 2 覆盖升级为 0.1.2 / code 3，ledger.db 和 demo.db 安装前后 SHA-256 均相同。使用原调试密钥，没有清数据或数据库迁移。
- 本次未访问真实手机，未追加 Android 15 UI 验收；此前还款双通知关联、后台/长短信真机限制不变。

## 安装与存量记录

直接覆盖安装新版，不卸载或清空数据。进入手机账本，选择「更多 → 重新解析（保留人工修正）」：旧版因缺少 EUR 等代码未识别的已保存原文会按新规则处理；本机未知代码则显示金额草稿待确认。人工字段/分类覆盖、排除状态、非交易状态及来源标识保留，不需要删除或重复导入。已确认关联的记录继续保持冻结。

## 安装包证据

文件：boc-sms-ledger-0.1.2-debug.apk，942,584 字节。

APK SHA-256：`874c88c706b96f5834c4562a6e358f91362610156b20b6c5053417598a1cb62e`。

新旧签名证书 SHA-256 相同：`b4fe7012bff1aaeb0a8b46f8252b753a94727aea357d73011e79f5c2e553468a`。调试密钥、数据库、真实短信未提交 Git；本次只保存在独立分支，未推送、合并或发布 release。
