package local.boc.ledger

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Intent
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
    private lateinit var reviewFilter:Spinner
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
        from=savedInstanceState?.getString("from")?.let(LocalDate::parse)
        to=savedInstanceState?.getString("to")?.let(LocalDate::parse)
        db=LedgerDb(this,if(demo) "demo.db" else "ledger.db")
        buildUi();reload()
    }
    override fun onSaveInstanceState(outState:Bundle) { super.onSaveInstanceState(outState);outState.putBoolean("demo",demo);outState.putString("from",from?.toString());outState.putString("to",to?.toString()) }
    override fun onResume() {
        super.onResume()
        if(!demo && !busy && SmsImport.hasRead(this) && getSharedPreferences("import",MODE_PRIVATE).getBoolean("enabled",false))
            work("启动补扫",{SmsImport.incremental(this,cancelled)}) { "补扫完成，新增 $it 条" }
        else if(::status.isInitialized) updateStatus()
    }
    override fun onDestroy() { cancelled.set(true);worker.shutdownNow(); if(!busy) db.close(); super.onDestroy() }
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun text(value:String,size:Float=14f,bold:Boolean=false)=TextView(this).apply { text=value;textSize=size;setTextColor(Color.rgb(42,36,34));if(bold)setTypeface(null,Typeface.BOLD);setPadding(0,dp(4),0,dp(4)) }
    private fun button(label:String,action:()->Unit)=Button(this).apply { text=label;textSize=12f;isAllCaps=false;setOnClickListener { if(!busy) action() } }
    private fun column()=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(8),dp(16),dp(8))}
    private fun horizontal(vararg children:View)=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;children.forEach {addView(it,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))} }
    private fun buildUi() {
        val root=column().apply {setBackgroundColor(Color.rgb(255,249,242))}
        root.addView(text(if(demo) "中行短信账本 · 演示" else "中行短信账本",24f,true))
        root.addView(text("只在手机本地 · 按原币记账",12f))
        status=text("",12f);root.addView(status)
        root.addView(horizontal(button("历史导入") {importDialog()},button("补扫") {
            if(demo) toast("演示模式无需短信权限") else if(!SmsImport.hasRead(this)) requestSms() else if(!getSharedPreferences("import",MODE_PRIVATE).getBoolean("enabled",false)) importDialog() else work("增量补扫",{SmsImport.incremental(this,cancelled)}) {"补扫新增 $it 条"}
        },button("更多") {menu()}))
        accountFilter=Spinner(this);currencyFilter=Spinner(this);reviewFilter=Spinner(this)
        root.addView(horizontal(accountFilter,currencyFilter,reviewFilter))
        rangeButton=button("接收时间：全部") {filterRangeDialog()};root.addView(rangeButton)
        summary=text("",12f);root.addView(summary)
        list=ListView(this).apply {dividerHeight=dp(8);setBackgroundColor(Color.TRANSPARENT);setOnItemClickListener {_,_,position,_ -> detail(shown[position].id)}}
        root.addView(list,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f))
        setContentView(root)
        // API 35 enforces edge-to-edge; consume system bars so controls remain tappable.
        root.setOnApplyWindowInsetsListener { v,insets ->
            if(Build.VERSION.SDK_INT >= 30) {
                val bars=insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(dp(16)+bars.left,dp(8)+bars.top,dp(16)+bars.right,dp(8)+bars.bottom)
                WindowInsets.CONSUMED
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(dp(16)+insets.systemWindowInsetLeft,dp(8)+insets.systemWindowInsetTop,dp(16)+insets.systemWindowInsetRight,dp(8)+insets.systemWindowInsetBottom)
                @Suppress("DEPRECATION")
                insets.consumeSystemWindowInsets()
            }
        }
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
        rows=db.all();links=db.links();refreshing=true
        setChoices(accountFilter,listOf("全部账户")+rows.mapNotNull {it.effective?.account}.distinct().sorted())
        setChoices(currencyFilter,listOf("全部币种")+rows.mapNotNull {it.effective?.money?.currency}.distinct().sorted())
        setChoices(reviewFilter,listOf("全部记录","待确认","已排除","非交易"))
        refreshing=false;render();updateStatus()
    }
    private fun pending(row:StoredSms):Boolean = !row.excluded && (row.effective!=null || row.parsed.status!=ParseStatus.IGNORED) &&
        (!row.verified || row.effective==null || row.category==Category.PENDING && row.effective.kind !in listOf(Kind.CREDIT_REVERSAL,Kind.CREDIT_REFUND) || row.effective.kind in listOf(Kind.CREDIT_REVERSAL,Kind.CREDIT_REFUND) && links.none {it.adjustmentId==row.id})
    private fun render() {
        if(!::list.isInitialized) return
        val account=accountFilter.selectedItem?.toString();val currency=currencyFilter.selectedItem?.toString();val state=reviewFilter.selectedItemPosition
        shown=rows.filter { r ->
            val day=Instant.ofEpochMilli(r.received).atZone(zone).toLocalDate()
            (account==null || account=="全部账户" || r.effective?.account==account) &&
            (currency==null || currency=="全部币种" || r.effective?.money?.currency==currency) &&
            (from==null || day>=from) && (to==null || day<=to) &&
            when(state) {1->pending(r);2->r.excluded;3->r.effective==null && r.parsed.status==ParseStatus.IGNORED;else->true}
        }
        rangeButton.text=getString(R.string.received_range,from?.toString() ?: "不限",to?.toString() ?: "不限")
        val totals=Accounting.totals(rows.map(StoredSms::entry),links,shown.map {it.id}.toSet())
        summary.text=if(shown.isEmpty()) "暂无记录。可导入历史短信，或从「更多」打开合成演示。" else buildString {
            append("${shown.size} 条 · 待确认 ${shown.count(::pending)}\n")
            for((c,t) in totals) {
                append("$c  消费净额 ${t.net.toPlainString()}")
                if(t.confirmedOffset.signum()!=0) append("（确认冲减 ${t.confirmedOffset.toPlainString()}）")
                if(t.income.signum()!=0) append("  收入 ${t.income.toPlainString()}")
                if(t.unlinkedRefund.signum()!=0) append("  未关联退货 ${t.unlinkedRefund.toPlainString()}")
                if(t.unlinkedReversal.signum()!=0) append("  未关联撤销 ${t.unlinkedReversal.toPlainString()}")
                if(t.repayment.signum()!=0) append("  偿债 ${t.repayment.toPlainString()}")
                append('\n')
            }
            append("按消费归属统计；已确认冲减随原消费。待定用途不计收支，退货单列不计收入。")
        }
        list.adapter=object:BaseAdapter() {
            override fun getCount()=shown.size
            override fun getItem(position:Int)=shown[position]
            override fun getItemId(position:Int)=shown[position].id
            override fun getView(position:Int,convertView:View?,parent:ViewGroup?):View {
                val r=shown[position];val e=r.effective
                return column().apply {
                    setBackgroundColor(Color.WHITE)
                    addView(text(if(e==null) "#${r.id} ${r.parsed.status.label}" else "${e.kind.label}    ${e.money}",16f,true))
                    addView(text(if(e==null) r.parsed.reason else "${e.merchant ?: e.channel ?: "未提供商户"} · ${e.account}",13f))
                    addView(text("${e?.date?.toString() ?: "正文日期未知"}\n接收 ${received(r)} · ${r.category.label}"+(if(pending(r)) " · 待确认" else "")+(if(r.excluded) " · 已排除" else "")+(if(r.manual) " · 人工修正" else "")+(if(!r.verified) " · 来源未确认" else ""),12f))
                }
            }
        }
    }
    private fun received(r:StoredSms)=Instant.ofEpochMilli(r.received).atZone(zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    private fun updateStatus(message:String?=null) {
        status.text=message ?: if(demo) "合成样例与手机账本分库保存" else {
            val prefs=getSharedPreferences("import",MODE_PRIVATE)
            when {
                !SmsImport.hasRead(this)->"尚未授权读取短信；已存账目仍可查看"
                !prefs.getBoolean("enabled",false)->"已授权。选择历史范围并开始导入后启用新短信补扫"
                else->"本地补扫已启用 · 后台短信接收${if(checkSelfPermission(Manifest.permission.RECEIVE_SMS)==PackageManager.PERMISSION_GRANTED) "已授权" else "未授权"}"
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
            }
        }
    }
    private fun toast(message:String)=Toast.makeText(this,message,Toast.LENGTH_LONG).show()
    private fun safe(action:()->Unit) {try {action();reload()} catch(e:IllegalArgumentException) {toast(e.message ?: "输入无效")} catch(e:IllegalStateException) {toast(e.message ?: "操作无效")} }
    private fun requestSms() {
        AlertDialog.Builder(this).setTitle("读取本机银行短信").setMessage("仅筛选 95566 或带中国银行落款的短信并保存在本机；其他发送方需逐条确认。不会联网或修改短信。系统可能限制短信权限；拒绝后仍可查看已有账目与演示。")
            .setPositiveButton("申请权限") {_,_->requestPermissions(arrayOf(Manifest.permission.READ_SMS,Manifest.permission.RECEIVE_SMS),10)}.setNegativeButton("取消",null).show()
    }
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==10) {
            updateStatus()
            if(SmsImport.hasRead(this)) importDialog() else AlertDialog.Builder(this).setTitle("未获短信读取权限").setMessage("可以继续查看已存账本和合成演示。如系统不再弹出授权，请到应用设置检查权限。")
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
        val options=arrayOf(if(demo) "切回手机账本" else "打开合成演示","短信权限与设置","重新解析（保留人工修正）","停止后台补扫","使用说明")
        AlertDialog.Builder(this).setTitle("账本工具").setItems(options) {_,which->when(which) {
            0->{demo=!demo;db.close();db=LedgerDb(this,if(demo) "demo.db" else "ledger.db");from=null;to=null;buildUi();if(demo) loadDemo() else reload()}
            1->if(SmsImport.hasRead(this)) startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:$packageName"))) else requestSms()
            2->work("重新解析",{db.reparse();Unit}) {"已重新解析；人工修正保留，已关联记录冻结"}
            3->{getSharedPreferences("import",MODE_PRIVATE).edit().putBoolean("enabled",false).apply();getSystemService(android.app.job.JobScheduler::class.java).cancel(41);updateStatus("已停止补扫；已存账目保留，可重新选择范围导入")}
            4->AlertDialog.Builder(this).setTitle("本地账本 0.1.0").setMessage("仅覆盖仓库七类中行短信模板。\n\n资金流与经济分类分开：借记卡支付、转入、转出默认用途待确认，不直接计入消费或收入。信用卡本金还款只列偿债；人民币实付与外币偿债保存在同一事件内。\n\n撤销和退货须手动关联后冲减原消费；退款发生日与原消费日分别保留。未关联退款单列，不能当收入；汇总不是银行账单。\n\n广播只唤醒短信库补扫，分段拼接由系统短信库完成。后台限制或短信库延迟可能影响及时性；打开应用可重扫。来源号码与落款不能证明银行身份，请核对可疑短信。\n\n无网络权限，不上传数据，关闭系统备份。卸载将删除本地账本，当前未提供数据导出或备份。演示数据单独保存。\n\n购汇还款与独立借记卡扣款通知的跨短信关联尚未实现，默认用途待确认；不要将疑似本金扣款再次标为消费。未真机验收。") .setPositiveButton("知道了",null).show()
        }}.show()
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
                append("${e.account}\n正文日期：${e.date ?: "未知"}\n金额：${e.money}\n分类：${r.category.label}\n商户：${e.merchant ?: "未知"}\n渠道：${e.channel ?: "未知"}\n")
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
    private fun linkDialog(row:StoredSms) {
        val candidates=rows.filter {Accounting.validateLink(rows.map(StoredSms::entry),links,row.id,it.id)==null}
        if(candidates.isEmpty()) {toast("没有可关联消费；账户、币种须一致且累计冲减不能超额，来源须确认");return}
        val labels=candidates.map {"#${it.id} ${it.effective!!.date ?: "日期未知"} ${it.effective.merchant ?: "商户未知"} ${it.effective.money}"}.toTypedArray()
        AlertDialog.Builder(this).setTitle("请选择原消费（需自行核对）").setItems(labels) {_,i->
            val target=candidates[i]
            AlertDialog.Builder(this).setTitle("确认同一笔原交易").setMessage("将调整 #${row.id} ${row.effective!!.money} 关联到消费 #${target.id} ${target.effective!!.money}。同额或同日不足以证明关联，请核对原文。")
                .setPositiveButton("确认关联") {_,_->safe {db.link(row.id,target.id)}}.setNegativeButton("取消",null).show()
        }.setNegativeButton("取消",null).show()
    }
    private fun edit(row:StoredSms) {
        val old=row.effective;val box=column()
        box.addView(text("所有修正保留原文和操作历史。日期留空表示未知；不从接收时间补年。已有关联请先解除。"))
        fun input(label:String,value:String=""):EditText {box.addView(text(label,12f));return EditText(this).apply {setText(value);isSingleLine=true;box.addView(this)} }
        val kind=Spinner(this).apply {adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,Kind.entries.map {it.label});setSelection(old?.kind?.ordinal ?: 0)};box.addView(text("事件类型",12f));box.addView(kind)
        val tail=input("卡尾号（4位）",old?.tail.orEmpty())
        val currency=input("币种（CNY / USD / SGD / JPY）",old?.money?.currency ?: "CNY")
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
