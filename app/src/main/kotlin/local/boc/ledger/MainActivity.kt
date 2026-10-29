package local.boc.ledger

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.content.pm.PackageManager
import android.os.Build
import android.view.WindowInsets
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONObject
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity:Activity() {
    private val worker=Executors.newSingleThreadExecutor()
    private val cancelled=AtomicBoolean(false)
    private lateinit var db:LedgerDb
    private lateinit var status:TextView
    private lateinit var summary:TextView
    private lateinit var list:ListView
    private lateinit var accountFilter:Spinner
    private lateinit var currencyFilter:Spinner
    private lateinit var scanButton:Button
    private lateinit var billingBox:LinearLayout
    private lateinit var billingText:TextView
    private lateinit var billingDayButton:Button
    private var page="home"
    private var homeAll=true
    private lateinit var dashboard:LinearLayout
    private lateinit var ledgerControls:LinearLayout
    private lateinit var pageTitle:TextView
    private lateinit var navigation:LinearLayout
    private lateinit var eyebrow:TextView
    private var accountKind=AccountKind.CREDIT
    private var cycleOffset=0
    @Volatile private var foreground=false
    private var observing=false
    private var rescanRequested=false
    private val mainHandler=Handler(Looper.getMainLooper())
    private val autoScan=Runnable {startAutomaticScan()}
    private val smsObserver=object:ContentObserver(mainHandler) {override fun onChange(selfChange:Boolean) {requestAutomaticScan(2000L)}}
    private val fxWorker=Executors.newSingleThreadExecutor()
    private val fxEpoch=AtomicInteger(0)
    private var fxTask:Future<*>?=null
    private var lastFxRequest:String?=null
    private var lastEstimate:BillingEstimate?=null
    private lateinit var rangeButton:Button
    private var rows=emptyList<StoredSms>()
    private var links=emptyList<Link>()
    private var shown=emptyList<StoredSms>()
    private var demo=false
    private var busy=false
    private var from:LocalDate?=null
    private var to:LocalDate?=null
    private var refreshing=false
    private val zone get()=ZoneId.systemDefault()
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        demo=savedInstanceState?.getBoolean("demo") ?: false
        accountKind=savedInstanceState?.getString("account_kind")?.let(AccountKind::valueOf) ?: AccountKind.CREDIT
        cycleOffset=savedInstanceState?.getInt("cycle_offset") ?: 0
        from=savedInstanceState?.getString("from")?.let(LocalDate::parse)
        to=savedInstanceState?.getString("to")?.let(LocalDate::parse)
        db=LedgerDb(this,if(demo) "demo.db" else "ledger.db")
        page=savedInstanceState?.getString("page") ?: "home"
        homeAll=savedInstanceState?.getBoolean("home_all") ?: true
        buildUi();reload()
    }
    override fun onSaveInstanceState(outState:Bundle) {
        super.onSaveInstanceState(outState);outState.putString("page",page);outState.putBoolean("home_all",homeAll);outState.putBoolean("demo",demo);outState.putString("from",from?.toString());outState.putString("to",to?.toString())
        outState.putString("account_kind",accountKind.name);outState.putInt("cycle_offset",cycleOffset)
    }
    override fun onResume() {
        super.onResume();foreground=true
        observeSms();requestAutomaticScan();refreshCreditEstimate()
    }
    override fun onPause() {
        foreground=false;mainHandler.removeCallbacks(autoScan)
        if(observing) {contentResolver.unregisterContentObserver(smsObserver);observing=false}
        fxEpoch.incrementAndGet();fxTask?.cancel(true);lastFxRequest=null
        super.onPause()
    }
    override fun onDestroy() {
        cancelled.set(true);mainHandler.removeCallbacksAndMessages(null);worker.shutdownNow();fxWorker.shutdownNow()
        if(!busy) db.close();super.onDestroy()
    }
    private fun observeSms() {
        if(!demo && foreground && SmsImport.hasRead(this) && !observing) try {
            contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI,true,smsObserver);observing=true
        } catch(_:SecurityException) {updateStatus()}
    }
    private fun requestAutomaticScan(delay:Long=0L) {
        if(!foreground || demo || !SmsImport.prepareAutomatic(this)) {if(::status.isInitialized) updateStatus();return}
        if(busy) {rescanRequested=true;return}
        mainHandler.removeCallbacks(autoScan);mainHandler.postDelayed(autoScan,delay)
    }
    private fun startAutomaticScan() {
        if(!foreground || demo || !SmsImport.prepareAutomatic(this)) return
        if(busy) {rescanRequested=true;return}
        rescanRequested=false
        work("自动扫描本机银行短信",{SmsImport.incremental(this,cancelled)}) {"自动扫描完成，新增 $it 条来源记录"}
    }
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun text(value:String,size:Float=14f,bold:Boolean=false)=TextView(this).apply { text=value;textSize=size;setTextColor(Color.rgb(38,56,68));if(bold)setTypeface(null,Typeface.BOLD);setPadding(0,dp(4),0,dp(4)) }
    private fun button(label:String,action:()->Unit)=Button(this).apply {
        text=label;textSize=12f;isAllCaps=false;setTextColor(Color.rgb(53,94,103));minHeight=dp(44);minimumHeight=dp(44)
        stateListAnimator=null;elevation=0f;background=rounded(Color.rgb(237,240,236),10)
        setPadding(dp(10),dp(6),dp(10),dp(6));setOnClickListener {if(!busy) action()}
    }
    private fun rounded(color:Int,radius:Int)=android.graphics.drawable.GradientDrawable().apply {setColor(color);cornerRadius=dp(radius).toFloat()}
    private fun muted(value:String,size:Float=12f)=text(value,size).apply {setTextColor(Color.rgb(116,132,139))}
    private fun gap(box:LinearLayout,n:Int=16) {box.addView(View(this),LinearLayout.LayoutParams(1,dp(n)))}
    private fun rule(box:LinearLayout) {box.addView(View(this).apply {setBackgroundColor(Color.rgb(226,230,223))},LinearLayout.LayoutParams(-1,dp(1)))}
    private fun number(value:String,size:Int=36)=text(value,size.toFloat(),false).apply {maxLines=1;setAutoSizeTextTypeUniformWithConfiguration(16,size,1,android.util.TypedValue.COMPLEX_UNIT_SP)}
    private fun link(label:String,action:()->Unit)=text(label,12f).apply {minHeight=dp(40);gravity=android.view.Gravity.CENTER_VERTICAL;setTextColor(Color.rgb(53,94,103));isClickable=true;setOnClickListener {if(!busy) action()}}
    private fun navItem(label:String,kind:Int,target:String)=LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL;gravity=android.view.Gravity.CENTER;setPadding(0,dp(8),0,dp(6));contentDescription=label
        addView(LedgerIcon(this@MainActivity,kind),LinearLayout.LayoutParams(dp(23),dp(23)))
        addView(text(label,10f).apply {gravity=android.view.Gravity.CENTER;setPadding(0,dp(4),0,0)})
        setOnClickListener {navigate(target)}
    }
    private fun column()=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(13),dp(16),dp(13))}
    private fun horizontal(vararg children:View)=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;children.forEachIndexed {i,v->addView(v,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f).apply {if(i<children.lastIndex) rightMargin=dp(6)})} }
    private fun panel(color:Int=Color.rgb(241,239,233))=column().apply {
        background=android.graphics.drawable.GradientDrawable().apply {setColor(color);cornerRadius=dp(18).toFloat()}
        val margin=LinearLayout.LayoutParams(-1,-2);margin.setMargins(0,dp(10),0,dp(10));layoutParams=margin
    }
    private fun navigate(target:String) {
        page=target;batch=false;selectedIds.clear();if(target=="debit") accountKind=AccountKind.DEBIT else if(target in listOf("home","credit")) accountKind=AccountKind.CREDIT
        fxEpoch.incrementAndGet();fxTask?.cancel(true);lastFxRequest=null;lastEstimate=null
        reload();list.post {list.setSelectionFromTop(0,0)}
    }
    private fun buildUi() {
        val root=column().apply {setPadding(dp(24),dp(18),dp(24),dp(20))}
        eyebrow=muted("LOCAL LEDGER",10f).apply {letterSpacing=0.16f};root.addView(eyebrow)
        pageTitle=text("概览",27f,true)
        val heading=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL;addView(pageTitle,LinearLayout.LayoutParams(0,-2,1f));addView(link("⋯") {menu()}.apply {contentDescription="更多";textSize=22f;gravity=android.view.Gravity.CENTER;background=rounded(0xffedf0ee.toInt(),20)},LinearLayout.LayoutParams(dp(44),-2))};root.addView(heading)
        gap(root,16)
        status=text("",12f)
        scanButton=button("授权自动扫描") {if(!SmsImport.hasRead(this)) requestSms() else {SmsImport.enableAutomatic(this);observeSms();requestAutomaticScan()}}

        dashboard=column().apply {setPadding(0,0,0,0)};root.addView(dashboard)
        ledgerControls=column().apply {setPadding(0,0,0,0)};root.addView(ledgerControls)
        accountFilter=Spinner(this);currencyFilter=Spinner(this);ledgerControls.addView(horizontal(accountFilter,currencyFilter))
        billingBox=column().apply {setPadding(0,0,0,0)}
        billingDayButton=button("设置账单日") {configureStatementDay()}
        billingBox.addView(horizontal(button("上一账期") {cycleOffset--;lastFxRequest=null;refreshCreditEstimate();renderDashboard()},billingDayButton,button("下一账期") {cycleOffset++;lastFxRequest=null;refreshCreditEstimate();renderDashboard()}))
        billingText=text("",15f).apply {setOnClickListener {estimateDetails()}}
        billingBox.addView(billingText);ledgerControls.addView(billingBox)
        rangeButton=button("接收时间：全部") {filterRangeDialog()};ledgerControls.addView(rangeButton)
        summary=text("",12f);ledgerControls.addView(summary)
        list=ListView(this).apply {setSelector(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));setItemsCanFocus(true);descendantFocusability=ViewGroup.FOCUS_AFTER_DESCENDANTS;dividerHeight=dp(5);divider=null;setBackgroundColor(Color.TRANSPARENT)
            setOnItemClickListener {_,_,position,_->val index=position-headerViewsCount;if(index in shown.indices && page in listOf("debit","credit")) detail(shown[index].id)} }
        list.addHeaderView(root,null,true)
        navigation=horizontal(navItem("概览",0,"home"),navItem("流水",1,"debit"),navItem("统计",4,"stats"),navItem("信用卡",2,"credit"),navItem("设置",3,"settings"))
        val container=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.rgb(250,250,247));addView(list,LinearLayout.LayoutParams(-1,0,1f));addView(navigation)}
        setContentView(container)
        container.setOnApplyWindowInsetsListener {v,insets->
            if(Build.VERSION.SDK_INT>=30) {
                val bars=insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);WindowInsets.CONSUMED
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,insets.systemWindowInsetRight,insets.systemWindowInsetBottom)
                @Suppress("DEPRECATION")
                insets.consumeSystemWindowInsets()
            }
        }
    }
    private fun selectedCycle(statement:Int):BillingCycle {
        val prefs=statementPrefs();val day=prefs.getInt("repayment_day",0);val month=prefs.getInt("repayment_month",1)
        return if(RepaymentDate.valid(statement,month,day)) RepaymentDate.upcomingCycle(LocalDate.now(),statement,month,day,cycleOffset)
        else BillingCycle.containing(LocalDate.now(),statement,cycleOffset)
    }
    private fun pendingAssociations() {
        val pending=rows.filter {it.recognizedTransaction && it.effective?.kind in listOf(Kind.CREDIT_REVERSAL,Kind.CREDIT_REFUND) && links.none {l->l.adjustmentId==it.id}}
        AlertDialog.Builder(this).setTitle("待关联（${pending.size}）").setItems(pending.map {"${it.effective!!.money} · ${it.effective.merchant ?: "商户未提供"}\n${it.effective.date ?: "日期未知"} · 查看原文与候选"}.toTypedArray()) {_,i->linkDialog(pending[i])}.setNegativeButton("关闭",null).show()
    }
    private fun repaymentLabel():String {
        val prefs=statementPrefs();val statement=prefs.getInt("statement_day",0);val day=prefs.getInt("repayment_day",0)
        if(statement==0) return "账单日未设置，尚不能确定还款周期"
        if(day==0) return "还款日未设置 · 请按银行账单配置"
        val month=prefs.getInt("repayment_month",1)
        if(!RepaymentDate.valid(statement,month,day)) return "还款日早于账单日，请重新设置"
        val cycle=selectedCycle(statement)
        val due=RepaymentDate.forCycle(cycle,month,day);val days=java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(),due)
        return "账期 ${cycle.start} — ${cycle.end}\n计划还款 $due · "+(if(days>=0) "还有 $days 天" else "该计划日期已过 ${-days} 天")+"\n出账${if(month==0) "当月" else "次月"} $day 日 · 不是银行应还账单"
    }
    private fun configureRepayment() {
        val prefs=statementPrefs();val statement=prefs.getInt("statement_day",0)
        if(statement==0) {toast("请先设置账单日");configureStatementDay();return}
        val box=column();box.addView(text("独立还款日期：短月取月底。预览不代表银行真实设置，保存前请核对。"))
        val month=Spinner(this).apply {adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,listOf("出账当月","出账次月"));setSelection(prefs.getInt("repayment_month",1))}
        val day=NumberPicker(this).apply {minValue=1;maxValue=31;value=prefs.getInt("repayment_day",1).coerceIn(1,31)}
        box.addView(month);box.addView(day);val preview=text("");box.addView(preview)
        val dialog=AlertDialog.Builder(this).setTitle("设置还款日").setView(ScrollView(this).apply {addView(box)}).setNegativeButton("取消",null).setPositiveButton("保存",null).create()
        fun update() {val valid=RepaymentDate.valid(statement,month.selectedItemPosition,day.value)
            val cycle=if(valid) RepaymentDate.upcomingCycle(LocalDate.now(),statement,month.selectedItemPosition,day.value,cycleOffset) else BillingCycle.containing(LocalDate.now(),statement)
            preview.text=if(valid) "${cycle.start} — ${cycle.end}\n计划还款 ${RepaymentDate.forCycle(cycle,month.selectedItemPosition,day.value)}" else "还款日早于账单日，请选择次月或调整日号"
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled=valid }
        month.onItemSelectedListener=object:AdapterView.OnItemSelectedListener {override fun onItemSelected(p:AdapterView<*>?,v:View?,i:Int,id:Long)=update();override fun onNothingSelected(p:AdapterView<*>?) {}}
        day.setOnValueChangedListener {_,_,_->update()}
        dialog.setOnShowListener {update();dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if(RepaymentDate.valid(statement,month.selectedItemPosition,day.value)) {prefs.edit().putInt("repayment_month",month.selectedItemPosition).putInt("repayment_day",day.value).apply();cycleOffset=0;lastFxRequest=null;dialog.dismiss();refreshCreditEstimate();renderDashboard()}
        }};dialog.show()
    }
    private val ink=Color.rgb(38,56,68)
    private val teal=Color.rgb(53,94,103)
    private var expenseLabels=emptyMap<Long,String>()
    private var merchantRules=emptyList<MerchantRule>()
    private var batch=false
    private val selectedIds=mutableSetOf<Long>()
    private var query=""
    private var matchId:Long?=null
    private var matchChoice:Long?=null
    private var otherAmounts=false
    private var statsKind=AccountKind.DEBIT
    private var statsCurrency="CNY"
    private var statsAccount:String?=null
    private fun info(title:String,message:String) {AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("关闭",null).show()}
    private fun money(v:BigDecimal?)=v?.let {val scale=maxOf(2,it.stripTrailingZeros().scale());java.text.DecimalFormat("#,##0."+"0".repeat(scale.coerceAtMost(8))).apply {decimalFormatSymbols=java.text.DecimalFormatSymbols(java.util.Locale.US)}.format(it)} ?: "—"
    private fun estimateMoney(v:BigDecimal?)=money(v?.setScale(2,java.math.RoundingMode.HALF_UP))
    private fun plainBox()=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
    private fun labelFor(r:StoredSms)=when(r.category) {Category.TRANSFER->"转账";Category.REPAYMENT->"还款";Category.INCOME->"收入";Category.OTHER->"其他非收支";else->expenseLabels[r.id] ?: "待分类"}
    private fun merchant(r:StoredSms)=merchantRules.find {it.merchant==r.effective?.merchant}?.alias?.takeIf {it.isNotBlank()} ?: r.effective?.merchant ?: r.effective?.kind?.label ?: "待确认"
    private fun tag(label:String,action:()->Unit)=button(label,action).apply {textSize=11f;minHeight=dp(36);minimumHeight=dp(36);setPadding(dp(8),dp(3),dp(8),dp(3));background=rounded(Color.rgb(231,239,236),7)}
    private fun split(left:View,right:View)=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL;addView(left,LinearLayout.LayoutParams(0,-2,1f));addView(right,LinearLayout.LayoutParams(-2,-2))}
    private fun bankTitle(label:String,mark:String,white:Boolean=false)=LinearLayout(this).apply {
        orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL
        addView(text(mark,14f).apply {gravity=android.view.Gravity.CENTER;setTextColor(if(white) Color.WHITE else ink);background=android.graphics.drawable.GradientDrawable().apply {shape=android.graphics.drawable.GradientDrawable.OVAL;setStroke(dp(1),if(white) 0xff9fb7ba.toInt() else 0xffa8b4b1.toInt())}},LinearLayout.LayoutParams(dp(32),dp(32)))
        addView(text(label,14f).apply {setPadding(dp(9),0,0,0);if(white)setTextColor(Color.WHITE)},LinearLayout.LayoutParams(0,-2,1f))
    }
    private fun categoryTile(name:String)=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;gravity=android.view.Gravity.CENTER;setPadding(dp(6),dp(12),dp(6),dp(10));background=rounded(0xffeeefe8.toInt(),12);addView(LedgerIcon(this@MainActivity,5+(db.categories().indexOf(name).coerceAtLeast(0)%6)).apply {active=true},LinearLayout.LayoutParams(dp(24),dp(24)));addView(text(name,13f).apply {gravity=android.view.Gravity.CENTER;setPadding(0,dp(8),0,0)});setOnClickListener {info(name,"在流水中点分类标签，或批量选择后标记。")}}
    private fun heading(label:String,actionLabel:String="",action:()->Unit={}) {gap(dashboard,18);dashboard.addView(split(text(label,16f,true),link(actionLabel,action)));gap(dashboard,4)}
    private fun statsDate(r:StoredSms):LocalDate?=if(r.effective?.accountKind==AccountKind.CREDIT) r.effective.date?.let {d->d.year?.let {LocalDate.of(it,d.month,d.day)}} else Instant.ofEpochMilli(r.received).atZone(zone).toLocalDate()
    private fun scopeRows(kind:AccountKind)=rows.filter {r->val day=statsDate(r);r.recognizedTransaction && r.effective!!.accountKind==kind && day!=null && (from==null || day>=from) && (to==null || day<=to) && (statsAccount==null || r.effective.account==statsAccount)}
    private fun statsAccounts() {
        val accounts=rows.filter {it.recognizedTransaction}.map {it.effective!!.account}.distinct().sorted();val choices=listOf("全部借记卡","全部信用卡")+accounts
        AlertDialog.Builder(this).setTitle("统计账户").setItems(choices.toTypedArray()) {_,i->if(i<2) {statsKind=if(i==0) AccountKind.DEBIT else AccountKind.CREDIT;statsAccount=null} else {statsAccount=accounts[i-2];statsKind=rows.first {it.effective?.account==statsAccount}.effective!!.accountKind};renderDashboard()}.setNegativeButton("取消",null).show()
    }
    private fun netPoints(source:List<StoredSms>)=Spending.points(rows.map(StoredSms::entry),links,expenseLabels,source.map {it.id}.toSet())
    private fun rangeName()=if(from==null && to==null) "全部日期⌄" else "${from ?: "…"} — ${to ?: "…"}⌄"
    private fun categoryDialog(ids:Set<Long>) {
        if(ids.isEmpty()) {toast("先选择流水");return}
        val options=db.categories()+listOf("待分类","转账","还款")
        AlertDialog.Builder(this).setTitle(if(ids.size==1) "选择分类" else "批量分类 · ${ids.size} 笔").setItems(options.toTypedArray()) {_,i->
            AlertDialog.Builder(this).setTitle("标记为${options[i]}").setMessage("${ids.size} 笔流水 · 原文保留").setPositiveButton("保存") {_,_->safe {db.classify(ids,options[i]);batch=false;selectedIds.clear()}}.setNegativeButton("取消",null).show()
        }.setNegativeButton("取消",null).show()
    }
    private fun pickFilter(spinner:Spinner,title:String) {val values=(0 until spinner.count).map {spinner.getItemAtPosition(it).toString()};AlertDialog.Builder(this).setTitle(title).setItems(values.toTypedArray()) {_,i->spinner.setSelection(i)}.setNegativeButton("取消",null).show()}
    private fun searchDialog() {val field=EditText(this).apply {setText(query);hint="商户、原文、金额"};AlertDialog.Builder(this).setTitle("搜索流水").setView(field).setPositiveButton("搜索") {_,_->query=field.text.toString();render()}.setNeutralButton("清空") {_,_->query="";render()}.setNegativeButton("取消",null).show()}
    private fun dueInfo():Pair<String,String> {
        val p=statementPrefs();val s=p.getInt("statement_day",0);val d=p.getInt("repayment_day",0);val m=p.getInt("repayment_month",1)
        if(!RepaymentDate.valid(s,m,d)) return "未设置" to "设置日期"
        val due=RepaymentDate.forCycle(selectedCycle(s),m,d);val days=java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(),due)
        return due.format(DateTimeFormatter.ofPattern("MM.dd")) to if(days>=0) "${days} 天后" else "已过 ${-days} 天"
    }
    private fun renderDashboard() {
        if(!::dashboard.isInitialized) return
        dashboard.removeAllViews();ledgerControls.visibility=View.GONE;eyebrow.visibility=View.GONE
        pageTitle.text=when(page){"home"->"概览";"debit"->"流水";"stats"->"统计";"credit"->"信用卡";"match"->"核对关联";else->"分类与规则"}
        if(demo) pageTitle.contentDescription=pageTitle.text.toString()+" · 合成演示"
        for(i in 0 until navigation.childCount) {val cell=navigation.getChildAt(i) as LinearLayout;val active=listOf("home","debit","stats","credit","settings")[i]==if(page=="match") "credit" else page;(cell.getChildAt(0) as LedgerIcon).active=active;cell.getChildAt(0).background=if(active) rounded(Color.rgb(226,236,232),11) else null;(cell.getChildAt(1) as TextView).setTextColor(if(active) teal else Color.rgb(137,148,151))}
        val debit=rows.filter {it.recognizedTransaction && it.effective!!.accountKind==AccountKind.DEBIT}
        val due=dueInfo()
        when(page) {
            "home"->{
                dashboard.addView(horizontal(tag(if(homeAll) "全部⌄" else "本月⌄") {homeAll=!homeAll;renderDashboard()},link("借记卡 · ⓘ") {info("收支口径","借记卡资金流入/流出，包含转账与还款；不是消费统计。按接收日期选择范围，信用卡独立。余额取每卡每币种最近可用短信快照，不是实时余额。")}))
                val today=LocalDate.now();val visible=debit.filter {homeAll || Instant.ofEpochMilli(it.received).atZone(zone).toLocalDate() in today.withDayOfMonth(1)..today}
                val totals=DebitDashboard.flows(debit.map(StoredSms::entry),visible.map {it.id}.toSet());gap(dashboard,19)
                if(totals.isEmpty()) dashboard.addView(link("暂无借记流水 · 查看扫描设置 →") {navigate("settings")})
                totals.forEach {(c,t)->val incoming=plainBox().apply {addView(muted("总收入 · $c"));addView(number(money(t.income),30));setOnClickListener {showDebitCategory(visible,Category.INCOME)}};val outgoing=plainBox().apply {setPadding(dp(18),0,0,0);addView(muted("总支出 · $c"));addView(number(money(t.spending),30));setOnClickListener {showDebitCategory(visible,Category.SPENDING)}};dashboard.addView(horizontal(incoming,outgoing))}
                gap(dashboard,20);rule(dashboard);gap(dashboard,17);dashboard.addView(split(muted("借记卡余额"),tag("短信快照") {showBalances(debit)}))
                val snapshots=DebitDashboard.balances(debit.map {ObservedEntry(it.entry(),it.received)})
                if(snapshots.isEmpty()) dashboard.addView(number("—",42))
                snapshots.groupBy {it.currency}.forEach {(c,group)->val known=group.mapNotNull {it.money};dashboard.addView(number("${if(c=="CNY") "¥" else c+" "}"+(if(known.size==group.size) money(known.fold(BigDecimal.ZERO){a,b->a+b.amount}) else "—"),42));val earliest=group.mapNotNull {it.received}.minOrNull();dashboard.addView(muted("${known.size} / ${group.size} 张卡 · "+(earliest?.let {Instant.ofEpochMilli(it).atZone(zone).format(DateTimeFormatter.ofPattern("MM.dd HH:mm"))} ?: "无快照"),11f))}
                heading("我的账户","查看流水 ↗") {navigate("debit")}
                dashboard.addView(panel(Color.rgb(238,234,225)).apply {addView(split(bankTitle("中国银行 · 借记卡","中"),muted("${debit.map {it.effective!!.tail}.distinct().size} 张卡")));gap(this,12);val points=netPoints(visible).groupBy {it.currency};if(points.isEmpty()) addView(split(muted("消费净额"),text("—",24f))) else points.forEach {(c,p)->addView(split(muted("消费净额 · $c"),number(money(p.fold(BigDecimal.ZERO){a,b->a+b.amount}),25)))};setOnClickListener {navigate("debit")}})
                dashboard.addView(panel(Color.rgb(40,74,84)).apply {addView(split(bankTitle("信用卡 · 本期消费","V",true),muted("参考估算",11f).apply {setTextColor(0xffc4d5d8.toInt())}));gap(this,10);addView(split(number("US$"+estimateMoney(lastEstimate?.usdSubtotal),28).apply {setTextColor(Color.WHITE)},text("≈ ¥"+estimateMoney(lastEstimate?.cnySubtotal),18f).apply {setTextColor(Color.WHITE)}));gap(this,10);addView(split(muted("${due.first} 还款").apply {setTextColor(0xffc4d5d8.toInt())},text(due.second,12f).apply {setTextColor(Color.WHITE)}));setOnClickListener {navigate("credit")}})
                dashboard.addView(link(if(demo) "● 合成演示 · 本地账本" else "● "+scanButton.text.toString()) {navigate("settings")}.apply {gravity=android.view.Gravity.CENTER})
            }
            "debit"->{
                dashboard.addView(button(if(query.isEmpty()) "⌕  搜索商户、原文、金额" else "⌕  $query") {searchDialog()});gap(dashboard,10)
                dashboard.addView(horizontal(tag(accountKind.label+"⌄") {accountKind=if(accountKind==AccountKind.DEBIT) AccountKind.CREDIT else AccountKind.DEBIT;reload()},tag(rangeName()) {filterRangeDialog()},tag(currencyFilter.selectedItem?.toString() ?: "币种⌄") {pickFilter(currencyFilter,"币种")},link(if(batch) "取消" else "批量") {batch=!batch;selectedIds.clear();render()}))
                dashboard.addView(link(accountFilter.selectedItem?.toString()+"⌄") {pickFilter(accountFilter,"账户")})
                val totals=netPoints(shown).groupBy {it.currency};dashboard.addView(split(muted(if(totals.isEmpty()) "暂无消费" else totals.entries.joinToString(" · "){(c,p)->"$c "+money(p.fold(BigDecimal.ZERO){a,b->a+b.amount})}),tag("待分类 ${shown.count {labelFor(it)=="待分类"}}") {val pending=shown.filter {labelFor(it)=="待分类" && it.effective!!.kind in listOf(Kind.DEBIT_PAYMENT,Kind.DEBIT_TRANSFER_OUT,Kind.CREDIT_PURCHASE)};batch=true;selectedIds.clear();selectedIds.addAll(pending.map {it.id});render()}))
                if(batch) dashboard.addView(button("已选 ${selectedIds.size} 笔 · 选择分类") {categoryDialog(selectedIds.toSet())})
                if(shown.isEmpty()) {gap(dashboard,28);dashboard.addView(text("暂无符合条件的流水",16f));dashboard.addView(link("清空筛选 →") {query="";from=null;to=null;reload()})}
            }
            "stats"->renderStats()
            "credit"->{
                dashboard.addView(split(muted("中国银行 VISA · 信用账户"),tag("日期设置") {navigate("settings")}))
                val day=statementPrefs().getInt("statement_day",0);gap(dashboard,14)
                if(day>0) {val cycle=selectedCycle(day);dashboard.addView(horizontal(link("‹") {cycleOffset--;lastFxRequest=null;refreshCreditEstimate();renderDashboard()},text(cycle.start.format(DateTimeFormatter.ofPattern("MM.dd"))+" — "+cycle.end.format(DateTimeFormatter.ofPattern("MM.dd")),14f).apply {gravity=android.view.Gravity.CENTER},link("›") {cycleOffset++;lastFxRequest=null;refreshCreditEstimate();renderDashboard()}.apply {gravity=android.view.Gravity.END}))} else dashboard.addView(link("设置账单日 →") {configureStatementDay()})
                gap(dashboard,21);dashboard.addView(split(muted("本期消费估算"),link("ⓘ") {estimateDetails()}));dashboard.addView(number("US$ "+estimateMoney(lastEstimate?.usdSubtotal),49));dashboard.addView(text("≈ ¥"+estimateMoney(lastEstimate?.cnySubtotal)+"  参考人民币",20f));gap(dashboard,14)
                dashboard.addView(link(lastEstimate?.let {"已覆盖 ${it.covered} / ${it.lines.size} 笔"+(if(it.missing+it.undated+it.pendingAdjustments>0) " · 待补全" else "")} ?: "设置账期后查看估算") {estimateDetails()})
                dashboard.addView(panel(Color.rgb(238,234,225)).apply {addView(muted("下次还款"));addView(split(number(due.first,30),tag(due.second) {configureRepayment()}))})
                val pending=rows.count {it.recognizedTransaction && it.effective!!.kind in listOf(Kind.CREDIT_REVERSAL,Kind.CREDIT_REFUND) && links.none {l->l.adjustmentId==it.id}}
                dashboard.addView(split(text("撤销 / 退货待关联",14f),tag("$pending 笔 →") {pendingAssociations()}));gap(dashboard,12);rule(dashboard)
                dashboard.addView(split(text("本期分类",14f),link("查看 →") {statsKind=AccountKind.CREDIT;statsAccount=null;val cycle=day.takeIf {it>0}?.let(::selectedCycle);from=cycle?.start;to=cycle?.end;navigate("stats")}));rule(dashboard)
                heading("最近调整")
                rows.filter {it.recognizedTransaction && it.effective!!.kind in listOf(Kind.CREDIT_REFUND,Kind.CREDIT_REVERSAL)}.take(2).forEach {dashboard.addView(transactionRow(it,false))}
                dashboard.addView(link("全部信用卡流水 →") {navigate("debit");accountKind=AccountKind.CREDIT;reload()})
            }
            "match"->renderMatch()
            "settings"->renderSettings()
        }
    }
    private fun showBalances(debit:List<StoredSms>) {info("余额快照",DebitDashboard.balances(debit.map {ObservedEntry(it.entry(),it.received)}).joinToString("\n\n"){b->"${b.account} · ${b.money ?: "未知"}\n"+(b.received?.let {Instant.ofEpochMilli(it).atZone(zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))} ?: "无快照")}.ifEmpty {"尚无可用余额快照"})}
    private fun transactionRow(r:StoredSms,selectable:Boolean=true):View {
        val e=r.effective!!;val incoming=e.kind in listOf(Kind.DEBIT_TRANSFER_IN,Kind.CREDIT_REFUND,Kind.CREDIT_REVERSAL)
        return plainBox().apply {
            setPadding(0,dp(12),0,dp(12));addView(muted(e.date?.toString() ?: "日期待确认",11f))
            val nameColumn=plainBox().apply {addView(text(merchant(r),15f));addView(muted("${e.tail} · ${e.money.currency}"+(if(links.any {it.adjustmentId==r.id}) " · 已关联" else ""),11f));setOnClickListener {detail(r.id)}}
            val left=LinearLayout(this@MainActivity).apply {orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL;addView(LedgerIcon(this@MainActivity,db.categories().indexOf(labelFor(r)).let {if(it<0) 10 else 5+it%6}).apply {background=rounded(0xffedf0e9.toInt(),10);setPadding(dp(6),dp(6),dp(6),dp(6))},LinearLayout.LayoutParams(dp(36),dp(36)));addView(nameColumn,LinearLayout.LayoutParams(0,-2,1f).apply {leftMargin=dp(9)})}
            val right=plainBox().apply {gravity=android.view.Gravity.END;addView(number((if(incoming) "+" else "−")+money(e.money.amount),20));if(e.kind in listOf(Kind.DEBIT_PAYMENT,Kind.DEBIT_TRANSFER_OUT,Kind.CREDIT_PURCHASE)) addView(tag(labelFor(r)+"⌄") {categoryDialog(setOf(r.id))}) else addView(muted(if(links.any {it.adjustmentId==r.id}) "已关联" else if(e.kind in listOf(Kind.CREDIT_REFUND,Kind.CREDIT_REVERSAL)) "待关联" else labelFor(r),11f))}
            val body=split(left,right)
            if(batch && selectable && e.kind in listOf(Kind.DEBIT_PAYMENT,Kind.DEBIT_TRANSFER_OUT,Kind.CREDIT_PURCHASE)) {val check=CheckBox(this@MainActivity).apply {isChecked=r.id in selectedIds;contentDescription="选择流水 ${r.id}";setOnCheckedChangeListener {_,checked->if(checked) selectedIds.add(r.id) else selectedIds.remove(r.id);renderDashboard()}};addView(split(body,check))} else addView(body)
            gap(this,10);rule(this);setOnClickListener {detail(r.id)}
        }
    }
    private fun renderStats() {
        val available=scopeRows(statsKind);val currencies=available.map {it.effective!!.money.currency}.distinct().sorted();if(statsCurrency !in currencies && currencies.isNotEmpty()) statsCurrency=currencies.first()
        dashboard.addView(horizontal(tag(rangeName()) {filterRangeDialog()},tag((statsAccount ?: statsKind.label)+"⌄") {statsAccounts()},tag(statsCurrency+"⌄") {AlertDialog.Builder(this).setTitle("原币统计").setItems(currencies.toTypedArray()) {_,i->statsCurrency=currencies[i];renderDashboard()}.show()}));gap(dashboard,16)
        val visible=available.filter {it.effective!!.money.currency==statsCurrency};val points=netPoints(visible);val groups=points.groupBy {it.category}.mapValues {(_,p)->p.fold(BigDecimal.ZERO){a,b->a+b.amount}}.entries.sortedByDescending {it.value}.map {it.key to it.value};val total=points.fold(BigDecimal.ZERO){a,b->a+b.amount}
        dashboard.addView(link("消费净额 · 原币 ⓘ") {info("统计口径","借记按接收日期、信用按正文完整交易日期筛选原消费。已关联调整归原消费并只扣一次。转账与还款排除；用途未知的跨行支取不擅自视为消费，网上支付待分类仍计入。不同币种独立；参考折算请看信用卡。未来或日期不全的调整暂不扣减。")}.apply {gravity=android.view.Gravity.CENTER})
        dashboard.addView(number((if(statsCurrency=="CNY") "¥" else statsCurrency+" ")+money(total),36).apply {gravity=android.view.Gravity.CENTER})
        dashboard.addView(SpendingChart(this,groups),LinearLayout.LayoutParams(-1,dp(156)))
        groups.forEachIndexed {i,(name,amount)->dashboard.addView(split(text("●  $name",13f).apply {val styled=android.text.SpannableString(text);styled.setSpan(android.text.style.ForegroundColorSpan(SpendingChart.colors[i%SpendingChart.colors.size]),0,1,0);text=styled;setOnClickListener {info(name,points.filter {it.category==name}.joinToString("\n"){p->val r=rows.first {it.id==p.id};"${merchant(r)} · ${money(p.amount)} $statsCurrency"})}},text(money(amount)+"    "+(if(total.signum()==0) "0" else amount.multiply(BigDecimal(100)).divide(total,1,java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString())+"%",13f)))}
        heading("时间趋势",statsCurrency)
        val days=visible.mapNotNull(::statsDate);val first=from ?: days.minOrNull() ?: LocalDate.now();val last=to ?: days.maxOrNull() ?: first;val span=(java.time.temporal.ChronoUnit.DAYS.between(first,last)+1).coerceAtLeast(1);val step=(span+3)/4
        val bins=(0..3).map {i->val a=first.plusDays(i*step);val b=minOf(last,a.plusDays(step-1));val ids=visible.filter {statsDate(it)?.let {d->d in a..b}==true}.map {it.id}.toSet();a.format(DateTimeFormatter.ofPattern("M/d")) to points.filter {it.id in ids}.fold(BigDecimal.ZERO){s,p->s+p.amount}}
        dashboard.addView(SpendingChart(this,bins,true),LinearLayout.LayoutParams(-1,dp(138)))
        val pending=groups.find {it.first=="待分类"}?.second ?: BigDecimal.ZERO;dashboard.addView(link("待分类 ${money(pending)} · 去整理 →") {navigate("debit");accountKind=statsKind;reload()})
        if(statsKind==AccountKind.CREDIT) {val unknown=rows.count {it.recognizedTransaction && it.effective!!.kind==Kind.CREDIT_PURCHASE && statsDate(it)==null};if(unknown>0) dashboard.addView(link("日期待确认 $unknown 笔 →") {rawRecords()})}
        val unresolved=visible.count {it.effective!!.kind==Kind.DEBIT_TRANSFER_OUT && it.category==Category.PENDING};if(unresolved>0) dashboard.addView(link("用途待确认 $unresolved 笔 →") {navigate("debit")})
    }
    private fun renderSettings() {
        heading("常用分类","＋ 自定义") {val input=EditText(this);AlertDialog.Builder(this).setTitle("新建分类").setView(input).setPositiveButton("保存") {_,_->safe {db.addCategory(input.text.toString())}}.setNegativeButton("取消",null).show()}
        db.categories().chunked(3).forEach {group->dashboard.addView(horizontal(*group.map {categoryTile(it)}.toTypedArray()));gap(dashboard,7)}
        heading("商户规则","＋ 添加") {editRule()}
        merchantRules.forEach {r->dashboard.addView(panel(0xffeef1e9.toInt()).apply {addView(split(text(r.alias.ifBlank {r.merchant},14f,true),tag(r.name) {editRule(r)}));addView(muted(r.merchant,11f));setOnClickListener {editRule(r)}})}
        if(merchantRules.isEmpty()) dashboard.addView(link("添加商户别名与默认分类 →") {editRule()})
        val prefs=getSharedPreferences(if(demo) "rules-demo" else "rules",0)
        dashboard.addView(Switch(this).apply {text="自动套用默认分类";textSize=14f;thumbTintList=android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(0xff718f82.toInt(),0xffb7c1bd.toInt()));trackTintList=android.content.res.ColorStateList.valueOf(0xffdce6df.toInt());isChecked=prefs.getBoolean("enabled",true);setOnCheckedChangeListener {_,v->prefs.edit().putBoolean("enabled",v).apply();if(v) safe {db.applyRules()}}})
        dashboard.addView(split(text("人工分类优先",14f),muted("始终保留")));gap(dashboard,15);rule(dashboard)
        heading("账单与还款","修改 →") {dateSettings()}
        val p=statementPrefs();dashboard.addView(split(text("账单日 / 还款日",14f),link("${p.getInt("statement_day",0).takeIf {it>0} ?: "—"} 日 / "+(p.getInt("repayment_day",0).takeIf {it>0}?.let {"${if(p.getInt("repayment_month",1)==1) "次月" else "当月"} $it 日"} ?: "—")) {dateSettings()}))
        heading("短信与本地账本")
        dashboard.addView(button(scanButton.text.toString()) {if(!demo) {if(!SmsImport.hasRead(this)) requestSms() else {SmsImport.enableAutomatic(this);observeSms();requestAutomaticScan()}}})
        dashboard.addView(link("原文 / 重解析 / 权限 / 工具 →") {menu()})
    }
    private fun dateSettings() {AlertDialog.Builder(this).setTitle("账单与还款").setItems(arrayOf("设置账单日","设置还款日","查看本期日期")) {_,i->when(i){0->configureStatementDay();1->configureRepayment();else->info("本期日期",repaymentLabel()+"\n短月取月底，提醒未启用")}}.setNegativeButton("关闭",null).show()}
    private fun editRule(old:MerchantRule?=null) {
        val box=column();fun field(label:String,value:String)=EditText(this).apply {hint=label;setText(value);isSingleLine=true;box.addView(this)}
        val merchant=field("原始商户（精确匹配）",old?.merchant.orEmpty()).apply {isEnabled=old==null};val alias=field("显示别名",old?.alias.orEmpty());val names=db.categories();val category=Spinner(this).apply {adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,names);setSelection(names.indexOf(old?.name).coerceAtLeast(0))};box.addView(category)
        val apply=CheckBox(this).apply {text="套用未人工分类的现有流水";isChecked=false};box.addView(apply)
        val builder=AlertDialog.Builder(this).setTitle("商户分类规则").setView(box).setPositiveButton("保存") {_,_->safe {db.saveRule(MerchantRule(merchant.text.toString(),alias.text.toString(),names[category.selectedItemPosition]),apply.isChecked);if(apply.isChecked) db.applyRules()}}.setNegativeButton("取消",null)
        if(old!=null) builder.setNeutralButton("删除规则") {_,_->safe {db.deleteRule(old.merchant)}};builder.show()
    }
    private fun renderMatch() {
        val current=rows.find {it.id==matchId} ?: run {dashboard.addView(text("请选择待关联记录"));return}
        fun source(r:StoredSms,title:String,selected:Boolean=false)=panel(if(selected) 0xffe8efea.toInt() else 0xfff0eee7.toInt()).apply {addView(muted(title,11f));addView(split(text(merchant(r),15f,true),muted(r.effective?.date?.toString() ?: "日期未知",11f)));addView(number((if(r.effective?.kind==Kind.CREDIT_PURCHASE) "− " else "+ ")+r.effective?.money.toString(),25));addView(muted("卡 ${r.effective?.tail}",11f));addView(text(r.body,12f).apply {setTextIsSelectable(true)})}
        dashboard.addView(source(current,"本次退货 / 撤销"));heading("选择原消费",if(otherAmounts) "其他金额⌄" else "同金额⌄") {otherAmounts=!otherAmounts;matchChoice=null;renderDashboard()}
        val candidates=rows.filter {r->r.sim==current.sim && r.source.startsWith("demo:")==current.source.startsWith("demo:") && Accounting.validateLink(rows.map(StoredSms::entry),links,current.id,r.id)==null && (otherAmounts || r.effective!!.money.amount.compareTo(current.effective!!.money.amount)==0)}.sortedWith(compareBy<StoredSms> {it.effective!!.date?.let {d->d.year?.let {LocalDate.of(it,d.month,d.day)}} ?: LocalDate.MAX}.thenBy {it.id})
        if(matchChoice !in candidates.map {it.id}) matchChoice=candidates.firstOrNull()?.id
        candidates.forEachIndexed {i,r->dashboard.addView(source(r,(if(r.id==matchChoice) "● " else "○ ")+(if(i==0) "最早一笔" else "候选消费"),r.id==matchChoice).apply {setOnClickListener {matchChoice=r.id;renderDashboard()};contentDescription="选择消费 ${r.id}"})}
        if(candidates.isEmpty()) dashboard.addView(text("暂无符合条件的原消费",15f))
        if(candidates.isNotEmpty()) dashboard.addView(button("确认关联") {val id=matchChoice ?: return@button;safe {db.link(current.id,id);page="credit"}}.apply {setTextColor(Color.WHITE);background=rounded(teal,13)})
        dashboard.addView(link("关联规则 ⓘ") {info("关联规则","同卡同币种、同商户同金额自动关联最早尚可冲销的消费。无商户仅同额需人工确认；部分退款选择其他金额。日期不完整仅人工核对，不能关联晚于退款的消费。原文保留，详情可解除。")})
    }

    private fun showDebitCategory(source:List<StoredSms>,category:Category) {
        val selected=source.filter {if(category==Category.INCOME) it.effective?.kind==Kind.DEBIT_TRANSFER_IN else it.effective?.kind in listOf(Kind.DEBIT_PAYMENT,Kind.DEBIT_TRANSFER_OUT)}
        AlertDialog.Builder(this).setTitle("借记卡${category.label}明细（${selected.size}）").setItems(selected.map {"${it.effective!!.money} · ${it.effective.merchant ?: it.effective.kind.label}\n${received(it)}"}.toTypedArray()) {_,i->detail(selected[i].id)}.setNegativeButton("关闭",null).show()
    }
    private fun setChoices(spinner:Spinner,values:List<String>,selection:String?=spinner.selectedItem?.toString()) {
        spinner.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,values)
        spinner.setSelection(values.indexOf(selection).coerceAtLeast(0))
        spinner.onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long) {if(!refreshing) render()}
            override fun onNothingSelected(parent:AdapterView<*>?) { }
        }
    }
    private fun reload() {
        if(isDestroyed || isFinishing) return
        if(getSharedPreferences(if(demo) "rules-demo" else "rules",0).getBoolean("enabled",true)) db.applyRules();db.autoAssociate();rows=db.all();links=db.links();expenseLabels=db.labels();merchantRules=db.rules();refreshing=true
        val group=rows.filter {it.recognizedTransaction && it.effective?.accountKind==accountKind}
        setChoices(accountFilter,listOf("全部账户")+group.mapNotNull {it.effective?.account}.distinct().sorted())
        setChoices(currencyFilter,listOf("全部币种")+group.mapNotNull {it.effective?.money?.currency}.distinct().sorted())
        refreshing=false;render();updateStatus()
    }
    private fun pending(row:StoredSms):Boolean = !row.excluded && (row.effective!=null || row.parsed.status!=ParseStatus.IGNORED) &&
        (!row.verified || row.effective==null || !row.effective.currenciesRecognized || row.category==Category.PENDING && row.effective.kind !in listOf(Kind.CREDIT_REVERSAL,Kind.CREDIT_REFUND) || row.effective.kind in listOf(Kind.CREDIT_REVERSAL,Kind.CREDIT_REFUND) && links.none {it.adjustmentId==row.id})
    private fun render() {
        if(!::list.isInitialized) return
        val account=accountFilter.selectedItem?.toString();val currency=currencyFilter.selectedItem?.toString();val savedPosition=list.firstVisiblePosition;val savedTop=list.getChildAt(0)?.top ?: 0
        shown=rows.filter { r ->
            val day=Instant.ofEpochMilli(r.received).atZone(zone).toLocalDate()
            r.recognizedTransaction && r.effective?.accountKind==accountKind &&
            (account==null || account=="全部账户" || r.effective.account==account) &&
            (currency==null || currency=="全部币种" || r.effective?.money?.currency==currency) &&
            (from==null || day>=from) && (to==null || day<=to) && (query.isBlank() || (merchant(r)+r.body+r.effective?.money.toString()).contains(query,true))
        }
        rangeButton.text=getString(R.string.received_range,from?.toString() ?: "不限",to?.toString() ?: "不限")
        list.adapter=object:BaseAdapter() {
            override fun getCount()=if(page=="debit") shown.size else 0
            override fun getItem(position:Int)=shown[position]
            override fun getItemId(position:Int)=shown[position].id
            override fun getView(position:Int,convertView:View?,parent:ViewGroup?):View = plainBox().apply {setPadding(dp(24),0,dp(24),0);addView(transactionRow(shown[position]))}
        }
        list.setSelectionFromTop(savedPosition.coerceAtMost(shown.size),savedTop)
        billingBox.visibility=View.GONE
        refreshCreditEstimate();renderDashboard()
    }
    private fun received(r:StoredSms)=Instant.ofEpochMilli(r.received).atZone(zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    private fun updateStatus(message:String?=null) {
        if(::scanButton.isInitialized) {
            scanButton.text=when {demo->"合成演示";!SmsImport.hasRead(this)->"授权自动扫描";!SmsImport.enabled(this)->"启用自动扫描";else->"自动扫描已启用"}
            scanButton.isEnabled=!demo && (!SmsImport.hasRead(this) || !SmsImport.enabled(this))
        }
        status.text=message ?: if(demo) "合成样例与手机账本分库保存" else {
            val prefs=getSharedPreferences("import",MODE_PRIVATE)
            when {
                !SmsImport.hasRead(this)->"尚未授权读取短信；已存账目仍可查看"
                !prefs.getBoolean("enabled",false)->"自动扫描已暂停；已存交易仍可查看"
                else->"自动扫描已启用 · 后台短信接收${if(checkSelfPermission(Manifest.permission.RECEIVE_SMS)==PackageManager.PERMISSION_GRANTED) "已授权" else "未授权"}"
            } + (prefs.getString("background_error",null)?.let {"\n$it"} ?: "")
        }
    }
    private fun <T> work(label:String,task:()->T,done:(T)->String) {
        if(busy) return
        busy=true;cancelled.set(false);updateStatus("$label…")
        worker.execute {
            val result=runCatching(task)
            runOnUiThread {
                busy=false
                if(!isDestroyed && !isFinishing) {
                    result.fold({reload();updateStatus(done(it))},{reload();updateStatus(when(it) {is SecurityException->"短信权限不足或已撤销，请重新授权";is InterruptedException->"已停止；已保存记录可安全重扫";else->"$label 未完成，请稍后重试"})})
                } else db.close()
                if(rescanRequested && !isDestroyed && !isFinishing) {rescanRequested=false;requestAutomaticScan()}
            }
        }
    }
    private fun toast(message:String)=Toast.makeText(this,message,Toast.LENGTH_LONG).show()
    private fun safe(action:()->Unit) {try {action();reload()} catch(e:IllegalArgumentException) {toast(e.message ?: "输入无效")} catch(e:IllegalStateException) {toast(e.message ?: "操作无效")} }
    private fun requestSms() {
        AlertDialog.Builder(this).setTitle("读取本机银行短信").setMessage("授权后自动读取本机现存的中行候选短信，并在新短信或回到 App 时补扫；首次读取全部现存记录，既有起始范围会保留。短信原文只留在本机，其他发送方需确认。仅币种和日期用于查询公开参考汇率。不会修改系统短信；拒绝后仍可查看已有账目。")
            .setPositiveButton("申请权限") {_,_->requestPermissions(arrayOf(Manifest.permission.READ_SMS,Manifest.permission.RECEIVE_SMS),10)}.setNegativeButton("取消",null).show()
    }
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==10) {
            updateStatus()
            if(SmsImport.hasRead(this)) {SmsImport.enableAutomatic(this);observeSms();requestAutomaticScan()} else AlertDialog.Builder(this).setTitle("未获短信读取权限").setMessage("可以继续查看已存账本和合成演示。如系统不再弹出授权，请到应用设置检查权限。")
                .setPositiveButton("应用设置") {_,_->startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:$packageName")))}.setNegativeButton("继续查看",null).show()
        }
    }
    private fun chooseDate(initial:LocalDate,onSelected:(LocalDate)->Unit) {
        DatePickerDialog(this,{_,y,m,d->onSelected(LocalDate.of(y,m+1,d))},initial.year,initial.monthValue-1,initial.dayOfMonth).show()
    }
    private fun importDialog() {
        if(demo) {toast("先切回手机账本再导入");return}
        if(!SmsImport.hasRead(this)) {requestSms();return}
        var start=LocalDate.now().minusMonths(3);var end=LocalDate.now()
        val box=column();box.addView(text("导入手机现存的中国银行候选短信。接收日期范围含首尾；原文年份缺失时保持未知。再次导入不会重复记账。",14f))
        val a=button("起始：$start") {};val b=button("结束：$end") {}
        a.setOnClickListener {chooseDate(start) {start=it;a.text=getString(R.string.start_date,start.toString())}}
        b.setOnClickListener {chooseDate(end) {end=it;b.text=getString(R.string.end_date,end.toString())}}
        box.addView(a);box.addView(b)
        val dialog=AlertDialog.Builder(this).setTitle("历史短信导入").setView(box).setNegativeButton("取消",null).setPositiveButton("开始导入",null).create()
        dialog.setOnShowListener {dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if(start>end) {toast("起始日期不能晚于结束日期");return@setOnClickListener}
            val first=start.atStartOfDay(zone).toInstant().toEpochMilli();val last=end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()-1
            dialog.dismiss()
            work("历史导入",{
                val n=SmsImport.scan(this,first,last,cancelled)
                val prefs=getSharedPreferences("import",MODE_PRIVATE)
                val oldStart=if(prefs.getBoolean("enabled",false)) prefs.getLong("start",first) else first
                // Historical selection may end in the past: subsequent incremental scans fill the gap up to now.
                val oldWatermark=if(prefs.getBoolean("enabled",false)) prefs.getLong("watermark",last) else last
                prefs.edit().putBoolean("enabled",true).putLong("start",minOf(oldStart,first)).putLong("watermark",maxOf(oldWatermark,last)).apply()
                SmsImport.schedule(this);n
            }) {"历史导入新增 $it 条；新短信和启动补扫已启用"}
        }};dialog.show()
    }
    private fun filterRangeDialog() {
        var a=from ?: LocalDate.now().minusMonths(1);var b=to ?: LocalDate.now()
        val box=column();box.addView(text("以短信接收日期筛选；正文交易日期另列，缺少年份不推算。"))
        val first=button("起始：$a") {};val last=button("结束：$b") {}
        first.setOnClickListener {chooseDate(a) {a=it;first.text=getString(R.string.start_date,a.toString())}}
        last.setOnClickListener {chooseDate(b) {b=it;last.text=getString(R.string.end_date,b.toString())}}
        box.addView(first);box.addView(last)
        AlertDialog.Builder(this).setTitle("列表接收日期范围").setView(box).setPositiveButton("应用") {_,_->if(a<=b) {from=a;to=b;render()} else toast("日期范围无效")}.setNeutralButton("全部日期") {_,_->from=null;to=null;render()}.setNegativeButton("取消",null).show()
    }
    private fun menu() {
        val options=arrayOf(if(demo) "切回手机账本" else "打开合成演示","短信权限与设置","重新解析（保留人工修正）",if(SmsImport.enabled(this)) "暂停自动扫描" else "恢复自动扫描","补读历史范围（可选）","保留的原始记录","刷新公开参考汇率","使用说明")
        AlertDialog.Builder(this).setTitle("账本工具").setItems(options) {_,which->when(which) {
            0->{fxEpoch.incrementAndGet();fxTask?.cancel(true);lastEstimate=null;demo=!demo;db.close();db=LedgerDb(this,if(demo) "demo.db" else "ledger.db");from=null;to=null;cycleOffset=0;lastFxRequest=null;buildUi();if(demo) loadDemo() else {reload();observeSms();requestAutomaticScan()}}
            1->if(SmsImport.hasRead(this)) startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:$packageName"))) else requestSms()
            2->work("重新解析",{db.reparse();Unit}) {"已重新解析；人工修正保留，已关联记录冻结"}
            3->if(demo) toast("请先切回手机账本") else if(SmsImport.enabled(this)) {SmsImport.pauseAutomatic(this);mainHandler.removeCallbacks(autoScan);updateStatus("自动扫描已暂停，已有交易保留")} else if(!SmsImport.hasRead(this)) requestSms() else {SmsImport.enableAutomatic(this);observeSms();requestAutomaticScan()}
            4->importDialog()
            5->rawRecords()
            6->{lastFxRequest=null;refreshCreditEstimate(true)}
            7->AlertDialog.Builder(this).setTitle("本地短信账本").setMessage("默认只展示成功识别或人工确认、来源/币种已确认且未排除的交易；借记卡与信用卡分开。其它原文保留在工具菜单，可重新解析。\n\n自动扫描：授权后首次读取现存候选短信；收到短信、短信库变化或回到应用时补扫。系统后台限制、强行停止或权限撤销仍可能延迟，下次打开恢复。可以主动暂停。\n\n账期按短信交易日估算，账单日当天归本期，短月使用月底。每笔净消费按原消费日参考率折 USD，再用最新可用 USD/CNY 折人民币，显示两位而中间不先舍入；已关联撤销/退货随原消费冲减，未关联调整单列，信用卡本金还款不计消费。\n\nFrankfurter v2 公开参考率不是银行牌价或官方应还额。仅查询币种/日期，不上传短信、账户、商户或金额。可核验有效日取不晚于目标日且近14天内最近的来源记录；缺失显示覆盖范围，离线可用缓存，历史及缓存时间可查看。\n\n数据只在本地，关闭系统备份；卸载会删除账本，尚无导出功能。还款双通知关联未实现，疑似借记卡本金扣款默认用途待确认。") .setPositiveButton("知道了",null).show()
        }}.show()
    }
    private fun rawRecords() {
        val records=db.all()
        val labels=records.map {"#${it.id} ${it.parsed.status.label}${if(it.manual) " · 人工更正" else ""}\n接收 ${received(it)}"}.toTypedArray()
        AlertDialog.Builder(this).setTitle("保留的原始记录（${records.size}）").setItems(labels) {_,index->rows=records;detail(records[index].id)}.setNegativeButton("关闭",null).show()
    }
    private fun statementPrefs()=getSharedPreferences(if(demo) "billing-demo" else "billing",MODE_PRIVATE)
    private fun configureStatementDay() {
        val box=column();box.addView(text("按短信交易日期估算：账单日当天归本期，下一天起归下期；短月取月底。与银行实际入账账单可能不同。此设置用于当前信用卡视图。"))
        val day=NumberPicker(this).apply {minValue=1;maxValue=31;value=statementPrefs().getInt("statement_day",1)}
        box.addView(day)
        AlertDialog.Builder(this).setTitle("配置信用卡账单日").setView(box).setPositiveButton("保存") {_,_->statementPrefs().edit().putInt("statement_day",day.value).apply();cycleOffset=0;lastFxRequest=null;refreshCreditEstimate();renderDashboard()}.setNegativeButton("取消",null).show()
    }
    private fun refreshCreditEstimate(force:Boolean=false) {
        if(!::billingText.isInitialized || page !in listOf("home","credit") || !foreground) return
        val day=statementPrefs().getInt("statement_day",0)
        billingDayButton.text=if(day==0) "设置账单日" else "账单日 $day 日"
        if(day==0) {fxEpoch.incrementAndGet();fxTask?.cancel(true);lastEstimate=null;billingText.text=getString(R.string.bill_unconfigured);return}
        val today=LocalDate.now();val cycle=selectedCycle(day)
        val account:String?=null
        val input=rows.filter {it.recognizedTransaction && it.effective?.accountKind==AccountKind.CREDIT && (account==null || account=="全部账户" || it.effective.account==account)}.map(StoredSms::entry)
        val token="$day/$cycle/$account/${input.hashCode()}/${links.hashCode()}/${System.currentTimeMillis()/3600000}"
        if(token==lastFxRequest && !force) return
        lastFxRequest=token;lastEstimate=null;val epoch=fxEpoch.incrementAndGet();fxTask?.cancel(true)
        val snapshotLinks=links.toList();val isDemo=demo
        billingText.text=getString(R.string.bill_loading,cycle.start.toString(),cycle.end.toString())
        fxTask=fxWorker.submit {
            val result=runCatching {
                FxCache(this).use {cache->BillingEstimator.estimate(input,snapshotLinks,cycle,today,FxRepository(cache,network=!isDemo,force=force,cancelled={epoch!=fxEpoch.get()}))}
            }
            runOnUiThread {
                if(epoch!=fxEpoch.get() || !foreground || isDestroyed) return@runOnUiThread
                result.fold({value->lastEstimate=value;billingText.text=formatEstimate(value);renderDashboard()},{billingText.text=getString(R.string.bill_failed,cycle.start.toString(),cycle.end.toString());renderDashboard()})
            }
        }
    }
    private fun formatEstimate(e:BillingEstimate):String=buildString {
        append("账期 ${e.cycle.start} 至 ${e.cycle.end}（含首尾）\n")
        fun amount(value:BigDecimal?)=value?.setScale(2,java.math.RoundingMode.HALF_UP)?.toPlainString() ?: "暂缺"
        if(e.lines.isEmpty()) append("本周期没有可归入的已识别消费\n") else {
            append(if(e.complete) "参考估算 " else "已覆盖部分 ")
            append("USD ${amount(e.usdSubtotal)} · CNY ${amount(e.cnySubtotal)}\n")
            append("已换算 ${e.covered}/${e.lines.size} 笔，缺报价/日期异常 ${e.missing} 笔\n")
        }
        if(e.usdSubtotal?.signum()==0 && e.missing==0) append("已确认净额为0，无需市场汇率\n")
        if(e.undated>0) append("另 ${e.undated} 笔缺完整交易日，未归账期\n")
        if(e.unlinkedAdjustments>0) append("账期内 ${e.unlinkedAdjustments} 笔撤销/退货未关联，未冲减\n")
        if(e.pendingAdjustments>0) append("${e.pendingAdjustments} 笔关联调整日期待核对，未冲减\n")
        e.latest?.let {append("USD/CNY 数据日 ${it.date}${if(it.cached) " · 缓存" else ""}${if(it.stale) "（联网失败/已过期）" else ""}\n")}
        e.latestMissing?.let {append("人民币折算暂缺：$it\n")}
        val historical=e.lines.mapNotNull {it.quote}
        if(e.lines.any {line->line.quote?.let {it.date!=line.date}==true}) append("部分使用最近有效报价日，详见明细\n")
        if(historical.any {it.stale}) append("含过期缓存参考率，请核对获取时间\n")
        append("按交易日估算 · 非银行应还额 · 来源 Frankfurter v2\n所选账户全部币种；不随下方接收日期/原币筛选。点击看明细。")
    }
    private fun estimateDetails() {
        val e=lastEstimate ?: return
        val value=buildString {
            append(formatEstimate(e));append("\n\n已关联调整按原消费归属期、原消费日净额折算，不再单列扣减。\n")
            for(line in e.lines) {
                append("\n#${line.id} ${line.date} ${line.currency} ${line.netAmount.toPlainString()} → USD ${line.usd?.toPlainString() ?: "暂缺"}\n")
                line.quote?.let {append("${it.base}/${it.quote} ${it.rate.toPlainString()}；有效日 ${it.date}\n${it.source}；获取 ${Instant.ofEpochMilli(it.fetchedAt)}${if(it.cached) "（缓存）" else ""}${if(it.stale) "（过期/联网失败）" else ""}\n")}
                line.missing?.let {append("$it\n")}
            }
            e.latest?.let {append("\n最新可用 USD/CNY ${it.rate.toPlainString()}；有效日 ${it.date}\n${it.source}；获取 ${Instant.ofEpochMilli(it.fetchedAt)}")}
        }
        AlertDialog.Builder(this).setTitle("参考估算明细").setMessage(value).setPositiveButton("关闭",null).show()
    }
    private fun loadDemo() = work("加载合成样例",{
        val json=assets.open("boc-sms.json").bufferedReader().use {JSONObject(it.readText())}
        var n=0;var i=0
        for(key in listOf("cases","boundary_cases")) {
            val cases=json.getJSONArray(key)
            for(index in 0 until cases.length()) {
                val c=cases.getJSONObject(index)
                if(db.ingest(c.getString("id"),"95566",c.getString("text"),Instant.parse("2026-06-12T12:00:00Z").toEpochMilli()+i++*1000,"synthetic",true))n++
            }
        };n
    }) {"合成演示已打开，新增 $it 条；与手机账本分开"}
    private fun detail(id:Long) {
        val r=rows.firstOrNull {it.id==id} ?: return;val e=r.effective
        val box=column()
        box.addView(text("#${r.id} ${e?.kind?.label ?: r.parsed.status.label}",20f,true))
        box.addView(text(buildString {
            append("来源：${r.sender} · SIM ${r.sim}\n接收：${received(r)}\n来源标识：${r.source}\n解析器 v${r.parserVersion} · ${r.parsed.status.label}\n")
            if(!r.verified)append("来源未确认，不计入汇总。\n")
            if(r.parsed.reason.isNotEmpty())append("${r.parsed.reason}\n")
            if(e!=null) {
                append("${e.account}\n正文日期：${e.date ?: "未知"}\n金额：${e.money}\n分类：${labelFor(r)} / ${r.category.label}\n商户：${e.merchant ?: "未知"}\n渠道：${e.channel ?: "未知"}\n")
                e.balance?.let {append("余额快照：$it（币种来自模板上下文）\n")}
                e.sourceMoney?.let {append("来源借记卡：${e.sourceTail}\n实际扣款：$it\n外币偿债：${e.money}\n汇率原文：${e.reportedRate}\n方向：${e.rateDirection ?: "未知"}\n")}
                if(e.kind==Kind.CREDIT_PURCHASE) append("清算/入账状态：未知\n")
            }
            if(r.manual)append("人工修正覆盖展示，原解析结果保留。\n")
            val outgoing=links.find {it.adjustmentId==id};val incoming=links.filter {it.purchaseId==id}
            outgoing?.let {append("已确认关联消费 #${it.purchaseId}\n")};incoming.forEach {append("调整证据 #${it.adjustmentId}\n")}
            if(r.excluded)append("已排除统计\n")
        },13f))
        box.addView(text("短信原文",15f,true));box.addView(text(r.body,14f).apply {setTextIsSelectable(true)})
        val scroll=ScrollView(this).apply {addView(box)}
        val dialog=AlertDialog.Builder(this).setView(scroll).setPositiveButton("关闭",null).create()
        fun action(label:String,block:()->Unit) {box.addView(button(label) {dialog.dismiss();block()})}
        if(!r.verified) action("确认本条短信来源") {AlertDialog.Builder(this).setTitle("确认来源").setMessage("请先核对发送方与原文。确认后本条识别事件可按分类计入账本；不会信任同号码的其他记录。") .setPositiveButton("确认本条") {_,_->safe {db.verify(id)}}.setNegativeButton("取消",null).show()}
        action("修正字段 / 分类") {edit(r)}
        if(r.manual) action("恢复原解析结果") {safe {db.restoreCorrection(id)}}
        if(e?.kind in listOf(Kind.CREDIT_REVERSAL,Kind.CREDIT_REFUND)) {
            val linked=links.any {it.adjustmentId==id}
            action(if(linked) "解除与原消费的关联" else "选择原消费并确认关联") {if(linked) safe {db.unlink(id)} else linkDialog(r)}
        }
        links.filter {it.purchaseId==id}.forEach {l->action("解除调整 #${l.adjustmentId}") {safe {db.unlink(l.adjustmentId)}}}
        action(if(r.excluded) "恢复统计" else "排除统计（保留原文）") {safe {db.toggleExcluded(id)}}
        action("查看修正与关联历史") {val history=db.history(id);AlertDialog.Builder(this).setTitle("操作历史 #$id").setMessage(if(history.isEmpty()) "尚无人工操作" else history.joinToString("\n\n")).setPositiveButton("关闭",null).show()}
        dialog.show()
    }
    private fun linkDialog(row:StoredSms) {matchId=row.id;matchChoice=null;otherAmounts=false;navigate("match")}
    private fun edit(row:StoredSms) {
        val old=row.effective;val box=column()
        box.addView(text("所有修正保留原文和操作历史。日期留空表示未知；不从接收时间补年。已有关联请先解除。"))
        fun input(label:String,value:String=""):EditText {box.addView(text(label,12f));return EditText(this).apply {setText(value);isSingleLine=true;box.addView(this)} }
        val kind=Spinner(this).apply {adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,Kind.entries.map {it.label});setSelection(old?.kind?.ordinal ?: 0)};box.addView(text("事件类型",12f));box.addView(kind)
        val tail=input("卡尾号（4位）",old?.tail.orEmpty())
        val currency=input("币种（大写三字母标准代码，如 CNY / USD / EUR）",old?.money?.currency ?: "CNY")
        val amount=input("正数金额",old?.money?.amount?.toPlainString().orEmpty())
        val date=input("正文日期 yyyy-MM-dd 或 MM-dd，可留空",old?.date?.let { if(it.year==null) "%02d-%02d".format(it.month,it.day) else "%04d-%02d-%02d".format(it.year,it.month,it.day) }.orEmpty())
        val merchant=input("商户，可留空",old?.merchant.orEmpty())
        val sourceTail=input("仅购汇还款：来源借记卡尾号",old?.sourceTail.orEmpty())
        val sourceCurrency=input("仅购汇还款：来源币种",old?.sourceMoney?.currency ?: "CNY")
        val sourceAmount=input("仅购汇还款：实际扣款金额",old?.sourceMoney?.amount?.toPlainString().orEmpty())
        val rate=input("仅购汇还款：短信汇率，可留空",old?.reportedRate?.toPlainString().orEmpty())
        val category=Spinner(this);box.addView(text("经济分类",12f));box.addView(category)
        fun choices() {val k=Kind.entries[kind.selectedItemPosition];val allowed=LedgerDb.allowedCategories(k);setChoices(category,allowed.map {it.label},row.category.label)}
        kind.onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long)=choices()
            override fun onNothingSelected(parent:AdapterView<*>?) { }
        };choices()
        val scroll=ScrollView(this).apply {addView(box)}
        val dialog=AlertDialog.Builder(this).setTitle("人工修正 #${row.id}").setView(scroll).setNegativeButton("取消",null).setPositiveButton("保存",null).create()
        dialog.setOnShowListener {dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                val k=Kind.entries[kind.selectedItemPosition]
                val m=BocParser.money(currency.text.toString().trim().uppercase(),amount.text.toString().trim())
                val d=date.text.toString().trim().let {s->when {s.isEmpty()->null;s.matches(Regex("[0-9]{2}-[0-9]{2}"))->BodyDate(null,s.substring(0,2).toInt(),s.substring(3).toInt());s.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))->LocalDate.parse(s).let {BodyDate(it.year,it.monthValue,it.dayOfMonth)};else->throw IllegalArgumentException("日期格式无效")}}
                val fx=k==Kind.CREDIT_FX_REPAYMENT
                if(fx) require(sourceTail.text.toString().matches(Regex("[0-9]{4}"))) {"来源尾号需为4位"}
                val outgoing=if(fx) BocParser.money(sourceCurrency.text.toString().trim().uppercase(),sourceAmount.text.toString().trim()) else null
                val reported=if(fx && rate.text.isNotBlank()) BigDecimal(rate.text.toString()).also {require(it>BigDecimal.ZERO)} else null
                val e=Event(k,tail.text.toString().trim(),d,m,merchant.text.toString().trim().ifEmpty {null},balance=old?.balance?.takeIf {old.kind==k && old.tail==tail.text.toString().trim() && it.currency==m.currency},channel=old?.channel?.takeIf {old.kind==k},sourceTail=if(fx) sourceTail.text.toString() else null,sourceMoney=outgoing,reportedRate=reported)
                db.correct(row.id,LedgerDb.allowedCategories(k)[category.selectedItemPosition],e)
                dialog.dismiss();reload()
            } catch(_:java.time.DateTimeException) {toast("日期无效")} catch(e:IllegalArgumentException) {toast(e.message ?: "字段无效")} catch(e:IllegalStateException) {toast(e.message ?: "字段无效")}
        }};dialog.show()
    }
}
