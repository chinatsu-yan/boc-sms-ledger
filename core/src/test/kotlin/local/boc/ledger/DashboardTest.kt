package local.boc.ledger
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.math.BigDecimal
class DashboardTest {
    private fun row(id:Long,kind:Kind=Kind.DEBIT_PAYMENT,category:Category=Category.SPENDING,balance:String?=null,tail:String="7304",currency:String="CNY")=Entry(id,Event(kind,tail,null,Money(currency,BigDecimal("12.50")),balance=balance?.let {Money(currency,BigDecimal(it))}),category)
    @Test fun debitTotalsExcludeCreditTransfersAndRepayment() {
        val rows=listOf(row(1),row(2,Kind.CREDIT_PURCHASE),row(3,category=Category.REPAYMENT),row(4,category=Category.TRANSFER),row(5,Kind.DEBIT_TRANSFER_IN,Category.INCOME))
        val totals=DebitDashboard.totals(rows,rows.map {it.id}.toSet()).getValue("CNY")
        assertEquals(BigDecimal("12.50"),totals.net);assertEquals(BigDecimal("12.50"),totals.income)
    }
    @Test fun newestAvailableSnapshotPerAccountDoesNotSumRepeatedBalances() {
        val rows=listOf(ObservedEntry(row(1,balance="100.00"),10),ObservedEntry(row(2,balance="90.00"),20),ObservedEntry(row(3),30))
        val result=DebitDashboard.balances(rows).single();assertEquals(BigDecimal("90.00"),result.money!!.amount);assertEquals(20L,result.received)
    }
    @Test fun missingAndDifferentCurrenciesRemainSeparate() {
        val result=DebitDashboard.balances(listOf(ObservedEntry(row(1),10),ObservedEntry(row(2,balance="20.00",currency="USD"),20),ObservedEntry(row(3,balance="30.00",tail="1111"),30)))
        assertEquals(3,result.size);assertEquals(1,result.count {it.money==null})
    }
    @Test fun excludedUnverifiedAndCreditNeverContributeBalances() {
        assertTrue(DebitDashboard.balances(listOf(ObservedEntry(row(1,balance="99").copy(excluded=true),1),ObservedEntry(row(2,balance="99").copy(senderVerified=false),2),ObservedEntry(row(3,Kind.CREDIT_PURCHASE,balance="99"),3))).isEmpty())
    }
    @Test fun repaymentClampsAndCrossesYears() {
        val cycle=BillingCycle(LocalDate.parse("2023-12-23"),LocalDate.parse("2024-01-22"))
        assertEquals(LocalDate.parse("2024-02-29"),RepaymentDate.forCycle(cycle,1,31))
        assertEquals(LocalDate.parse("2024-01-22"),RepaymentDate.forCycle(cycle,0,22))
        assertFalse(RepaymentDate.valid(22,0,12));assertTrue(RepaymentDate.valid(22,1,12))
    }
}
