package local.boc.ledger
import android.test.ActivityInstrumentationTestCase2
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.DatePicker
import android.view.KeyEvent
import java.time.LocalDate
import java.time.ZoneId
import java.math.BigDecimal
@Suppress("DEPRECATION")
class FiltersDateTest:ActivityInstrumentationTestCase2<MainActivity>(MainActivity::class.java) {
 private fun field(name:String)=MainActivity::class.java.getDeclaredField(name).apply {isAccessible=true}
 private fun idle(){instrumentation.waitForIdleSync();SystemClock.sleep(250)}
 private fun sheets()=LedgerSheet::class.java.getDeclaredField("open").apply {isAccessible=true}.get(null) as List<LedgerSheet>
 private fun click(label:String,dialog:Boolean=false) {var target:View?=null;instrumentation.runOnMainSync {
  fun walk(v:View){if(target!=null)return;if((v as? TextView)?.text?.toString()==label || v.contentDescription?.toString()==label){var p:View?=v;while(p!=null&&!p.isClickable)p=p.parent as? View;if(p!=null){target=p;return}};if(v is ViewGroup)for(i in 0 until v.childCount)walk(v.getChildAt(i))}
  walk(if(dialog)sheets().last().window!!.decorView else activity.window.decorView);target?.performClick()
 };assertNotNull("Click $label",target);idle()}
 private fun call(name:String,vararg args:Any){instrumentation.runOnMainSync{val m=MainActivity::class.java.declaredMethods.first{it.name==name&&it.parameterTypes.size==args.size};m.isAccessible=true;m.invoke(activity,*args)};idle()}
 private fun back(){instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);idle()}
 private fun shown()=field("shown").get(activity) as List<StoredSms>
 private fun capture(name:String){idle();SystemClock.sleep(350);val image=instrumentation.uiAutomation.takeScreenshot();java.io.File(activity.getExternalFilesDir(null),"v18-$name.png").outputStream().use {image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}
 private fun time(date:LocalDate)=date.atTime(12,0).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
 fun testSmsYearProjectionPreservesManualAndLinkedEvidence() {
  val context=instrumentation.targetContext;context.deleteDatabase("v18-dates.db")
  LedgerDb(context,"v18-dates.db").use {db->
   db.ingest("date","95566","您的借记卡账户8312，于12月31日网上支付支取人民币12.00元,交易后余额100.00【中国银行】",time(LocalDate.of(2024,1,1)),"synthetic-v18")
   val first=db.all().single();assertEquals(2023,first.effective!!.date!!.year);assertNull(first.parsed.event!!.date!!.year);assertTrue(first.dateInferred)
   db.classify(setOf(first.id),"餐饮");db.reparse();assertEquals("餐饮",db.labels()[first.id]);assertEquals(2023,db.all().single().effective!!.date!!.year)
   db.correct(first.id,Category.SPENDING,first.effective.copy(date=BodyDate(null,12,31)));db.reparse();assertNull(db.all().single().effective!!.date!!.year);assertFalse(db.all().single().dateInferred)
   db.ingest("buy","95566","您的信用卡8132于2024年01月02日，在SYNTHETIC消费USD10.00元。【中国银行】",time(LocalDate.of(2024,1,2)),"synthetic-v18")
   db.ingest("refund","95566","您的信用卡8132于2024年01月03日成功退货USD10.00元。【中国银行】",time(LocalDate.of(2024,1,3)),"synthetic-v18")
   val rows=db.all();db.link(rows.first{it.effective!!.kind==Kind.CREDIT_REFUND}.id,rows.first{it.effective!!.kind==Kind.CREDIT_PURCHASE}.id);val before=db.links();db.reparse();assertEquals(before,db.links())
  };context.deleteDatabase("v18-dates.db")
 }
 fun testInferredYearAndFinalVisuals() {
  val ctx=instrumentation.targetContext
  LedgerDb(ctx,"demo.db").use {it.ingest("v18-year-proof","95566","您的借记卡账户8312，于12月31日网上支付支取人民币12.00元,交易后余额100.00【中国银行】",time(LocalDate.of(2024,1,1)),"synthetic-v18-year",true)}
  activity;click("更多");click("打开合成演示",true);while(field("busy").getBoolean(activity))SystemClock.sleep(100);idle()
  click("流水");click("全部账户");click("借记卡 · 8312",true);assertEquals(2023,shown().single().effective!!.date!!.year);capture("inferred-year")
  click("信用卡");click("全部信用卡");click("信用卡 · 8132",true);capture("credit");call("openCreditLedger");capture("cycle-ledger");click("全部币种");capture("currency-sheet");click("HKD",true);capture("filtered")
 }
 fun testVisibleFiltersCycleAndReturn() {
  val ctx=instrumentation.targetContext;ctx.getSharedPreferences("billing-demo",0).edit().putInt("statement_day",22).putInt("repayment_day",12).putInt("repayment_month",1).commit()
  val cycle=RepaymentDate.upcomingCycle(LocalDate.now(),22,1,12);val day=cycle.end.minusDays(3)
  LedgerDb(ctx,"demo.db").use {db->
   db.writableDatabase.execSQL("DELETE FROM expense_labels WHERE sms_id IN (SELECT id FROM sms WHERE sim='synthetic-v18')");db.writableDatabase.execSQL("DELETE FROM audit WHERE sms_id IN (SELECT id FROM sms WHERE sim='synthetic-v18')");db.writableDatabase.execSQL("DELETE FROM sms WHERE sim='synthetic-v18'")
   val records=listOf(Triple("8132","USD10.00",cycle.end.minusDays(4)),Triple("8132","HKD156.00",day),Triple("8132","CNY280.00",cycle.end.minusDays(2)),Triple("8132","USD44.00",cycle.start.minusDays(1)),Triple("6284","USD55.00",day),Triple("8132","USD123456789.12",cycle.end.minusDays(1)))
   records.forEachIndexed{i,(card,amount,d)->db.ingest("v18-$i","95566","您的信用卡${card}于${d.year}年%02d月%02d日，在%s消费%s元。【中国银行】".format(d.monthValue,d.dayOfMonth,if(i==5)"SYNTHETIC-LONG-MERCHANT-NAME" else "MOSS-$i",amount),System.currentTimeMillis()+i,"synthetic-v18",true)}
   db.all().filter{it.sim=="synthetic-v18"}.forEach{db.classify(setOf(it.id),if(it.effective!!.money.currency=="CNY")"餐饮" else "购物")}
  }
  FxCache(ctx).use{c->c.save(QuoteKey("HKD","USD",day),CachedRate(FxQuote("HKD","USD",day,BigDecimal("0.128"),"合成测试报价",System.currentTimeMillis()),System.currentTimeMillis()+3600000,0,null))}
  activity;click("更多");click("打开合成演示",true);while(field("busy").getBoolean(activity))SystemClock.sleep(100);idle()
  click("信用卡");click("全部信用卡");click("信用卡 · 8132",true);capture("credit")
  call("openCreditLedger");assertEquals(cycle.start,field("from").get(activity));assertEquals(cycle.end,field("to").get(activity));assertEquals(4,shown().size);assertTrue(shown().all{it.effective!!.tail=="8132"});capture("cycle-ledger")
  click("下一账期");assertEquals(0,shown().size);back();assertEquals(1,field("cycleOffset").getInt(activity));click("上一账期");call("openCreditLedger")
  click("全部币种");capture("currency-sheet");click("HKD",true);assertEquals(1,shown().size);assertEquals("HKD",shown().single().effective!!.money.currency);capture("filtered")
  click("全部分类");click("餐饮",true);assertEquals(0,shown().size);capture("empty-filter")
  click("餐饮");click("购物",true);assertEquals(1,shown().size)
  val id=shown().single().id;call("detail",id);back();assertEquals("HKD",field("selectedCurrency").get(activity));assertEquals("购物",field("selectedCategory").get(activity));assertEquals(1,shown().size)
  click("清除筛选");assertNull(field("selectedCurrency").get(activity));assertNull(field("selectedAccount").get(activity));assertNull(field("selectedCategory").get(activity));assertNull(field("from").get(activity));assertFalse(field("ledgerUsesCycle").getBoolean(activity));assertTrue(shown().any{it.effective!!.tail=="6284"})
  click("全部账户");click("信用卡 · 8132",true);assertEquals(5,shown().size)
  call("filterRangeDialog")
  fun setDate(label:String,d:LocalDate){click(label,true);instrumentation.runOnMainSync{fun find(v:View):DatePicker?{if(v is DatePicker)return v;if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i))?.let{return it};return null};find(sheets().last().window!!.decorView)!!.updateDate(d.year,d.monthValue-1,d.dayOfMonth)};click("确定",true)}
  setDate("起始：${LocalDate.now().minusMonths(1)}",day);setDate("结束：${LocalDate.now()}",day);click("应用",true);assertEquals(1,shown().size);assertEquals("HKD",shown().single().effective!!.money.currency);capture("date-filter")
  click("清除筛选");click("全部账户");click("信用卡 · 8132",true)
  val list=field("list").get(activity) as android.widget.ListView;instrumentation.runOnMainSync{list.setSelectionFromTop(2,0)};capture("long-amount")
  back();assertEquals("credit",field("page").get(activity));assertFalse(activity.isFinishing)
 }
}
