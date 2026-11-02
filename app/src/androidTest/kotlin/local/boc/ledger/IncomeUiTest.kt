package local.boc.ledger
import android.test.ActivityInstrumentationTestCase2
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
@Suppress("DEPRECATION")
class IncomeUiTest:ActivityInstrumentationTestCase2<MainActivity>(MainActivity::class.java) {
    private fun field(name:String)=MainActivity::class.java.getDeclaredField(name).apply{isAccessible=true}
    private fun idle(){instrumentation.waitForIdleSync();SystemClock.sleep(250)}
    private fun sheets()=LedgerSheet::class.java.getDeclaredField("open").apply{isAccessible=true}.get(null) as List<LedgerSheet>
    private fun click(label:String,dialog:Boolean=false){var target:View?=null;instrumentation.runOnMainSync{
        fun walk(v:View){if(target!=null)return;if((v as? TextView)?.text?.toString()==label||v.contentDescription?.toString()==label){var p:View?=v;while(p!=null&&!p.isClickable)p=p.parent as? View;target=p};if(v is ViewGroup)for(i in 0 until v.childCount)walk(v.getChildAt(i))}
        walk(if(dialog)sheets().last().window!!.decorView else activity.window.decorView);target?.performClick()
    };assertNotNull(label,target);idle()}
    private fun call(name:String,vararg args:Any){instrumentation.runOnMainSync{val m=MainActivity::class.java.declaredMethods.first{it.name==name&&it.parameterTypes.size==args.size};m.isAccessible=true;m.invoke(activity,*args)};idle()}
    private fun capture(name:String){idle();instrumentation.uiAutomation.takeScreenshot().let{b->java.io.File(activity.getExternalFilesDir(null),"v111-$name.png").outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}}
    private fun chartText():String {var text="";instrumentation.runOnMainSync{fun walk(v:View){if(v is SpendingChart)text+=v.contentDescription;if(v is ViewGroup)for(i in 0 until v.childCount)walk(v.getChildAt(i))};walk(activity.window.decorView)};return text}
    fun testDirectIncomeClassificationAndStatistics() {
        val ctx=instrumentation.targetContext;var ids=emptySet<Long>()
        LedgerDb(ctx,"demo.db").use{db->
            for((i,amount) in listOf("720.00","180.00").withIndex())db.ingest("v111-income-$i","95566","您的借记卡账户8428，于08月17日收入(公积金)(提取托付)人民币${amount}元，交易后余额3000.00【中国银行】",1723896000000+i,"synthetic-v111",true)
            ids=db.all().filter{it.sim=="synthetic-v111"}.map{it.id}.toSet();db.classify(ids,"待分类")
        }
        activity;click("更多");click("打开合成演示",true);while(field("busy").getBoolean(activity))SystemClock.sleep(100)
        click("流水");instrumentation.runOnMainSync{field("selectedAccount").set(activity,"借记卡 · 8428")};call("render")
        instrumentation.runOnMainSync{(field("list").get(activity) as android.widget.ListView).setSelectionFromTop(1,0)};idle()
        click("待分类");capture("category");click("公积金",true);assertTrue(sheets().isEmpty())
        LedgerDb(ctx,"demo.db").use{assertEquals(1,it.labels().filterKeys{it in ids}.values.count{it=="公积金"})}
        call("categoryDialog",ids);click("工资",true);click("保存",true)
        LedgerDb(ctx,"demo.db").use{assertTrue(ids.all{n->it.labels()[n]=="工资"})}
        click("统计");instrumentation.runOnMainSync{field("statsAccount").set(activity,"借记卡 · 8428")};call("renderDashboard");click("收入统计")
        assertTrue(chartText().contains("工资 900.00"));capture("income-stats")
        click("支出统计");assertFalse(chartText().contains("工资"));click("收入统计")
        instrumentation.runOnMainSync{(field("list").get(activity) as android.widget.ListView).smoothScrollBy(420,1)};idle();capture("income-trend")
    }
}
