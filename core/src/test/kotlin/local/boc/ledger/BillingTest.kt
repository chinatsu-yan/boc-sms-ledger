package local.boc.ledger

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class BillingTest {
    @Test fun yenReferenceRefundThenUsdSumAndNativeYuanRemainSeparate() {
        val today=LocalDate.of(2024,2,20)
        val rows=listOf(entry(1,Kind.CREDIT_PURCHASE,"2024-02-18","JPY","10000"),entry(2,Kind.CREDIT_REFUND,"2024-02-19","JPY","2000",Category.PENDING),entry(3,Kind.CREDIT_PURCHASE,"2024-02-18","USD","20"),entry(4,Kind.CREDIT_PURCHASE,"2024-02-18","CNY","300"))
        val result=BillingEstimator.estimate(rows,listOf(Link(2,1)),BillingCycle.containing(today,25),today,RateLookup {key->
            assertTrue(key==QuoteKey("JPY","USD",LocalDate.of(2024,2,18)) || key==QuoteKey("USD","CNY",null))
            RateResult.Available(FxQuote(key.base,key.quote,if(key.date==null)today else LocalDate.of(2024,2,16),BigDecimal(if(key.base=="JPY")"0.00625" else "7.2"),"synthetic",1))
        })
        assertEquals(0,BigDecimal("70").compareTo(result.usdSubtotal));assertEquals(0,BigDecimal("504").compareTo(result.cnySubtotal));assertEquals(BigDecimal("300"),result.nativeCnySubtotal);assertTrue(result.complete)
    }
    private fun date(s:String)=LocalDate.parse(s)
    private fun entry(id:Long,kind:Kind,day:String?,currency:String="EUR",amount:String="10.00",category:Category=Category.SPENDING):Entry {
        val d=day?.let(::date)
        val e=Event(kind,if(kind.name.startsWith("DEBIT")) "6666" else "5555",d?.let {BodyDate(it.year,it.monthValue,it.dayOfMonth)},BocParser.money(currency,amount))
        return Entry(id,e,category)
    }
    @Test fun closingDayBelongsToEndingCycleAndShortMonthsClampToLastDay() {
        assertEquals(BillingCycle(date("2024-01-11"),date("2024-02-10")),BillingCycle.containing(date("2024-02-10"),10))
        assertEquals(BillingCycle(date("2024-02-11"),date("2024-03-10")),BillingCycle.containing(date("2024-02-11"),10))
        assertEquals(BillingCycle(date("2024-02-01"),date("2024-02-29")),BillingCycle.containing(date("2024-02-29"),31))
        assertEquals(BillingCycle(date("2023-02-01"),date("2023-02-28")),BillingCycle.containing(date("2023-02-28"),31))
        assertEquals(BillingCycle(date("2024-03-01"),date("2024-03-31")),BillingCycle.containing(date("2024-03-01"),31))
        assertEquals(BillingCycle(date("2023-12-11"),date("2024-01-10")),BillingCycle.containing(date("2024-02-10"),10,-1))
    }
    @Test fun historicalToUsdThenLatestToCnyKeepsThreeDigitPrecision() {
        val day=date("2024-02-20");val cycle=BillingCycle.containing(day,25)
        val rows=listOf(entry(1,Kind.CREDIT_PURCHASE,"2024-02-19","KWD","1.234"),entry(2,Kind.CREDIT_PURCHASE,"2024-02-20","CNY","14.00"),entry(3,Kind.CREDIT_PURCHASE,"2024-02-20","USD","2.50"))
        val seen=mutableListOf<QuoteKey>()
        val rates=RateLookup {key->seen+=key;RateResult.Available(FxQuote(key.base,key.quote,key.date ?: day,BigDecimal(when(key.base){"KWD"->"3.25";"CNY"->"0.142857";else->"7.20"}),"TEST",1))}
        val result=BillingEstimator.estimate(rows,emptyList(),cycle,day,rates)
        val usd=BigDecimal("1.234")*BigDecimal("3.25")+BigDecimal("2.50")
        assertEquals(usd,result.usdSubtotal);assertEquals(usd*BigDecimal("7.20"),result.cnySubtotal)
        assertTrue(result.complete);assertEquals(2,result.covered);assertEquals(BigDecimal("14.00"),result.nativeCnySubtotal)
        assertTrue(seen.contains(QuoteKey("KWD","USD",date("2024-02-19"))))
        assertTrue(seen.contains(QuoteKey("USD","CNY",null)))
        assertEquals(2,seen.size);assertFalse(seen.any {it.base=="CNY"})
    }
    @Test fun linkedRefundAndReversalOffsetOriginalOnceAndRepaymentIsExcluded() {
        val today=date("2024-02-20");val cycle=BillingCycle.containing(today,25)
        val rows=listOf(entry(1,Kind.CREDIT_PURCHASE,"2024-02-10"),entry(2,Kind.CREDIT_REVERSAL,"2024-02-11",amount="2.00",category=Category.PENDING),entry(3,Kind.CREDIT_REFUND,"2024-02-20",amount="3.00",category=Category.PENDING),entry(4,Kind.CREDIT_FX_REPAYMENT,"2024-02-15",amount="100.00",category=Category.REPAYMENT),entry(5,Kind.DEBIT_PAYMENT,"2024-02-15",amount="100.00"))
        val rates=RateLookup {key->RateResult.Available(FxQuote(key.base,key.quote,key.date ?: today,BigDecimal(if(key.base=="EUR") "2" else "7"),"TEST",1))}
        val result=BillingEstimator.estimate(rows,listOf(Link(2,1),Link(3,1)),cycle,today,rates)
        assertEquals(1,result.lines.size)
        assertEquals(BigDecimal("5.00"),result.lines.single().netAmount)
        assertEquals(BigDecimal("10.00"),result.usdSubtotal)
        assertEquals(BigDecimal("70.00"),result.cnySubtotal)
        val later=BillingEstimator.estimate(rows,listOf(Link(2,1),Link(3,1)),BillingCycle.containing(date("2024-03-20"),25),date("2024-03-20"),rates)
        assertTrue(later.lines.isEmpty());assertEquals(0,later.unlinkedAdjustments)
    }
    @Test fun missingFutureAndUndatedDataNeverBecomeZeroOrUseLatestForHistory() {
        val today=date("2024-02-20");val cycle=BillingCycle.containing(today,25)
        val rows=listOf(entry(1,Kind.CREDIT_PURCHASE,"2024-02-19"),entry(2,Kind.CREDIT_PURCHASE,"2024-02-21"),entry(3,Kind.CREDIT_PURCHASE,null),entry(4,Kind.CREDIT_REFUND,"2024-02-19",category=Category.PENDING))
        val calls=mutableListOf<QuoteKey>()
        val result=BillingEstimator.estimate(rows,emptyList(),cycle,today,RateLookup {calls+=it;RateResult.Missing("offline")})
        assertNull(result.usdSubtotal);assertNull(result.cnySubtotal)
        assertEquals(2,result.missing);assertEquals(1,result.undated);assertEquals(1,result.unlinkedAdjustments)
        assertFalse(result.complete);assertEquals(listOf(QuoteKey("EUR","USD",date("2024-02-19"))),calls)
    }
    @Test fun priorEffectiveDayIsAcceptedAndFutureOrOldOrWrongDirectionIsRejected() {
        val today=date("2024-02-20");val cycle=BillingCycle.containing(today,25);val rows=listOf(entry(1,Kind.CREDIT_PURCHASE,"2024-02-18"))
        fun estimate(actualDate:LocalDate,wrong:Boolean=false)=BillingEstimator.estimate(rows,emptyList(),cycle,today,RateLookup {k->RateResult.Available(FxQuote(if(wrong) k.quote else k.base,if(wrong) k.base else k.quote,if(k.date==null) today else actualDate,BigDecimal.ONE,"TEST",1))})
        assertTrue(estimate(date("2024-02-16")).complete)
        assertEquals(date("2024-02-16"),estimate(date("2024-02-16")).lines.single().quote!!.date)
        assertNull(estimate(date("2024-02-19")).usdSubtotal)
        assertNull(estimate(date("2024-01-01")).usdSubtotal)
        assertNull(estimate(date("2024-02-18"),true).usdSubtotal)
    }
    @Test fun futureAndUndatedLinkedAdjustmentsRemainPending() {
        val today=date("2024-02-20");val cycle=BillingCycle.containing(today,25)
        val rows=listOf(entry(1,Kind.CREDIT_PURCHASE,"2024-02-10"),entry(2,Kind.CREDIT_REFUND,"2024-02-21",amount="2.00",category=Category.PENDING),entry(3,Kind.CREDIT_REVERSAL,null,amount="3.00",category=Category.PENDING))
        val result=BillingEstimator.estimate(rows,listOf(Link(2,1),Link(3,1)),cycle,today,RateLookup {k->RateResult.Available(FxQuote(k.base,k.quote,k.date ?: today,BigDecimal.ONE,"TEST",1))})
        assertEquals(BigDecimal("10.00"),result.usdSubtotal)
        assertEquals(2,result.pendingAdjustments);assertFalse(result.complete)
    }
    @Test fun zeroNetNeedsNoQuotesButPartialCoverageIsExplicit() {
        val today=date("2024-02-20");val cycle=BillingCycle.containing(today,25)
        val rows=listOf(entry(1,Kind.CREDIT_PURCHASE,"2024-02-10"),entry(2,Kind.CREDIT_REVERSAL,"2024-02-11",category=Category.PENDING))
        val result=BillingEstimator.estimate(rows,listOf(Link(2,1)),cycle,today,RateLookup {fail("No rate needed for confirmed zero");RateResult.Missing("unused")})
        assertEquals(BigDecimal("0.00"),result.usdSubtotal);assertEquals(BigDecimal.ZERO,result.cnySubtotal);assertTrue(result.complete)
        val partial=BillingEstimator.estimate(rows+entry(3,Kind.CREDIT_PURCHASE,"2024-02-12","USD","1.25")+entry(4,Kind.CREDIT_PURCHASE,"2024-02-13","GBP","3.00"),listOf(Link(2,1)),cycle,today,RateLookup {RateResult.Missing("missing")})
        assertEquals(BigDecimal("1.25"),partial.usdSubtotal)
        assertNull(partial.cnySubtotal);assertEquals(1,partial.missing);assertFalse(partial.complete)
    }
}
