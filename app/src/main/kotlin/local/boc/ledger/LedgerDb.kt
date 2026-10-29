package local.boc.ledger

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest

data class StoredSms(
    val id: Long, val source: String, val sender: String, val body: String, val received: Long, val sim: String,
    val parsed: ParseResult, val effective: Event?, val category: Category, val excluded: Boolean,
    val verified: Boolean, val manual: Boolean, val parserVersion: Int
) {
    fun entry() = Entry(id,effective,category,excluded,verified)
    val needsReview get() = !excluded && parsed.status!=ParseStatus.IGNORED && (!verified || effective==null || category==Category.PENDING || effective.kind in listOf(Kind.CREDIT_REVERSAL,Kind.CREDIT_REFUND))
}

class LedgerDb(context: Context, name: String = "ledger.db"): SQLiteOpenHelper(context,name,null,1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE sms(id INTEGER PRIMARY KEY, source TEXT NOT NULL UNIQUE, sender TEXT NOT NULL, body TEXT NOT NULL, received INTEGER NOT NULL, sim TEXT NOT NULL, status TEXT NOT NULL, reason TEXT NOT NULL, event TEXT, parser_version INTEGER NOT NULL, manual_event TEXT, category TEXT, excluded INTEGER NOT NULL DEFAULT 0, verified INTEGER NOT NULL, manual INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("CREATE TABLE links(adjustment INTEGER PRIMARY KEY REFERENCES sms(id), purchase INTEGER NOT NULL REFERENCES sms(id))")
        db.execSQL("CREATE TABLE audit(id INTEGER PRIMARY KEY, sms_id INTEGER NOT NULL REFERENCES sms(id), at INTEGER NOT NULL, action TEXT NOT NULL, before_value TEXT NOT NULL, after_value TEXT NOT NULL)")
        db.execSQL("CREATE INDEX received_index ON sms(received)")
    }
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { error("Unsupported database migration: $oldVersion -> $newVersion") }
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
                val event=optional("manual_event")?.let(EventJson::decode) ?: parsed.event
                add(StoredSms(n("id"),s("source"),s("sender"),s("body"),n("received"),s("sim"),parsed,event,optional("category")?.let(Category::valueOf) ?: event?.defaultCategory ?: Category.PENDING,n("excluded")==1L,n("verified")==1L,n("manual")==1L,n("parser_version").toInt()))
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
