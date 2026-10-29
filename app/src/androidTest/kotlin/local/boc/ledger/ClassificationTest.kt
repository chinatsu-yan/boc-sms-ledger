package local.boc.ledger
import android.test.AndroidTestCase
import android.database.sqlite.SQLiteDatabase
class ClassificationTest:AndroidTestCase() {
 fun testBatchManualPriorityRuleScopeAndReplay() {
  val name="classification-test.db";context.deleteDatabase(name)
  LedgerDb(context,name).use {db->
   val body="您的信用卡4826于2026年06月01日，在SYNTH_STORE消费USD20.00元。【中国银行】"
   db.ingest("old","95566",body,1000,"synthetic",true);val old=db.all().single().id
   db.saveRule(MerchantRule("SYNTH_STORE","合成商户","订阅"));db.applyRules();assertNull(db.labels()[old])
   db.ingest("new","95566",body,2000,"synthetic",true);val fresh=db.all().first().id;db.applyRules();assertEquals("订阅",db.labels()[fresh])
   db.addCategory("办公");db.classify(setOf(old,fresh),"办公");db.saveRule(MerchantRule("SYNTH_STORE","合成商户","购物"),true);db.applyRules();db.reparse();assertEquals(setOf("办公"),db.labels().values.toSet());assertTrue(db.history(old).any {it.contains("人工分类")})
   val before=db.labels();try {db.classify(setOf(old,99999),"餐饮");fail("invalid batch")} catch(_:IllegalArgumentException){};assertEquals(before,db.labels())
  };context.deleteDatabase(name)
 }
 fun testResetTransferToPendingDoesNotInventConsumption() {
  val name="pending-category-test.db";context.deleteDatabase(name)
  LedgerDb(context,name).use {db->db.ingest("transfer","95566","您的借记卡账户7304，于06月19日手机银行支取(跨行支付)人民币200.00元,交易后余额18776.00【中国银行】",1000,"test",true);val id=db.all().single().id;db.classify(setOf(id),"餐饮");assertEquals(Category.SPENDING,db.all().single().category);db.classify(setOf(id),"待分类");assertEquals(Category.PENDING,db.all().single().category);assertTrue(Spending.points(db.all().map {it.entry()},emptyList(),db.labels(),setOf(id)).isEmpty())};context.deleteDatabase(name)
 }
 fun testV1UpgradePreservesManualAndLinks() {
  val name="migration-v16.db";context.deleteDatabase(name)
  val path=context.getDatabasePath(name);path.parentFile!!.mkdirs()
  SQLiteDatabase.openOrCreateDatabase(path,null).use {d->
   d.execSQL("CREATE TABLE sms(id INTEGER PRIMARY KEY, source TEXT NOT NULL UNIQUE, sender TEXT NOT NULL, body TEXT NOT NULL, received INTEGER NOT NULL, sim TEXT NOT NULL, status TEXT NOT NULL, reason TEXT NOT NULL, event TEXT, parser_version INTEGER NOT NULL, manual_event TEXT, category TEXT, excluded INTEGER NOT NULL DEFAULT 0, verified INTEGER NOT NULL, manual INTEGER NOT NULL DEFAULT 0)")
   d.execSQL("CREATE TABLE links(adjustment INTEGER PRIMARY KEY REFERENCES sms(id), purchase INTEGER NOT NULL REFERENCES sms(id))")
   d.execSQL("CREATE TABLE audit(id INTEGER PRIMARY KEY, sms_id INTEGER NOT NULL REFERENCES sms(id), at INTEGER NOT NULL, action TEXT NOT NULL, before_value TEXT NOT NULL, after_value TEXT NOT NULL)")
   val event=BocParser.parse("您的信用卡4826于2026年06月01日，在SYNTH_STORE消费USD20.00元。【中国银行】").event!!
   for(i in 1..2) d.execSQL("INSERT INTO sms VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",arrayOf<Any>(i,"demo:$i","95566","synthetic",1000,"s","PARSED","",EventJson.encode(event),BocParser.VERSION,EventJson.encode(event),"SPENDING",0,1,1))
   d.execSQL("INSERT INTO links VALUES(2,1)");d.execSQL("INSERT INTO audit VALUES(1,1,1000,'人工修正','before','after')");d.version=1
  }
  LedgerDb(context,name).use {db->assertEquals(2,db.all().size);assertTrue(db.all().all {it.manual});assertEquals(listOf(Link(2,1)),db.links());assertTrue(db.history(1).single().contains("after"));assertEquals(6,db.categories().size);assertEquals(2,db.readableDatabase.version)}
  context.deleteDatabase(name)
 }
}
