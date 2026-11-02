package local.boc.ledger

import android.test.AndroidTestCase
import java.math.BigDecimal
import java.time.Instant

@Suppress("DEPRECATION")
class DebitIncomeUpgradeTest:AndroidTestCase() {
    private val name="debit-income-upgrade-test.db"
    private val body="您的借记卡账户8246，于08月17日收入(公积金)(提取托付)人民币720.00元，交易后余额3150.20【中国银行】"
    private val time=Instant.parse("2024-08-17T10:00:00Z").toEpochMilli()
    override fun setUp(){super.setUp();context.deleteDatabase(name)}
    override fun tearDown(){context.deleteDatabase(name);super.tearDown()}
    fun testUpgradeRecognizesSavedIncomeAndPreservesUserEditsAndLinks() {
        var target=0L;var manual=0L;var excluded=0L;var originalLinks=emptyList<Link>()
        LedgerDb(context,name).use {db->
            for(i in 0..2)db.ingest("income-$i","95566",body,time+i,"synthetic",true)
            val rows=db.all();target=rows.first{it.received==time}.id;manual=rows.first{it.received==time+1}.id;excluded=rows.first{it.received==time+2}.id
            val event=rows.first{it.id==manual}.effective!!
            db.correct(manual,Category.INCOME,event.copy(money=BocParser.money("CNY","999.00")))
            db.toggleExcluded(excluded)
            db.ingest("failed","95566",body+"交易失败",time+3,"synthetic",true)
            db.ingest("purchase","95566","您的信用卡9346于2024年08月17日，在SAMPLE消费USD20.00元。【中国银行】",time+4,"synthetic",true)
            db.ingest("refund","95566","您的信用卡9346于2024年08月18日成功退货USD20.00元。【中国银行】",time+5,"synthetic",true)
            db.link(db.all().first{it.received==time+5}.id,db.all().first{it.received==time+4}.id);originalLinks=db.links()
            db.writableDatabase.execSQL("UPDATE sms SET event=NULL,status='NEEDS_REVIEW',reason='old template',parser_version=3 WHERE id IN (?,?,?)",arrayOf(target,manual,excluded))
            db.writableDatabase.execSQL("DROP TABLE income_categories");db.writableDatabase.version=2
        }
        LedgerDb(context,name).use {db->
            val rows=db.all();val income=rows.first{it.id==target}
            assertEquals(6,rows.size);assertEquals(body,income.body);assertTrue(income.recognizedTransaction)
            assertEquals(Kind.DEBIT_TRANSFER_IN,income.effective!!.kind);assertEquals(2024,income.effective.date!!.year)
            assertEquals("公积金 / 提取托付",income.effective.channel)
            assertEquals(BigDecimal("720.00"),DebitDashboard.flows(rows.map(StoredSms::entry),setOf(target)).getValue("CNY").income)
            val edited=rows.first{it.id==manual};assertTrue(edited.manual);assertEquals(BigDecimal("999.00"),edited.effective!!.money.amount);assertEquals(Category.INCOME,edited.category)
            assertTrue(rows.first{it.id==excluded}.excluded);assertEquals(ParseStatus.NEEDS_REVIEW,rows.first{it.id==excluded}.parsed.status)
            assertEquals(ParseStatus.IGNORED,rows.first{it.received==time+3}.parsed.status);assertEquals(originalLinks,db.links())
            assertFalse(db.ingest("income-0","95566",body,time,"synthetic",true));assertEquals(1,db.history(target).size)
        }
        LedgerDb(context,name).use {assertEquals(1,it.history(target).size)}
    }
}
