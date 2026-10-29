package local.boc.ledger

import android.test.AndroidTestCase
import java.math.BigDecimal

@Suppress("DEPRECATION")
class DeviceSmokeTest:AndroidTestCase() {
    private val purchase="您的信用卡2222于2026年06月10日，在DB_DEMO消费SGD2.50元。【中国银行】"
    private val reversal="您的信用卡2222于2026年06月10日，在DB_DEMO撤销SGD2.50元。【中国银行】"
    private val unknown="您的借记卡账户1111收到未知交易通知【中国银行】"
    override fun setUp() {super.setUp();context.deleteDatabase("smoke.db")}
    override fun tearDown() {context.deleteDatabase("smoke.db");super.tearDown()}
    fun testIdempotencySeparateRowsAndPersistence() {
        LedgerDb(context,"smoke.db").use {db->
            assertTrue(db.ingest("7","95566",purchase,1000,"1"))
            assertFalse(db.ingest("7","95566",purchase,1000,"1"))
            assertTrue(db.ingest("8","95566",purchase,1000,"1"))
            assertEquals(2,db.all().size)
        }
        LedgerDb(context,"smoke.db").use {db->assertEquals(2,db.all().size)}
    }
    fun testLinksManualOverlayReplayAndAudit() {
        LedgerDb(context,"smoke.db").use {db->
            db.ingest("1","95566",purchase,1000,"1");db.ingest("2","95566",reversal,2000,"1")
            val rows=db.all();val a=rows.first {it.effective?.kind==Kind.CREDIT_REVERSAL}.id;val p=rows.first {it.effective?.kind==Kind.CREDIT_PURCHASE}.id
            db.link(a,p)
            assertEquals(BigDecimal("0.00"),Accounting.totals(db.all().map(StoredSms::entry),db.links())["SGD"]!!.net)
            try {db.correct(p,Category.SPENDING);fail("Linked evidence must be frozen")} catch(_:IllegalArgumentException) { }
            db.unlink(a)
            assertEquals(BigDecimal("2.50"),Accounting.totals(db.all().map(StoredSms::entry),db.links())["SGD"]!!.net)
            val replacement=rows.first {it.id==p}.effective!!.copy(money=Money("SGD",BigDecimal("2.25")))
            db.correct(p,Category.SPENDING,replacement);db.reparse()
            assertEquals(BigDecimal("2.25"),db.all().first {it.id==p}.effective!!.money.amount)
            db.restoreCorrection(p)
            assertEquals(BigDecimal("2.50"),db.all().first {it.id==p}.effective!!.money.amount)
            assertTrue(db.history(a).any {it.contains("解除关联")})
        }
    }
    fun testUnrecognizedSenderAndCorrectionsAreVisible() {
        LedgerDb(context,"smoke.db").use {db->
            db.ingest("3","UNKNOWN",unknown,3000,"2")
            val row=db.all().single()
            assertEquals(ParseStatus.NEEDS_REVIEW,row.parsed.status);assertFalse(row.verified)
            val manual=Event(Kind.DEBIT_TRANSFER_IN,"1111",null,Money("CNY",BigDecimal("100.00")))
            db.correct(row.id,Category.INCOME,manual)
            assertTrue(Accounting.totals(db.all().map(StoredSms::entry),emptyList()).isEmpty())
            db.verify(row.id)
            assertEquals(BigDecimal("100.00"),Accounting.totals(db.all().map(StoredSms::entry),emptyList())["CNY"]!!.income)
            db.toggleExcluded(row.id)
            assertTrue(Accounting.totals(db.all().map(StoredSms::entry),emptyList()).isEmpty())
            db.toggleExcluded(row.id);db.reparse()
            assertEquals("100.00",db.all().single().effective!!.money.amount.toPlainString())
            assertEquals(unknown,db.all().single().body)
        }
    }
    fun testInboxBroadcastIdentityAndCandidateBoundary() {
        assertEquals(LedgerDb.sourceIdentity("1","95566",purchase,1000,"1"),LedgerDb.sourceIdentity("1","95566",purchase,1000,"1"))
        assertFalse(LedgerDb.sourceIdentity("1","95566",purchase,1000,"1")==LedgerDb.sourceIdentity("2","95566",purchase,1000,"1"))
        assertTrue(SmsImport.candidate("95566","未知通知"))
        assertTrue(SmsImport.candidate("UNKNOWN",unknown))
        assertFalse(SmsImport.candidate("95588","其他银行通知"))
    }
}
