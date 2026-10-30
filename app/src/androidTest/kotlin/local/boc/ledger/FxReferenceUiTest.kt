package local.boc.ledger

import android.test.ActivityInstrumentationTestCase2
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.math.BigDecimal
import java.time.LocalDate

@Suppress("DEPRECATION")
class FxReferenceUiTest:ActivityInstrumentationTestCase2<MainActivity>(MainActivity::class.java) {
    private fun field(name:String)=MainActivity::class.java.getDeclaredField(name).apply {isAccessible=true}
    private fun idle(){instrumentation.waitForIdleSync();SystemClock.sleep(250)}
    private fun sheets()=LedgerSheet::class.java.getDeclaredField("open").apply {isAccessible=true}.get(null) as List<LedgerSheet>
    private fun click(label:String,dialog:Boolean=false) {
        var target:View?=null
        instrumentation.runOnMainSync {
            fun walk(v:View){if(target!=null)return;if((v as? TextView)?.text?.toString()==label || v.contentDescription?.toString()==label){var p:View?=v;while(p!=null&&!p.isClickable)p=p.parent as? View;target=p};if(v is ViewGroup)for(i in 0 until v.childCount)walk(v.getChildAt(i))}
            walk(if(dialog)sheets().last().window!!.decorView else activity.window.decorView);target?.performClick()
        };assertNotNull(label,target);idle()
    }
    private fun capture(name:String){idle();instrumentation.uiAutomation.takeScreenshot().let {image->java.io.File(activity.getExternalFilesDir(null),"v19-$name.png").outputStream().use {image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}}
    fun testReferenceAmountAndHolidayDateVisible() {
        val ctx=instrumentation.targetContext;val date=LocalDate.of(2024,2,18)
        LedgerDb(ctx,"demo.db").use {db->
            for((i,amount) in listOf("JPY10000","USD20","CNY300").withIndex())
                db.ingest("v19-fx-$i","95566","您的信用卡9028于2024年02月18日，在FX-SAMPLE消费${amount}元。【中国银行】",1708257600000L+i,"synthetic-v19",true)
        }
        FxCache(ctx).use {it.save(QuoteKey("JPY","USD",date),CachedRate(FxQuote("JPY","USD",date.minusDays(2),BigDecimal("0.00625"),"合成测试报价",System.currentTimeMillis()),System.currentTimeMillis()+3600000,0,null))}
        activity;click("更多");click("打开合成演示",true)
        while(field("busy").getBoolean(activity))SystemClock.sleep(100)
        click("流水")
        instrumentation.runOnMainSync {
            field("accountKind").set(activity,AccountKind.CREDIT)
            field("selectedAccount").set(activity,"信用卡 · 9028")
            MainActivity::class.java.getDeclaredMethod("render").apply {isAccessible=true}.invoke(activity)
        };idle()
        instrumentation.runOnMainSync {(field("list").get(activity) as android.widget.ListView).setSelectionFromTop(1,0)}
        idle()
        SystemClock.sleep(500)
        capture("reference")
        click("≈ US$ 62.50 · 参考 02.16")
        capture("source")
        var text=""
        instrumentation.runOnMainSync {fun walk(v:View){if(v is TextView)text+=v.text.toString()+"\n";if(v is ViewGroup)for(i in 0 until v.childCount)walk(v.getChildAt(i))};walk(sheets().last().window!!.decorView)}
        assertTrue(text.contains("JPY/USD 0.00625"));assertTrue(text.contains("有效日 2024-02-16"));assertTrue(text.contains("合成测试报价"))
        click("关闭",true)
    }
}
