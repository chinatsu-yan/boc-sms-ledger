package local.boc.ledger
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
class FeedbackTest {
    private fun row(id:Long,kind:Kind,amount:String="20.00",merchant:String?="SYNTH_STORE",date:String="2026-06-01",tail:String="4826",currency:String="USD"):Entry {
        val d=LocalDate.parse(date)
        return Entry(id,Event(kind,tail,BodyDate(d.year,d.monthValue,d.dayOfMonth),Money(currency,BigDecimal(amount)),merchant),if(kind==Kind.CREDIT_PURCHASE) Category.SPENDING else Category.PENDING)
    }
    @Test fun pendingDebitKindsContributeToCashFlowNotCredit() {
        val rows=listOf(row(1,Kind.DEBIT_TRANSFER_IN,"800.00"),row(2,Kind.DEBIT_PAYMENT,"25.50"),row(3,Kind.DEBIT_TRANSFER_OUT,"100.00"),row(4,Kind.CREDIT_PURCHASE,"99.00"))
        val total=DebitDashboard.flows(rows,rows.map {it.id}.toSet()).getValue("USD")
        assertEquals(BigDecimal("800.00"),total.income);assertEquals(BigDecimal("125.50"),total.spending)
        assertEquals(BigDecimal.ZERO,DebitDashboard.totals(rows,rows.map{it.id}.toSet()).getValue("USD").income)
    }
    @Test fun flowFiltersAndExcludedStillApply() {
        val rows=listOf(row(1,Kind.DEBIT_TRANSFER_IN),row(2,Kind.DEBIT_TRANSFER_IN).copy(senderVerified=false),row(3,Kind.DEBIT_TRANSFER_IN).copy(excluded=true))
        assertTrue(DebitDashboard.flows(rows,setOf(2,3)).isEmpty())
    }
    @Test fun nextMonthDueSelectsPreviousStatementUntilDueDayInclusive() {
        fun cycle(day:String)=RepaymentDate.upcomingCycle(LocalDate.parse(day),22,1,12)
        assertEquals(LocalDate.parse("2026-05-22"),cycle("2026-06-11").end)
        assertEquals(LocalDate.parse("2026-05-22"),cycle("2026-06-12").end)
        assertEquals(LocalDate.parse("2026-06-22"),cycle("2026-06-13").end)
    }
    @Test fun sameMonthYearBoundaryAndLeapClamp() {
        val first=RepaymentDate.upcomingCycle(LocalDate.parse("2026-12-31"),22,0,31)
        assertEquals(LocalDate.parse("2026-12-22"),first.end)
        assertEquals(LocalDate.parse("2027-01-22"),RepaymentDate.upcomingCycle(LocalDate.parse("2027-01-01"),22,0,31).end)
        assertEquals(LocalDate.parse("2024-01-31"),RepaymentDate.upcomingCycle(LocalDate.parse("2024-02-29"),31,1,31).end)
        assertEquals(LocalDate.parse("2024-02-29"),RepaymentDate.upcomingCycle(LocalDate.parse("2024-03-01"),31,1,31).end)
    }
    @Test fun fullReversalMatchesButPartialRefundIsManual() {
        val purchase=row(10,Kind.CREDIT_PURCHASE)
        assertEquals(listOf(Link(1,10)),AutoAssociation.propose(listOf(row(1,Kind.CREDIT_REVERSAL),purchase),emptyList()))
        assertTrue(AutoAssociation.propose(listOf(purchase,row(2,Kind.CREDIT_REFUND,"5.00",date="2026-06-03")),emptyList()).isEmpty())
    }
    @Test fun missingMerchantIsManualAndDuplicateMatchesEarliest() {
        val purchase=row(10,Kind.CREDIT_PURCHASE)
        assertTrue(AutoAssociation.propose(listOf(purchase,row(2,Kind.CREDIT_REFUND,merchant=null)),emptyList()).isEmpty())
        assertEquals(listOf(Link(2,10)),AutoAssociation.propose(listOf(purchase.copy(id=11),purchase,row(2,Kind.CREDIT_REVERSAL)),emptyList()))
    }
    @Test fun currencyAccountDateAndBlockedEvidencePreventMatching() {
        val p=row(10,Kind.CREDIT_PURCHASE)
        for(a in listOf(row(2,Kind.CREDIT_REVERSAL,tail="1111"),row(2,Kind.CREDIT_REVERSAL,currency="CNY"),row(2,Kind.CREDIT_REVERSAL,date="2026-05-31"))) assertTrue(AutoAssociation.propose(listOf(p,a),emptyList()).isEmpty())
        assertTrue(AutoAssociation.propose(listOf(p,row(2,Kind.CREDIT_REVERSAL)),emptyList(),setOf(2)).isEmpty())
    }
    @Test fun cumulativeOffsetsNeverExceedPurchaseAndReplayIsIdempotent() {
        val rows=listOf(row(10,Kind.CREDIT_PURCHASE),row(1,Kind.CREDIT_REFUND,"20.00"),row(2,Kind.CREDIT_REFUND,"20.00"))
        val links=AutoAssociation.propose(rows,emptyList());assertEquals(1,links.size)
        assertTrue(AutoAssociation.propose(rows,links).isEmpty())
        assertEquals(BigDecimal("0.00"),Accounting.totals(rows,links).getValue("USD").net)
    }
}
