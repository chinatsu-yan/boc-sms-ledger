package local.boc.ledger

import android.os.SystemClock
import android.test.ActivityInstrumentationTestCase2
import android.view.accessibility.AccessibilityNodeInfo

/** Runs only against the isolated emulator, using the APK's synthetic fixtures. */
@Suppress("DEPRECATION")
class ActivitySmokeTest:ActivityInstrumentationTestCase2<MainActivity>(MainActivity::class.java) {
    override fun setUp() {super.setUp();instrumentation.targetContext.getSharedPreferences("billing-demo",0).edit().clear().commit()}
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
    fun testPermissionFreeDemoAndPersistentLedger() {
        activity
        waitFor("我的账本")
        tap("更多");tap("打开合成演示");waitFor("我的账本 · 演示")
        tap("流水");waitFor("借记流水 · 演示")
        tap("信用卡");waitFor("信用卡 · 演示")
        tap("设置");waitFor("日期与账本设置 · 演示")
        waitFor("还款日：未设置")
        tap("账单日：未设置");tap("保存")
        tap("还款日：未设置");waitFor("设置还款日");tap("保存")
        waitFor("还款日：每月 1 日")
        tap("概览");waitFor("我的账本 · 演示")
        tap("更多");tap("切回手机账本");waitFor("我的账本")
        tap("设置");waitFor("还款日：未设置")
        LedgerDb(activity,"demo.db").use {assertEquals(16,it.all().size)}
    }
}
