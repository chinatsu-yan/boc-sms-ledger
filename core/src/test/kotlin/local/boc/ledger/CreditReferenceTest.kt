package local.boc.ledger
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
class CreditReferenceTest {
 private val today=LocalDate.of(2024,2,20)
 private fun event(currency:String,amount:String,kind:Kind=Kind.CREDIT_PURCHASE)=Event(kind,"7328",BodyDate(2024,2,18),BocParser.money(currency,amount),"SYNTHETIC")
 @Test fun nativeYuanRefundIsSeparateAndDoesNotRequestCurrencyConversion() {
  val entries=listOf(Entry(1,event("RMB","100.00"),Category.SPENDING),Entry(2,event("CNY","25.00",Kind.CREDIT_REFUND),Category.PENDING))
  val result=BillingEstimator.estimate(entries,listOf(Link(2,1)),BillingCycle.containing(today,25),today,RateLookup {error("CNY does not need a rate")})
  assertEquals(BigDecimal("75.00"),result.nativeCnySubtotal);assertNull(result.usdSubtotal);assertNull(result.cnySubtotal);assertEquals(0,result.foreignLines.size);assertTrue(result.complete)
 }
 @Test fun lineReferenceUsesOriginalAmountAndTransactionDateWithoutRounding() {
  val e=event("KWD","1.234")
  val value=CreditReference.convert(e,today,RateLookup {key->assertEquals(QuoteKey("KWD","USD",LocalDate.of(2024,2,18)),key);RateResult.Available(FxQuote("KWD","USD",LocalDate.of(2024,2,16),BigDecimal("3.25"),"synthetic",1))})
  assertEquals(BigDecimal("4.01050"),value.amount);assertEquals(LocalDate.of(2024,2,16),value.quote!!.date)
  assertEquals(BigDecimal("25.00"),CreditReference.convert(event("USD","25.00"),today,RateLookup {error("USD is already USD")}).amount)
 }
 @Test fun missingOrUnknownDateNeverUsesCurrentRateOrZero() {
  assertNull(CreditReference.convert(event("HKD","25.00"),today,RateLookup {RateResult.Missing("offline")}).amount)
  val unknown=event("HKD","25.00").copy(date=BodyDate(null,2,18))
  assertEquals("日期待确认",CreditReference.convert(unknown,today,RateLookup {error("No date, no request")}).reason)
  val future=event("CNY","10.00").copy(date=BodyDate(2024,2,21))
  val result=BillingEstimator.estimate(listOf(Entry(1,future,Category.SPENDING)),emptyList(),BillingCycle.containing(today,25),today,RateLookup {error("No FX")})
  assertNull(result.nativeCnySubtotal);assertFalse(result.complete)
 }
}
