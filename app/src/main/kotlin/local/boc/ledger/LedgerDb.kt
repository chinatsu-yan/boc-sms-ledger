package local.boc.ledger

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest

data class MerchantRule(val merchant:String,val alias:String,val name:String)

data class StoredSms(
    val id: Long, val source: String, val sender: String, val body: String, val received: Long, val sim: String,
    val parsed: ParseResult, val effective: Event?, val category: Category, val excluded: Boolean,
    val verified: Boolean, val manual: Boolean, val parserVersion: Int, val dateInferred:Boolean=false
) {
    fun entry() = Entry(id,effective,category,excluded,verified)
    val recognizedTransaction get() = !excluded && verified && effective?.currenciesRecognized==true && (parsed.status==ParseStatus.PARSED || manual)
    val needsReview get() = !excluded && parsed.status!=ParseStatus.IGNORED && (!verified || effective==null || !effective.currenciesRecognized || category==Category.PENDING || effective.kind in listOf(Kind.CREDIT_REVERSAL,Kind.CREDIT_REFUND))
}

class LedgerDb(context: Context, name: String = "ledger.db"): SQLiteOpenHelper(context,name,null,4), java.io.Closeable {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE sms(id INTEGER PRIMARY KEY, source TEXT NOT NULL UNIQUE, sender TEXT NOT NULL, body TEXT NOT NULL, received INTEGER NOT NULL, sim TEXT NOT NULL, status TEXT NOT NULL, reason TEXT NOT NULL, event TEXT, parser_version INTEGER NOT NULL, manual_event TEXT, category TEXT, excluded INTEGER NOT NULL DEFAULT 0, verified INTEGER NOT NULL, manual INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("CREATE TABLE links(adjustment INTEGER PRIMARY KEY REFERENCES sms(id), purchase INTEGER NOT NULL REFERENCES sms(id))")
        db.execSQL("CREATE TABLE audit(id INTEGER PRIMARY KEY, sms_id INTEGER NOT NULL REFERENCES sms(id), at INTEGER NOT NULL, action TEXT NOT NULL, before_value TEXT NOT NULL, after_value TEXT NOT NULL)")
        db.execSQL("CREATE INDEX received_index ON sms(received)")
        createClassification(db)
        createIncomeCategories(db)
    }
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if(oldVersion<2) createClassification(db)
        if(oldVersion<3) refreshUnrecognizedDebitIncome(db)
        if(oldVersion<4) createIncomeCategories(db)
    }
    private fun createIncomeCategories(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE income_categories(name TEXT PRIMARY KEY)")
        listOf("工资","奖金","公积金","利息","其他收入").forEach {db.execSQL("INSERT INTO income_categories VALUES(?)",arrayOf(it))}
    }
    private fun refreshUnrecognizedDebitIncome(db:SQLiteDatabase) {
        db.rawQuery("SELECT id,body,status FROM sms WHERE status=? AND manual=0 AND excluded=0 AND parser_version<4 AND body LIKE ? AND id NOT IN (SELECT adjustment FROM links UNION SELECT purchase FROM links)",arrayOf(ParseStatus.NEEDS_REVIEW.name,"您的借记卡账户%收入%" )).use {c->
            while(c.moveToNext()) {
                val p=BocParser.parse(c.getString(1))
                val parsedEvent=p.event
                if(p.status!=ParseStatus.PARSED || parsedEvent?.kind!=Kind.DEBIT_TRANSFER_IN) continue
                val event=EventJson.encode(parsedEvent)
                db.update("sms",ContentValues().apply {put("status",p.status.name);put("reason",p.reason);put("event",event);put("parser_version",BocParser.VERSION)},"id=?",arrayOf(c.getLong(0).toString()))
                db.insertOrThrow("audit",null,ContentValues().apply {put("sms_id",c.getLong(0));put("at",System.currentTimeMillis());put("action","升级识别借记卡收入 v4");put("before_value",c.getString(2));put("after_value",event)})
            }
        }
    }
    private fun createClassification(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE expense_categories(name TEXT PRIMARY KEY)")
        listOf("餐饮","购物","交通","日用","订阅","其他").forEach {db.execSQL("INSERT INTO expense_categories VALUES(?)",arrayOf(it))}
        db.execSQL("CREATE TABLE expense_labels(sms_id INTEGER PRIMARY KEY REFERENCES sms(id), name TEXT NOT NULL, manual INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE merchant_rules(merchant TEXT PRIMARY KEY, alias TEXT NOT NULL, name TEXT NOT NULL, start_id INTEGER NOT NULL)")
    }
    fun categories(income:Boolean=false):List<String> = readableDatabase.rawQuery("SELECT name FROM ${if(income) "income_categories" else "expense_categories"} ORDER BY rowid",null).use {c->buildList {while(c.moveToNext()) add(c.getString(0))}}
    fun addCategory(name:String,income:Boolean=false) {require(name.trim().length in 1..16 && name.trim() !in listOf("待分类","转账","还款","收入"));writableDatabase.execSQL("INSERT OR IGNORE INTO ${if(income) "income_categories" else "expense_categories"} VALUES(?)",arrayOf(name.trim()))}
    fun labels():Map<Long,String> = readableDatabase.rawQuery("SELECT sms_id,name FROM expense_labels",null).use {c->buildMap {while(c.moveToNext()) put(c.getLong(0),c.getString(1))}}
    fun rules():List<MerchantRule> = readableDatabase.rawQuery("SELECT merchant,alias,name FROM merchant_rules ORDER BY merchant",null).use {c->buildList {while(c.moveToNext()) add(MerchantRule(c.getString(0),c.getString(1),c.getString(2)))}}
    fun saveRule(rule:MerchantRule,applyExisting:Boolean=false) {require(rule.merchant.isNotBlank() && rule.alias.length<=40 && rule.name in categories());writableDatabase.execSQL("INSERT OR REPLACE INTO merchant_rules VALUES(?,?,?,?)",arrayOf<Any>(rule.merchant.trim(),rule.alias.trim(),rule.name,if(applyExisting) 0L else (all().maxOfOrNull {it.id} ?: 0L)+1))}
    fun deleteRule(merchant:String) {writableDatabase.delete("merchant_rules","merchant=?",arrayOf(merchant))}
    fun classify(ids:Set<Long>,name:String) = transaction {
        val selected=all().filter {it.id in ids};require(selected.size==ids.size && selected.all {it.recognizedTransaction})
        val income=selected.all {it.effective!!.kind==Kind.DEBIT_TRANSFER_IN}
        require(income || selected.none {it.effective!!.kind==Kind.DEBIT_TRANSFER_IN}) {"收入和支出请分别批量分类"}
        require(name in categories(income)+if(income) listOf("待分类","转账") else listOf("待分类","转账","还款"))
        selected.forEach {row->
            val e=row.effective!!;require(e.kind in listOf(Kind.DEBIT_TRANSFER_IN,Kind.DEBIT_PAYMENT,Kind.DEBIT_TRANSFER_OUT,Kind.CREDIT_PURCHASE)) {"此记录请通过字段修正处理用途"}
            require(e.accountKind!=AccountKind.CREDIT || name !in listOf("转账","还款")) {"信用卡消费不可标为转账或还款"}
            val old=labels()[row.id] ?: "待分类"
            writableDatabase.execSQL("INSERT OR REPLACE INTO expense_labels VALUES(?,?,1)",arrayOf<Any>(row.id,name))
            val cat=when(name){"转账"->Category.TRANSFER;"还款"->Category.REPAYMENT;"待分类"->e.defaultCategory;else->if(income) Category.INCOME else Category.SPENDING}
            writableDatabase.update("sms",ContentValues().apply {put("category",cat.name)},"id=?",arrayOf(row.id.toString()))
            audit(row.id,"人工分类",old+" / "+row.category.name,name+" / "+cat.name)
        }
    }
    fun applyRules() = transaction {
        val mapping=rules().associateBy {it.merchant}
        val starts=readableDatabase.rawQuery("SELECT merchant,start_id FROM merchant_rules",null).use {c->buildMap {while(c.moveToNext()) put(c.getString(0),c.getLong(1))}}
        val assigned=readableDatabase.rawQuery("SELECT sms_id FROM expense_labels",null).use {c->buildSet {while(c.moveToNext()) add(c.getLong(0))}}
        all().filter {it.recognizedTransaction && !it.manual && it.id !in assigned && it.category !in listOf(Category.TRANSFER,Category.REPAYMENT,Category.OTHER)}.forEach {row->
            val e=row.effective!!;val r=mapping[e.merchant] ?: return@forEach
            if(row.id<(starts[r.merchant] ?: Long.MAX_VALUE)) return@forEach
            if(e.kind !in listOf(Kind.DEBIT_PAYMENT,Kind.CREDIT_PURCHASE)) return@forEach
            writableDatabase.execSQL("INSERT INTO expense_labels VALUES(?,?,0)",arrayOf<Any>(row.id,r.name))
            writableDatabase.update("sms",ContentValues().apply {put("category",Category.SPENDING.name)},"id=?",arrayOf(row.id.toString()))
            audit(row.id,"商户默认分类","待分类",r.name)
        }
    }
    fun ingest(providerId: String, sender: String, body: String, received: Long, sim: String, demo: Boolean = false): Boolean {
        val source=(if(demo) "demo:" else "inbox:") + sourceIdentity(providerId,sender,body,received,sim)
        val parsed=BocParser.parse(body)
        return writableDatabase.insertWithOnConflict("sms",null,ContentValues().apply {
            put("source",source); put("sender",sender); put("body",body); put("received",received); put("sim",sim)
            put("status",parsed.status.name); put("reason",parsed.reason); put("event",parsed.event?.let(EventJson::encode))
            put("parser_version",BocParser.VERSION); put("verified",if(sender=="95566" || demo) 1 else 0)
        },SQLiteDatabase.CONFLICT_IGNORE)!=-1L
    }
    fun all(): List<StoredSms> = readableDatabase.query("sms",null,null,null,null,null,"received DESC, id DESC").use { c ->
        buildList {
            fun s(name:String)=c.getString(c.getColumnIndexOrThrow(name))
            fun n(name:String)=c.getLong(c.getColumnIndexOrThrow(name))
            fun optional(name:String)=if(c.isNull(c.getColumnIndexOrThrow(name))) null else s(name)
            while(c.moveToNext()) {
                val parsed=ParseResult(ParseStatus.valueOf(s("status")),optional("event")?.let(EventJson::decode),s("reason"))
                val manualEvent=optional("manual_event")?.let(EventJson::decode)
                val inferred=if(manualEvent==null) SmsDate.resolve(parsed.event?.date,n("received")) else manualEvent.date
                val event=manualEvent ?: parsed.event?.copy(date=inferred)
                val dateInferred=manualEvent==null && parsed.event?.date?.year==null && inferred?.year!=null
                add(StoredSms(n("id"),s("source"),s("sender"),s("body"),n("received"),s("sim"),parsed,event,optional("category")?.let(Category::valueOf) ?: event?.defaultCategory ?: Category.PENDING,n("excluded")==1L,n("verified")==1L,n("manual")==1L,n("parser_version").toInt(),dateInferred))
            }
        }
    }
    fun links(): List<Link> = readableDatabase.rawQuery("SELECT adjustment,purchase FROM links",null).use { c -> buildList { while(c.moveToNext()) add(Link(c.getLong(0),c.getLong(1))) } }
    private fun audit(id:Long, action:String, before:String, after:String) {
        writableDatabase.insertOrThrow("audit",null,ContentValues().apply { put("sms_id",id);put("at",System.currentTimeMillis());put("action",action);put("before_value",before);put("after_value",after) })
    }
    fun history(id:Long): List<String> = readableDatabase.rawQuery("SELECT at,action,before_value,after_value FROM audit WHERE sms_id=? ORDER BY id",arrayOf(id.toString())).use { c -> buildList { while(c.moveToNext()) add("${java.time.Instant.ofEpochMilli(c.getLong(0))}\n${c.getString(1)}\n${c.getString(2)} → ${c.getString(3)}") } }
    private fun transaction(block: ()->Unit) { val db=writableDatabase; db.beginTransaction(); try { block(); db.setTransactionSuccessful() } finally { db.endTransaction() } }
    fun correct(id: Long, category: Category, event: Event? = null) = transaction {
        val row=all().first { it.id==id }
        require(links().none { it.adjustmentId==id || it.purchaseId==id }) { "请先解除与本条有关的关联" }
        val e=event ?: row.effective ?: error("请先补全事件字段")
        require(e.currenciesRecognized) { "本机货币资料未能确认币种或单位规则，请核对代码；原记录仍保留待确认" }
        require(category in allowedCategories(e.kind)) { "此事件类型不支持该分类" }
        writableDatabase.update("sms",ContentValues().apply { put("category",category.name); put("manual",1); if(event!=null) put("manual_event",EventJson.encode(event)) },"id=?",arrayOf(id.toString()))
        audit(id,"人工修正", "${row.category.name}: ${row.effective?.let(EventJson::encode)}","${category.name}: ${EventJson.encode(e)}")
    }
    fun restoreCorrection(id:Long) = transaction {
        require(links().none { it.adjustmentId==id || it.purchaseId==id }) { "请先解除关联" }
        val row=all().first { it.id==id }
        writableDatabase.update("sms",ContentValues().apply { putNull("manual_event");putNull("category");put("manual",0) },"id=?",arrayOf(id.toString()))
        audit(id,"恢复解析结果",row.effective?.let(EventJson::encode) ?: "无事件",row.parsed.event?.let(EventJson::encode) ?: "无事件")
    }
    fun toggleExcluded(id:Long) = transaction {
        require(links().none { it.adjustmentId==id || it.purchaseId==id }) { "请先解除关联" }
        val old=all().first { it.id==id }.excluded
        writableDatabase.update("sms",ContentValues().apply {put("excluded",if(old) 0 else 1)},"id=?",arrayOf(id.toString()))
        audit(id,"排除/恢复统计",old.toString(),(!old).toString())
    }
    fun verify(id:Long) = transaction {
        val row=all().first { it.id==id }
        writableDatabase.update("sms",ContentValues().apply {put("verified",1)},"id=?",arrayOf(id.toString()))
        audit(id,"人工确认本条来源",row.sender,"用户确认")
    }
    fun link(adjustment:Long,purchase:Long) = transaction {
        val rows=all()
        require(rows.first { it.id==adjustment }.source.startsWith("demo:")==rows.first { it.id==purchase }.source.startsWith("demo:")) { "演示数据不能关联手机数据" }
        require(rows.first {it.id==adjustment}.sim==rows.first {it.id==purchase}.sim) {"SIM来源不一致"}
        val problem=Accounting.validateLink(rows.map(StoredSms::entry),links(),adjustment,purchase)
        require(problem==null) { problem!! }
        writableDatabase.insertOrThrow("links",null,ContentValues().apply {put("adjustment",adjustment);put("purchase",purchase)})
        audit(adjustment,"确认关联","无","消费 #$purchase")
    }
    fun unlink(adjustment:Long) = transaction {
        val old=links().first { it.adjustmentId==adjustment }
        writableDatabase.delete("links","adjustment=?",arrayOf(adjustment.toString()))
        audit(adjustment,"解除关联","消费 #${old.purchaseId}","无")
    }
    fun autoAssociate() = transaction {
        val rows=all()
        val undone=readableDatabase.rawQuery("SELECT DISTINCT sms_id FROM audit WHERE action=?",arrayOf("解除关联")).use {c->buildSet {while(c.moveToNext()) add(c.getLong(0))}}
        val blocked=undone+rows.filter {it.manual || !it.recognizedTransaction}.map {it.id}
        for(link in rows.groupBy {it.source.startsWith("demo:") to it.sim}.values.flatMap {group->AutoAssociation.propose(group.map(StoredSms::entry),links(),blocked)}) {
            writableDatabase.insertOrThrow("links",null,ContentValues().apply {put("adjustment",link.adjustmentId);put("purchase",link.purchaseId)})
            audit(link.adjustmentId,"自动关联：同账户币种、同商户同金额、最早可冲销消费","无","消费 #${link.purchaseId}")
        }
    }
    fun reparse() = transaction {
        val frozen = links().flatMap { listOf(it.adjustmentId,it.purchaseId) }.toSet()
        for(row in all()) {
            val p=BocParser.parse(row.body)
            // Linked evidence is frozen until the user explicitly unlinks it. Manual overlays always survive replay.
            if(row.id in frozen) continue
            writableDatabase.update("sms",ContentValues().apply {put("status",p.status.name);put("reason",p.reason);put("event",p.event?.let(EventJson::encode));put("parser_version",BocParser.VERSION)},"id=?",arrayOf(row.id.toString()))
            audit(row.id,"重解析 v${BocParser.VERSION}",row.parsed.event?.let(EventJson::encode) ?: row.parsed.status.name,p.event?.let(EventJson::encode) ?: p.status.name)
        }
    }
    companion object {
        fun sourceIdentity(providerId:String,sender:String,body:String,received:Long,sim:String):String {
            // Include lengths to avoid delimiter ambiguity; independent provider rows are never collapsed by body hash.
            val input=listOf(providerId,sender,body,received.toString(),sim).joinToString("") { "${it.length}:$it" }
            return MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        fun allowedCategories(kind:Kind) = when(kind) {
            Kind.CREDIT_PURCHASE -> listOf(Category.SPENDING)
            Kind.CREDIT_REVERSAL,Kind.CREDIT_REFUND -> listOf(Category.PENDING)
            Kind.CREDIT_FX_REPAYMENT -> listOf(Category.REPAYMENT)
            Kind.DEBIT_TRANSFER_IN -> listOf(Category.PENDING,Category.INCOME,Category.TRANSFER,Category.OTHER)
            else -> listOf(Category.PENDING,Category.SPENDING,Category.TRANSFER,Category.REPAYMENT,Category.OTHER)
        }
    }
}
