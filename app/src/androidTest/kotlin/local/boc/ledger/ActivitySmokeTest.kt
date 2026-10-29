package local.boc.ledger

import android.os.SystemClock
import android.test.ActivityInstrumentationTestCase2
import android.view.accessibility.AccessibilityNodeInfo

/** Runs only against the isolated emulator, using the APK's synthetic fixtures. */
@Suppress("DEPRECATION")
class ActivitySmokeTest:ActivityInstrumentationTestCase2<MainActivity>(MainActivity::class.java) {
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
        waitFor("中行短信账本")
        tap("更多");tap("打开合成演示")
        waitFor("中行短信账本 · 演示")
        waitFor("16 条",false)
        tap("更多");tap("切回手机账本")
        waitFor("中行短信账本")
        tap("更多");tap("打开合成演示")
        waitFor("16 条",false)
        // Reopening the fixture ledger remains idempotent and does not ask for SMS permission.
        assertNotNull(node("合成样例与手机账本分库保存") ?: node("合成演示已打开",false))
    }
}
