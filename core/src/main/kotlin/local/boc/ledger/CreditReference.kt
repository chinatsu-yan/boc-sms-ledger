package local.boc.ledger
import java.math.BigDecimal
import java.time.LocalDate

data class UsdReference(val amount:BigDecimal?,val quote:FxQuote?=null,val reason:String?=null)
object CreditReference {
    fun convert(event:Event,today:LocalDate,rates:RateLookup):UsdReference {
        if(event.money.currency=="CNY") return UsdReference(null,reason="人民币单列")
        if(event.money.currency=="USD") return UsdReference(event.money.amount)
        val d=event.date;val date=d?.year?.let {LocalDate.of(it,d.month,d.day)} ?: return UsdReference(null,reason="日期待确认")
        if(date>today) return UsdReference(null,reason="日期待确认")
        return when(val rate=rates.get(QuoteKey(event.money.currency,"USD",date))) {
            is RateResult.Available -> UsdReference(event.money.amount*rate.quote.rate,rate.quote)
            is RateResult.Missing -> UsdReference(null,reason="汇率暂缺")
        }
    }
}
