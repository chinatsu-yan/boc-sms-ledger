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

    fun testVersionOneUnknownCurrencyRowsReparseWithoutLosingManualEdits() {
        val fixtures=context.assets.open("currency-variants.json").bufferedReader().use { org.json.JSONObject(it.readText()) }.getJSONArray("cases")
        LedgerDb(context,"smoke.db").use {db->
            for(i in 0 until fixtures.length()) {
                val c=fixtures.getJSONObject(i)
                assertTrue(db.ingest(c.getString("id"),"95566",c.getString("text"),1000L+i,"synthetic"))
            }
            // Persist exactly the unknown/event-null state produced by parser v1, then use the public replay entry.
            db.writableDatabase.execSQL("UPDATE sms SET status='NEEDS_REVIEW', reason='unsupported currency in v1', event=NULL, parser_version=1")
            val before=db.all()
            assertTrue(before.all {it.effective==null && it.parserVersion==1})
            val twd=before.first {it.body.contains("TWD")}
            val hkd=before.first {it.body.contains("HKD")}
            val corrected=BocParser.parse(twd.body).event!!.copy(money=Money("TWD",BigDecimal("2100.00")))
            db.correct(twd.id,Category.SPENDING,corrected)
            db.toggleExcluded(hkd.id)
            db.reparse()
            val after=db.all()
            assertEquals(before.map {it.id}.toSet(),after.map {it.id}.toSet())
            for(row in after) {
                val original=before.first {it.id==row.id}
                assertEquals(original.source,row.source)
                assertEquals(original.body,row.body)
                assertEquals(ParseStatus.PARSED,row.parsed.status)
                assertEquals(BocParser.VERSION,row.parserVersion)
                assertEquals(3,row.parserVersion)
            }
            val manual=after.first {it.id==twd.id}
            assertEquals("2360.00",manual.parsed.event!!.money.amount.toPlainString())
            assertEquals("2100.00",manual.effective!!.money.amount.toPlainString())
            assertTrue(manual.manual)
            assertEquals(Category.SPENDING,manual.category)
            val parsedHkd=after.first {it.id==hkd.id}
            assertEquals("HKD",parsedHkd.effective!!.money.currency)
            assertEquals("38.50",parsedHkd.effective.money.amount.toPlainString())
            assertTrue(parsedHkd.excluded)
            assertTrue(db.history(twd.id).any {it.contains("重解析 v${BocParser.VERSION}")})
            for(i in 0 until fixtures.length()) {
                val c=fixtures.getJSONObject(i)
                assertFalse(db.ingest(c.getString("id"),"95566",c.getString("text"),1000L+i,"synthetic"))
            }
            assertEquals(2,db.all().size)
        }
        LedgerDb(context,"smoke.db").use {db->
            assertEquals(2,db.all().size)
            assertEquals("2100.00",db.all().first {it.body.contains("TWD")}.effective!!.money.amount.toPlainString())
        }
    }

    fun testStandardCurrencyMatrixAndUnknownDraftOnAndroid() {
        val fixtures=context.assets.open("general-currencies.json").bufferedReader().use {org.json.JSONObject(it.readText())}
        val cases=fixtures.getJSONArray("cases")
        for(i in 0 until cases.length()) {
            val c=cases.getJSONObject(i);val expected=c.getJSONObject("expected")
            val result=BocParser.parse(c.getString("text"))
            assertEquals(c.getString("id"),ParseStatus.PARSED,result.status)
            val event=result.event!!
            assertEquals(expected.getString("currency"),event.money.currency)
            assertEquals(expected.getString("amount"),event.money.amount.toPlainString())
            assertTrue(event.currenciesRecognized)
        }
        val runtimeCurrencies=java.util.Currency.getAvailableCurrencies().filter {it.defaultFractionDigits>=0}
        assertTrue(runtimeCurrencies.size>100)
        for(currency in runtimeCurrencies) {
            val number=BigDecimal("1234").setScale(currency.defaultFractionDigits).toPlainString()
            val body="您的信用卡5555于2024年02月20日，在DEMOUSD消费${currency.currencyCode}${number}元。【中国银行】"
            val result=BocParser.parse(body)
            assertEquals(currency.currencyCode,ParseStatus.PARSED,result.status)
            val event=result.event!!
            assertEquals(currency.currencyCode,event.money.currency)
            assertEquals(number,event.money.amount.toPlainString())
        }
        println("Android currency entries with unit rules exercised: ${runtimeCurrencies.size}")
        val boundary=fixtures.getJSONArray("boundary_cases")
        for(i in 0 until boundary.length()) {
            val c=boundary.getJSONObject(i);val result=BocParser.parse(c.getString("text"))
            assertEquals(c.getString("id"),c.getString("expected_status").uppercase(),result.status.name)
            if(c.getBoolean("draft")) {
                val draft=result.event!!
                assertEquals(c.getString("currency"),draft.money.currency)
                assertEquals(c.getString("amount"),draft.money.amount.toPlainString())
                assertFalse(draft.currenciesRecognized)
                assertEquals(Category.PENDING,draft.defaultCategory)
            } else assertNull(result.event)
        }
        val body="您的信用卡5555于2024年02月20日，在DEMOUSD消费ZZZ12.3456元。【中国银行】"
        LedgerDb(context,"smoke.db").use {db->
            assertTrue(db.ingest("unknown-code","95566",body,1000,"synthetic"))
            val row=db.all().single()
            assertEquals(ParseStatus.NEEDS_REVIEW,row.parsed.status)
            assertEquals(Category.PENDING,row.category)
            assertEquals("ZZZ",row.effective!!.money.currency)
            assertEquals("12.3456",row.effective.money.amount.toPlainString())
            assertTrue(Accounting.totals(db.all().map(StoredSms::entry),emptyList()).isEmpty())
            try { db.correct(row.id,Category.SPENDING);fail("Unconfirmed currency cannot become spending") } catch(_:IllegalArgumentException) { }
            db.reparse()
            assertFalse(db.ingest("unknown-code","95566",body,1000,"synthetic"))
        }
        LedgerDb(context,"smoke.db").use {db->
            val row=db.all().single()
            assertEquals(body,row.body)
            assertEquals(ParseStatus.NEEDS_REVIEW,row.parsed.status)
            assertFalse(row.effective!!.currenciesRecognized)
            assertTrue(Accounting.totals(db.all().map(StoredSms::entry),emptyList()).isEmpty())
        }
    }
    fun testCurrencyConfirmationFlagPersistsAndOldJsonStillLoads() {
        val tentative=Event(Kind.CREDIT_PURCHASE,"5555",null,Money("EUR",BigDecimal("12.34"),"EUR",false))
        val encoded=EventJson.encode(tentative)
        val decoded=EventJson.decode(encoded)
        assertEquals("EUR",decoded.money.currency)
        assertEquals("12.34",decoded.money.amount.toPlainString())
        assertFalse(decoded.currenciesRecognized)
        assertEquals(Category.PENDING,decoded.defaultCategory)
        assertTrue(Accounting.totals(listOf(Entry(1,decoded,Category.SPENDING)),emptyList()).isEmpty())
        val old=org.json.JSONObject(encoded)
        old.getJSONObject("money").remove("currencyRecognized")
        val legacy=EventJson.decode(old.toString())
        assertTrue(legacy.currenciesRecognized)
        assertEquals("12.34",legacy.money.amount.toPlainString())
    }
    fun testVersionTwoUnknownEuroReparseKeepsEditsAndNonTransactions() {
        val body="您的信用卡5555于2024年02月20日，在DEMOEUR消费EUR1,234.50元。【中国银行】"
        val failed="您的信用卡5555于2024年02月20日，在DEMOEUR消费EUR12.50元失败。【中国银行】"
        LedgerDb(context,"smoke.db").use {db->
            db.ingest("eur-normal","95566",body,1000,"synthetic")
            db.ingest("eur-manual","95566",body,2000,"synthetic")
            db.ingest("eur-failed","95566",failed,3000,"synthetic")
            val before=db.all();val normal=before.first {it.received==1000L};val manual=before.first {it.received==2000L}
            db.writableDatabase.execSQL("UPDATE sms SET status='NEEDS_REVIEW', event=NULL, parser_version=2 WHERE id IN (?,?)",arrayOf(normal.id,manual.id))
            db.correct(manual.id,Category.SPENDING,BocParser.parse(body).event!!.copy(money=Money("EUR",BigDecimal("1000.00"))))
            db.toggleExcluded(normal.id)
            db.reparse()
            val rows=db.all()
            assertEquals(before.map {it.id}.toSet(),rows.map {it.id}.toSet())
            for(row in rows) {
                assertEquals(before.first {it.id==row.id}.source,row.source)
                assertEquals(BocParser.VERSION,row.parserVersion)
            }
            val ordinary=rows.first {it.id==normal.id};val corrected=rows.first {it.id==manual.id}
            assertEquals(ParseStatus.PARSED,ordinary.parsed.status)
            assertEquals("1234.50",ordinary.effective!!.money.amount.toPlainString())
            assertTrue(ordinary.excluded)
            assertEquals("1234.50",corrected.parsed.event!!.money.amount.toPlainString())
            assertEquals("1000.00",corrected.effective!!.money.amount.toPlainString())
            assertTrue(corrected.manual)
            assertEquals(ParseStatus.IGNORED,rows.first {it.received==3000L}.parsed.status)
            assertNull(rows.first {it.received==3000L}.effective)
            assertEquals(BigDecimal("1000.00"),Accounting.totals(rows.map(StoredSms::entry),emptyList())["EUR"]!!.net)
            assertTrue(db.history(manual.id).any {it.contains("重解析 v${BocParser.VERSION}")})
            assertFalse(db.ingest("eur-normal","95566",body,1000,"synthetic"))
            assertFalse(db.ingest("eur-manual","95566",body,2000,"synthetic"))
            assertFalse(db.ingest("eur-failed","95566",failed,3000,"synthetic"))
            assertEquals(3,db.all().size)
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
