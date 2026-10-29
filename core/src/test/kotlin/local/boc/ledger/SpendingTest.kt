package local.boc.ledger
import org.junit.Test
import org.junit.Assert.*
import java.math.BigDecimal as D
import java.time.LocalDate
class SpendingTest {
 private fun row(id:Long,k:Kind,c:Category=Category.PENDING,amount:String="20.00",currency:String="CNY",date:BodyDate?=BodyDate(2026,6,2))=Entry(id,Event(k,"4826",date,Money(currency,D(amount)),"SYNTH"),c)
 @Test fun pendingPaymentIncludedButTransferRepaymentExcluded() {
  val rows=listOf(row(1,Kind.DEBIT_PAYMENT),row(2,Kind.DEBIT_TRANSFER_OUT),row(3,Kind.DEBIT_PAYMENT,Category.TRANSFER),row(4,Kind.CREDIT_FX_REPAYMENT,Category.REPAYMENT))
  val points=Spending.points(rows,emptyList(),emptyMap(),rows.map {it.id}.toSet());assertEquals(listOf(1L),points.map {it.id});assertEquals("待分类",points.single().category)
 }
 @Test fun refundFollowsOriginalCategoryAndPeriodOnce() {
  val rows=listOf(row(1,Kind.CREDIT_PURCHASE,Category.SPENDING),row(2,Kind.CREDIT_REFUND,amount="5.00"));val p=Spending.points(rows,listOf(Link(2,1)),mapOf(1L to "购物"),setOf(1))
  assertEquals("购物",p.single().category);assertEquals(D("15.00"),p.single().amount);assertTrue(Spending.points(rows,listOf(Link(2,1)),emptyMap(),setOf(2)).isEmpty())
 }
 @Test fun unlinkedUndatedAndFutureRefundNotSilentlySubtracted() {
  val p=row(1,Kind.CREDIT_PURCHASE,Category.SPENDING);for(a in listOf(row(2,Kind.CREDIT_REFUND,date=null),row(2,Kind.CREDIT_REFUND,date=BodyDate(2099,1,1)))) assertEquals(D("20.00"),Spending.points(listOf(p,a),listOf(Link(2,1)),emptyMap(),setOf(1),LocalDate.parse("2026-06-03")).single().amount)
 }
 @Test fun currenciesPrecisionAndEligibilityRemainSeparate() {
  val rows=listOf(row(1,Kind.DEBIT_PAYMENT,amount="1.234",currency="KWD"),row(2,Kind.DEBIT_PAYMENT),row(3,Kind.DEBIT_PAYMENT).copy(excluded=true));val p=Spending.points(rows,emptyList(),emptyMap(),setOf(1,2,3));assertEquals(2,p.size);assertEquals(D("1.234"),p.first().amount);assertEquals(setOf("KWD","CNY"),p.map {it.currency}.toSet())
 }
 @Test fun earliestDateNotArrivalAndSkipAlreadyFullyCanceled() {
  val newer=row(1,Kind.CREDIT_PURCHASE,Category.SPENDING,date=BodyDate(2026,6,3));val older=row(5,Kind.CREDIT_PURCHASE,Category.SPENDING,date=BodyDate(2026,5,1));val a=row(9,Kind.CREDIT_REVERSAL,date=BodyDate(2026,8,1));assertEquals(listOf(Link(9,5)),AutoAssociation.propose(listOf(newer,older,a),emptyList()));val oldAdjustment=row(8,Kind.CREDIT_REVERSAL,date=BodyDate(2026,6,5));assertEquals(listOf(Link(9,1)),AutoAssociation.propose(listOf(newer,older,oldAdjustment,a),listOf(Link(8,5))))
 }
}
