package local.boc.ledger
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
class IncomeTest {
    private fun row(id:Long,kind:Kind=Kind.DEBIT_TRANSFER_IN,category:Category=Category.PENDING,currency:String="CNY")=Entry(id,Event(kind,"8246",BodyDate(2024,8,17),Money(currency,BigDecimal("100.00"))),category)
    @Test fun pendingIncomeIsVisibleWhileTransfersRefundsAndRepaymentsAreExcluded() {
        val rows=listOf(row(1),row(2,category=Category.INCOME),row(3,category=Category.TRANSFER),row(4,Kind.CREDIT_REFUND),row(5,Kind.CREDIT_FX_REPAYMENT,Category.REPAYMENT),row(6,Kind.DEBIT_PAYMENT,Category.SPENDING))
        val points=Income.points(rows,mapOf(2L to "公积金"),rows.map {it.id}.toSet())
        assertEquals(listOf("待分类","公积金"),points.map{it.category});assertEquals(BigDecimal("200.00"),points.fold(BigDecimal.ZERO){a,p->a+p.amount})
        assertEquals(listOf(6L),Spending.points(rows,emptyList(),emptyMap(),rows.map{it.id}.toSet()).map{it.id})
    }
    @Test fun visibleIdsExcludedSourcesAndCurrenciesRemainSeparate() {
        val rows=listOf(row(1),row(2,currency="USD"),row(3).copy(excluded=true),row(4).copy(senderVerified=false),row(5,category=Category.OTHER))
        assertEquals(listOf(2L),Income.points(rows,emptyMap(),setOf(2,3,4,5)).map{it.id})
        assertEquals(setOf("CNY","USD"),Income.points(rows,emptyMap(),setOf(1,2)).map{it.currency}.toSet())
    }
}
