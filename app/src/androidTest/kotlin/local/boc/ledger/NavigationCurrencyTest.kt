package local.boc.ledger
import android.test.ActivityInstrumentationTestCase2
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.view.KeyEvent
import java.time.LocalDate
import java.math.BigDecimal
@Suppress("DEPRECATION")
class NavigationCurrencyTest:ActivityInstrumentationTestCase2<MainActivity>(MainActivity::class.java) {
 private fun field(name:String)=MainActivity::class.java.getDeclaredField(name).apply {isAccessible=true}
 private fun click(label:String) {
  var target:View?=null
  instrumentation.runOnMainSync {
   fun walk(v:View) {if(!v.isShown)return;if((v as? TextView)?.text?.toString()==label || v.contentDescription?.toString()==label) {var p:View?=v;while(p!=null&&!p.isClickable)p=p.parent as? View;if(target==null)target=p};if(v is ViewGroup)for(i in 0 until v.childCount)walk(v.getChildAt(i))};walk(activity.window.decorView);target?.performClick()
  }
  assertNotNull("Clickable $label",target);idle()
 }
 private fun idle(){instrumentation.waitForIdleSync();SystemClock.sleep(300)}
 private fun back(){instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);idle();val sheets=LedgerSheet::class.java.getDeclaredField("open").apply {isAccessible=true}.get(null) as List<*>;println("BACK page="+field("page").get(activity)+" sheets="+sheets.size)}
 private fun call(name:String,vararg args:Any) {instrumentation.runOnMainSync {val method=MainActivity::class.java.declaredMethods.first {it.name==name && it.parameterTypes.size==args.size};method.isAccessible=true;method.invoke(activity,*args)};idle()}
 private fun texts():String {val result=StringBuilder();instrumentation.runOnMainSync {fun walk(v:View){if(v is TextView)result.append(v.text).append('\n');if(v is ViewGroup)for(i in 0 until v.childCount)walk(v.getChildAt(i))};walk(activity.window.decorView)};return result.toString()}
 private fun capture(name:String){idle();SystemClock.sleep(500);val image=instrumentation.uiAutomation.takeScreenshot();java.io.File(activity.getExternalFilesDir(null),"v17-$name.png").outputStream().use {image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}
 fun testNavigationCurrencyAndSheets() {
  val context=instrumentation.targetContext;val today=LocalDate.now();val day=today.minusDays(5)
  context.getSharedPreferences("billing-demo",0).edit().putInt("statement_day",today.dayOfMonth).putInt("repayment_day",today.plusDays(1).dayOfMonth).putInt("repayment_month",if(today.plusDays(1).month==today.month)0 else 1).commit()
  val before=LedgerDb(context,"demo.db").use {db->db.labels().size}
  LedgerDb(context,"demo.db").use {db->
   db.writableDatabase.execSQL("DELETE FROM expense_labels WHERE sms_id IN (SELECT id FROM sms WHERE sim='synthetic-v17')")
   db.writableDatabase.execSQL("DELETE FROM links WHERE adjustment IN (SELECT id FROM sms WHERE sim='synthetic-v17') OR purchase IN (SELECT id FROM sms WHERE sim='synthetic-v17')")
   db.writableDatabase.execSQL("DELETE FROM audit WHERE sms_id IN (SELECT id FROM sms WHERE sim='synthetic-v17')")
   db.writableDatabase.execSQL("DELETE FROM sms WHERE sim='synthetic-v17'")
   listOf("CNY 280.00","HKD 156.00","USD 18.50").forEachIndexed {i,pair->val (currency,amount)=pair.split(' ');val body="您的信用卡7328于${day.year}年%02d月%02d日，在%s消费%s%s元。【中国银行】".format(day.monthValue,day.dayOfMonth,listOf("MOSS-STORE","CLOUD-MUSIC","BLUE-BOOK")[i],currency,amount);db.ingest("v17-$i","95566",body,System.currentTimeMillis()+i,"synthetic-v17",true)}
   assertTrue("Preserved v16 manual categories",before>0);assertTrue(db.labels().size>=before)
  }
  FxCache(context).use {cache->for(key in listOf(QuoteKey("HKD","USD",day),QuoteKey("USD","CNY",null)))cache.save(key,CachedRate(FxQuote(key.base,key.quote,key.date?:today,BigDecimal(if(key.base=="HKD")"0.128" else "7.20"),"合成测试报价",System.currentTimeMillis()),System.currentTimeMillis()+3600000,0,null))}
  activity
  // Open synthetic ledger through the same public menu entry, no phone access.
  click("更多")
  fun dialogClick(label:String) {
   var found=false
   instrumentation.runOnMainSync {
    val sheets=LedgerSheet::class.java.getDeclaredField("open").apply {isAccessible=true}.get(null) as List<*>
    fun walk(v:View) {if((v as? TextView)?.text?.toString()==label && v.isClickable) {v.performClick();found=true;return};if(v is ViewGroup)for(i in 0 until v.childCount)walk(v.getChildAt(i))}
    walk((sheets.last() as LedgerSheet).window!!.decorView)
   };assertTrue("Sheet item $label",found);idle()
  }
  dialogClick("打开合成演示");while(field("busy").getBoolean(activity)) SystemClock.sleep(100);idle();capture("home")
  click("信用卡");SystemClock.sleep(1000);assertTrue(texts().contains("人民币消费"));assertTrue(texts().contains("CNY 280.00"));capture("credit")
  // Secondary -> primary, then primary -> home. Save ledger filter and scroll.
  call("navigate","debit",false);instrumentation.runOnMainSync {field("accountKind").set(activity,AccountKind.CREDIT)};call("reload");SystemClock.sleep(600)
  assertTrue(texts().contains("人民币部分"));capture("credit-ledger")
  val list=field("list").get(activity) as android.widget.ListView
  instrumentation.runOnMainSync {list.setSelectionFromTop(2,0)};idle();SystemClock.sleep(600);assertTrue(texts().contains("HKD 156.00"));assertTrue(texts().contains("≈ US$ 19.97"));capture("foreign-ledger")
  back();assertEquals("credit",field("page").get(activity));back();assertEquals("home",field("page").get(activity));assertFalse(activity.isFinishing)
  click("流水");instrumentation.runOnMainSync {field("query").set(activity,"7304")};call("render");click("统计");capture("stats");click("流水");assertEquals("7304",field("query").get(activity))
  call("searchDialog");capture("search-sheet");
  val activeSheet=LedgerSheet::class.java.getDeclaredField("open").apply {isAccessible=true}.get(null) as List<*>
  val sheet=activeSheet.last() as LedgerSheet
  instrumentation.runOnMainSync {fun edit(v:View):android.widget.EditText? {if(v is android.widget.EditText)return v;if(v is ViewGroup)for(i in 0 until v.childCount)edit(v.getChildAt(i))?.let{return it};return null};val input=edit(sheet.window!!.decorView)!!;input.requestFocus();(activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(input,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)}
  SystemClock.sleep(1500);capture("keyboard-sheet");back();assertTrue("Back dismisses keyboard before sheet",sheet.isShowing);back();assertEquals("debit",field("page").get(activity));assertFalse(activity.isFinishing)
  click("设置");capture("settings");call("dateSettings");dialogClick("设置还款日");capture("date-sheet");back();assertFalse(activity.isFinishing);back();assertEquals("settings",field("page").get(activity))
  val db=field("db").get(activity) as LedgerDb;val chosen=db.all().first {it.sim=="synthetic-v17"}.id
  call("categoryDialog",setOf(chosen));capture("category-sheet");back();assertEquals("settings",field("page").get(activity))
  call("detail",chosen);capture("detail-sheet");dialogClick("修正字段 / 分类");capture("edit-sheet");back();dialogClick("关闭");back();assertEquals("home",field("page").get(activity));back();assertFalse(activity.isFinishing);back();assertTrue(activity.isFinishing)
 }
}
