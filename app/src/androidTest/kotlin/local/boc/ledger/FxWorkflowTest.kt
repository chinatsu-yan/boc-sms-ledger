package local.boc.ledger

import android.test.AndroidTestCase
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

@Suppress("DEPRECATION")
class FxWorkflowTest:AndroidTestCase() {
    override fun setUp(){super.setUp();context.deleteDatabase("fx-test.db");context.deleteDatabase("view-test.db")}
    override fun tearDown(){context.deleteDatabase("fx-test.db");context.deleteDatabase("view-test.db");super.tearDown()}
    private val time=Instant.parse("2024-02-20T12:00:00Z").toEpochMilli()
    fun testProviderEffectiveDateAndExactRateToken() {
        val key=QuoteKey("TWD","USD",LocalDate.parse("2024-02-18"))
        val raw="""{"date":"2024-02-18","base":"TWD","quote":"USD","rate":0.03,"providers":[{"key":"ECB","date":"2024-02-16","rate":0.03},{"key":"TEST","date":"2024-02-17","rate":0.03}]}"""
        val selection=PublicRateParser.select(raw,key,LocalDate.parse("2024-02-20"))
        assertEquals("TEST",selection.provider);assertEquals(LocalDate.parse("2024-02-17"),selection.date)
        val direct="""{"date":"2024-02-17","base":"TWD","quote":"USD","rate":0.03123456789123456789}"""
        val quote=PublicRateParser.direct(direct,key,selection,time)
        assertEquals("0.03123456789123456789",quote.rate.toPlainString())
        try {PublicRateParser.direct(direct.replace("0.03123456789123456789","0.03.1"),key,selection,time);fail("invalid numeric token")} catch(_:Exception){}
        try {PublicRateParser.direct(direct.replace("2024-02-17","2024-02-18"),key,selection,time);fail("wrong effective day")} catch(_:IllegalArgumentException){}
    }
    fun testCachingOfflineStalenessNegativeCacheAndFutureRequests() {
        var now=time;var requests=0;var offline=false
        val key=QuoteKey("USD","CNY",null)
        val transport=PublicRateTransport {path->
            requests++
            if(offline) throw java.io.IOException("synthetic offline")
            assertFalse(path.contains("5555"));assertFalse(path.contains("merchant"))
            if(path.startsWith("/v2/providers/")) """{"date":"2024-02-20","base":"USD","quote":"CNY","rate":7.123456789123456789}"""
            else """{"date":"2024-02-20","base":"USD","quote":"CNY","rate":7.1,"providers":[{"key":"ECB","date":"2024-02-20","rate":7.1}]}"""
        }
        FxCache(context,"fx-test.db").use {cache->
            val first=FxRepository(cache,transport,{now}).get(key) as RateResult.Available
            assertEquals(2,requests);assertFalse(first.quote.cached)
            val second=FxRepository(cache,transport,{now}).get(key) as RateResult.Available
            assertEquals(2,requests);assertTrue(second.quote.cached)
            assertEquals("7.123456789123456789",second.quote.rate.toPlainString())
            offline=true;now+=2*60*60*1000L
            val fallback=FxRepository(cache,transport,{now}).get(key) as RateResult.Available
            assertEquals(3,requests);assertTrue(fallback.quote.stale)
            assertEquals(LocalDate.parse("2024-02-20"),fallback.quote.date)
            FxRepository(cache,transport,{now},force=true).get(key)
            assertEquals(3,requests)
            assertTrue(FxRepository(cache,transport,{now},network=false).get(QuoteKey("EUR","USD",LocalDate.parse("2024-02-19"))) is RateResult.Missing)
            assertTrue(FxRepository(cache,transport,{now}).get(QuoteKey("EUR","USD",LocalDate.parse("2024-02-21"))) is RateResult.Missing)
            assertEquals(3,requests)
        }
        FxCache(context,"fx-test.db").use {cache->
            val stored=cache.load(key)!!
            assertEquals("7.123456789123456789",stored.quote!!.rate.toPlainString())
            assertNotNull(stored.error)
        }
    }
    fun testRecognizedViewsDoNotDeleteRawRowsOrGuessUnknownAccountKind() {
        val credit="您的信用卡5555于2024年02月20日，在DEMO消费USD2.00元。【中国银行】"
        val debit="您的借记卡账户6666，于02月20日网上支付支取人民币2.00元,交易后余额10.00【中国银行】"
        LedgerDb(context,"view-test.db").use {db->
            db.ingest("credit","95566",credit,1000,"synthetic")
            db.ingest("debit","95566",debit,2000,"synthetic")
            db.ingest("unknown","95566","未适配格式【中国银行】",3000,"synthetic")
            db.ingest("unverified","OTHER",credit,4000,"synthetic")
            db.ingest("draft","95566",credit.replace("USD","ZZZ"),5000,"synthetic")
            val rows=db.all();val visible=rows.filter {it.recognizedTransaction}
            assertEquals(5,rows.size);assertEquals(2,visible.size)
            assertEquals(1,visible.count {it.effective!!.accountKind==AccountKind.CREDIT})
            assertEquals(1,visible.count {it.effective!!.accountKind==AccountKind.DEBIT})
            assertNull(rows.first {it.received==3000L}.effective)
            db.toggleExcluded(visible.first {it.effective!!.accountKind==AccountKind.CREDIT}.id)
            assertEquals(1,db.all().count {it.recognizedTransaction})
            assertEquals(5,db.all().size)
        }
    }
}
