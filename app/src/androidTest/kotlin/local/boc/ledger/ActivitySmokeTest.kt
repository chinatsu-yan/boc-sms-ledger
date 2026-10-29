package local.boc.ledger
import android.os.SystemClock
import android.test.ActivityInstrumentationTestCase2
import android.view.accessibility.AccessibilityNodeInfo
import java.time.LocalDate
@Suppress("DEPRECATION")
class ActivitySmokeTest:ActivityInstrumentationTestCase2<MainActivity>(MainActivity::class.java) {
 override fun setUp(){super.setUp();instrumentation.targetContext.getSharedPreferences("billing-demo",0).edit().putInt("statement_day",22).putInt("repayment_day",12).putInt("repayment_month",1).commit()}
 private fun find(label:String,exact:Boolean=true,click:Boolean=false):AccessibilityNodeInfo? {
  fun walk(n:AccessibilityNodeInfo):AccessibilityNodeInfo? {
   val t=n.text?.toString().orEmpty();val d=n.contentDescription?.toString().orEmpty()
   if((if(exact)t==label||d==label else t.contains(label)||d.contains(label)) && (!click||n.isClickable)) return n
   for(i in 0 until n.childCount){val c=n.getChild(i)?:continue;walk(c)?.let {return it}};return null
  };return instrumentation.uiAutomation.rootInActiveWindow?.let(::walk)
 }
 private fun waitFor(s:String,exact:Boolean=true):AccessibilityNodeInfo {val end=SystemClock.uptimeMillis()+15000;while(SystemClock.uptimeMillis()<end){find(s,exact)?.let{return it};SystemClock.sleep(200)};error("UI missing: $s")}
 private fun tap(s:String,exact:Boolean=true) {
  println("TAP: $s");var target:android.view.View?=null
  instrumentation.runOnMainSync {
   fun walk(v:android.view.View) {
    if(!v.isShown) return
    val label=(v as? android.widget.TextView)?.text?.toString().orEmpty()
    if(if(exact)label==s||v.contentDescription?.toString()==s else label.contains(s)||v.contentDescription?.toString()?.contains(s)==true) {
     var p:android.view.View?=v;while(p!=null&&!p.isClickable) p=p.parent as? android.view.View
     if(p!=null && target==null) target=p
    }
    if(v is android.view.ViewGroup) for(i in 0 until v.childCount) walk(v.getChildAt(i))
   };walk(activity.window.decorView);target?.performClick()
  }
  if(target==null) {val n=waitFor(s,exact);val rect=android.graphics.Rect();n.getBoundsInScreen(rect);val now=SystemClock.uptimeMillis();instrumentation.sendPointerSync(android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_DOWN,rect.exactCenterX(),rect.exactCenterY(),0));instrumentation.sendPointerSync(android.view.MotionEvent.obtain(now,now+60,android.view.MotionEvent.ACTION_UP,rect.exactCenterX(),rect.exactCenterY(),0))}
  instrumentation.waitForIdleSync();SystemClock.sleep(350)
 }
 private fun top(){instrumentation.runOnMainSync {val f=MainActivity::class.java.getDeclaredField("list");f.isAccessible=true;(f.get(activity) as android.widget.ListView).setSelectionFromTop(0,0)};SystemClock.sleep(300)}
 private fun capture(n:String){if(n!="keyboard" && !n.endsWith("scroll"))top();instrumentation.waitForIdleSync();SystemClock.sleep(500);val bitmap=instrumentation.uiAutomation.takeScreenshot();assertNotNull("screenshot",bitmap);java.io.File(activity.getExternalFilesDir(null),"v16-$n.png").outputStream().use {bitmap!!.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}
 fun testSixScreensClassificationAndAssociation() {
  val cycle=RepaymentDate.upcomingCycle(LocalDate.now(),22,1,12);fun date(days:Long)=cycle.end.minusDays(days).let {"${it.year}年%02d月%02d日".format(it.monthValue,it.dayOfMonth)}
  LedgerDb(instrumentation.targetContext,"demo.db").use {db->
   db.writableDatabase.execSQL("DELETE FROM expense_labels WHERE sms_id IN (SELECT id FROM sms WHERE sim='synthetic-v16')")
   db.writableDatabase.execSQL("DELETE FROM links WHERE adjustment IN (SELECT id FROM sms WHERE sim='synthetic-v16') OR purchase IN (SELECT id FROM sms WHERE sim='synthetic-v16')")
   db.writableDatabase.execSQL("DELETE FROM audit WHERE sms_id IN (SELECT id FROM sms WHERE sim='synthetic-v16')")
   db.writableDatabase.execSQL("DELETE FROM sms WHERE sim='synthetic-v16'")
   val msgs=listOf(
    "您的借记卡账户7304，于06月25日网上支付支取人民币900.00元,交易后余额18764.52【中国银行】",
    "您的借记卡账户7304，于06月24日网上支付支取人民币600.00元,交易后余额19664.52【中国银行】",
    "您的借记卡账户7304，于06月23日网上支付支取人民币360.00元,交易后余额20264.52【中国银行】",
    "您的借记卡账户7304，于06月22日网上支付支取人民币300.00元,交易后余额20624.52【中国银行】",
    "您的借记卡账户7304，于06月21日网上支付支取人民币240.00元,交易后余额20924.52【中国银行】",
    "您的信用卡4826于${date(16)}，在PINE_ISLE消费USD49.00元。【中国银行】",
    "您的信用卡4826于${date(8)}，在PINE_ISLE消费USD49.00元。【中国银行】",
    "您的信用卡4826于${date(2)}，在PINE_ISLE撤销USD49.00元。【中国银行】",
    "您的信用卡4826于${date(1)}成功退货USD49.00元。【中国银行】")
   msgs.forEachIndexed {i,b->db.ingest("v16-$i","95566",b,System.currentTimeMillis()-i*86400000L,"synthetic-v16",true)}
   val debit=db.all().filter {it.sim=="synthetic-v16"&&it.effective!!.accountKind==AccountKind.DEBIT}.sortedBy {it.id}
   listOf("餐饮","购物","交通","日用").forEachIndexed {i,n->db.classify(setOf(debit[i].id),n)}
   db.saveRule(MerchantRule("PINE_ISLE","松岛商店","购物"),true)
  }
  activity;waitFor("概览");tap("更多");tap("打开合成演示");waitFor("● 合成演示 · 本地账本");capture("home")
  tap("流水");capture("ledger");tap("批量");tap("取消");assertNull(find("已选",false))
  tap("餐饮⌄");tap("购物");tap("取消")
  val firstId=LedgerDb(activity,"demo.db").use {db->val id=db.all().filter {it.sim=="synthetic-v16" && it.effective!!.accountKind==AccountKind.DEBIT}.minOf {it.id};assertEquals("餐饮",db.labels()[id]);id}
  top();tap("餐饮⌄");tap("购物");tap("保存")
  tap("批量");tap("选择流水 $firstId");tap("已选 1 笔 · 选择分类");tap("餐饮");tap("保存")
  LedgerDb(activity,"demo.db").use {db->assertEquals("餐饮",db.labels()[firstId])}
  tap("统计");waitFor("消费净额 · 原币 ⓘ");capture("stats")
  repeat(2) {val now=SystemClock.uptimeMillis();val x=activity.resources.displayMetrics.widthPixels/2f;val h=activity.resources.displayMetrics.heightPixels.toFloat();instrumentation.sendPointerSync(android.view.MotionEvent.obtain(now,now,0,x,h-140,0));for(i in 1..8) instrumentation.sendPointerSync(android.view.MotionEvent.obtain(now,now+i*35L,2,x,(h-140)+(h*.25f-(h-140))*i/8,0));instrumentation.sendPointerSync(android.view.MotionEvent.obtain(now,now+300,1,x,h*.25f,0));SystemClock.sleep(500)}
  waitFor("时间趋势");capture("stats-scroll")
  tap("信用卡");waitFor("撤销 / 退货待关联");capture("credit")
  // Open the pending collection and the merchant-less same-amount refund.
  tap("笔 →",false);tap("USD 49.00",false);waitFor("核对关联");capture("match");tap("确认关联")
  LedgerDb(activity,"demo.db").use {db->val a=db.all().first {it.sim=="synthetic-v16" && it.effective!!.kind==Kind.CREDIT_REFUND};val l=db.links().first {it.adjustmentId==a.id};assertTrue(db.history(a.id).any {it.contains("确认关联")});db.reparse();assertTrue(db.links().contains(l));db.unlink(a.id);db.autoAssociate();assertFalse(db.links().any {it.adjustmentId==a.id})}
  LedgerDb(activity,"demo.db").use {db->assertTrue(db.links().isNotEmpty());assertTrue(db.labels().values.contains("购物"))}
  tap("设置");waitFor("分类与规则");capture("settings")
  tap("＋ 自定义")
  fun input(n:AccessibilityNodeInfo):AccessibilityNodeInfo? {if(n.className?.toString()?.endsWith("EditText")==true)return n;for(i in 0 until n.childCount) n.getChild(i)?.let {c->input(c)?.let{return it}};return null}
  val field=input(instrumentation.uiAutomation.rootInActiveWindow)!!;val bounds=android.graphics.Rect();field.getBoundsInScreen(bounds);val time=SystemClock.uptimeMillis();instrumentation.sendPointerSync(android.view.MotionEvent.obtain(time,time,0,bounds.exactCenterX(),bounds.exactCenterY(),0));instrumentation.sendPointerSync(android.view.MotionEvent.obtain(time,time+60,1,bounds.exactCenterX(),bounds.exactCenterY(),0));instrumentation.sendStringSync("Travel");SystemClock.sleep(700);capture("keyboard");tap("取消")
  LedgerDb(activity,"demo.db").use {db->assertFalse(db.categories().contains("Travel"))}
  tap("更多");tap("切回手机账本");waitFor("分类与规则");assertEquals(0,activity.getSharedPreferences("billing",0).getInt("repayment_day",0))
 }
}
