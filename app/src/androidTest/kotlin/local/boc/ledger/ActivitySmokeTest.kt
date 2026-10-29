package local.boc.ledger

import android.os.SystemClock
import android.test.ActivityInstrumentationTestCase2
import android.view.accessibility.AccessibilityNodeInfo

/** Runs only against the isolated emulator, using the APK's synthetic fixtures. */
@Suppress("DEPRECATION")
class ActivitySmokeTest:ActivityInstrumentationTestCase2<MainActivity>(MainActivity::class.java) {
    override fun setUp() {super.setUp();instrumentation.targetContext.getSharedPreferences("billing-demo",0).edit().clear().putInt("statement_day",22).putInt("repayment_day",12).putInt("repayment_month",1).commit()}
    private fun node(label:String,exact:Boolean=true):AccessibilityNodeInfo? {
        val root=instrumentation.uiAutomation.rootInActiveWindow ?: return null
        fun walk(n:AccessibilityNodeInfo):AccessibilityNodeInfo? {
            val value=n.text?.toString().orEmpty()
            if(if(exact) value==label else value.contains(label)) return n
            for(i in 0 until n.childCount) { val child=n.getChild(i) ?: continue;walk(child)?.let {return it} }
            return null
        }
        return walk(root)
    }
    private fun waitFor(label:String,exact:Boolean=true):AccessibilityNodeInfo {
        val deadline=SystemClock.uptimeMillis()+120000
        while(SystemClock.uptimeMillis()<deadline) {
            node(label,exact)?.let {return it}
            SystemClock.sleep(300)
        }
        throw AssertionError("UI text not found: $label")
    }
    private fun tap(label:String) {
        var n:AccessibilityNodeInfo?=waitFor(label)
        while(n!=null && !n.isClickable) n=n.parent
        assertTrue("Clickable node not found for $label",n?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true)
    }
    private fun capture(name:String) {
        instrumentation.waitForIdleSync();SystemClock.sleep(600)
        val file=java.io.File(activity.getExternalFilesDir(null),"v15-$name.png")
        var bitmap=instrumentation.uiAutomation.takeScreenshot()
        repeat(4) {if(bitmap==null) {SystemClock.sleep(500);bitmap=instrumentation.uiAutomation.takeScreenshot()}}
        if(bitmap!=null) file.outputStream().use {bitmap!!.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
    }
    fun testPermissionFreeDemoAndPersistentLedger() {
        val today=java.time.LocalDate.now();val cycle=RepaymentDate.upcomingCycle(today,22,1,12)
        val day=cycle.end.minusDays(5)
        val date="${day.year}年%02d月%02d日".format(day.monthValue,day.dayOfMonth)
        LedgerDb(instrumentation.targetContext,"demo.db").use {db->
            // Remove only this test's synthetic rows before reseeding, preserving the original fixtures.
            db.writableDatabase.execSQL("DELETE FROM links WHERE adjustment IN (SELECT id FROM sms WHERE sim='synthetic-ui') OR purchase IN (SELECT id FROM sms WHERE sim='synthetic-ui')")
            db.writableDatabase.execSQL("DELETE FROM audit WHERE sms_id IN (SELECT id FROM sms WHERE sim='synthetic-ui')")
            db.writableDatabase.execSQL("DELETE FROM sms WHERE sim='synthetic-ui'")
            val messages=listOf(
                "您的借记卡账户7304，于06月15日收入(网银跨行)人民币12000.00元,交易后余额19000.00【中国银行】",
                "您的借记卡账户7304，于06月18日网上支付支取人民币24.00元,交易后余额18976.00【中国银行】",
                "您的借记卡账户7304，于06月19日手机银行支取(跨行支付)人民币200.00元,交易后余额18776.00【中国银行】",
                "您的信用卡4826于$date，在SYNTH_CLOUD消费USD128.40元。【中国银行】",
                "您的信用卡4826于$date，在SYNTH_CLOUD撤销USD8.40元。【中国银行】",
                "您的信用卡4826于${date}成功退货USD4.00元。【中国银行】")
            messages.forEachIndexed {i,body->db.ingest("ui-v15-$i","95566",body,System.currentTimeMillis()+i,"synthetic-ui",true)}
        }
        activity;waitFor("我的账本")
        tap("更多");tap("打开合成演示");waitFor("合成演示 · 本地独立账本")
        capture("home")
        LedgerDb(activity,"demo.db").use {db->
            val rows=db.all().filter {it.recognizedTransaction};val flow=DebitDashboard.flows(rows.map(StoredSms::entry),rows.map{it.id}.toSet()).getValue("CNY")
            assertTrue(flow.income.signum()>0);assertTrue(flow.spending.signum()>0)
            val adjustment=rows.first {it.source.startsWith("demo:") && it.body.contains("SYNTH_CLOUD撤销")}
            assertTrue(db.links().any {it.adjustmentId==adjustment.id})
        }
        tap("流水");waitFor("借记流水");capture("debit")
        tap("信用卡");waitFor("US$ 120.00");capture("credit")
        val pending=node("待关联",false);assertNotNull(pending)
        pending!!.performAction(AccessibilityNodeInfo.ACTION_CLICK);waitFor("查看原文与候选",false);tap("关闭")
        tap("设置");waitFor("日期设置");capture("settings")
        waitFor("出账次月 12 日  →")
        tap("更多");tap("切回手机账本");waitFor("日期设置")
        assertEquals(0,activity.getSharedPreferences("billing",0).getInt("repayment_day",0))
    }
}
