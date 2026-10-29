package local.boc.ledger

import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

data class BillingCycle(val start: LocalDate,val end: LocalDate) {
    fun contains(date:LocalDate)=date>=start && date<=end
    companion object {
        fun containing(today:LocalDate,statementDay:Int,offset:Int=0):BillingCycle {
            require(statementDay in 1..31)
            fun closing(month:YearMonth)=month.atDay(minOf(statementDay,month.lengthOfMonth()))
            val month=YearMonth.from(today)
            val currentEnd=closing(month)
            val endMonth=(if(today<=currentEnd) month else month.plusMonths(1)).plusMonths(offset.toLong())
            return BillingCycle(closing(endMonth.minusMonths(1)).plusDays(1),closing(endMonth))
        }
    }
}
data class QuoteKey(val base:String,val quote:String,val date:LocalDate?) {
    init {require(base.matches(Regex("[A-Z]{3}")) && quote.matches(Regex("[A-Z]{3}")))}
    val cacheKey get()="$base/$quote/${date ?: "LATEST"}"
}
data class FxQuote(val base:String,val quote:String,val date:LocalDate,val rate:BigDecimal,val source:String,val fetchedAt:Long,val cached:Boolean=false,val stale:Boolean=false) {
    init {require(rate>BigDecimal.ZERO)}
}
sealed class RateResult {
    data class Available(val quote:FxQuote):RateResult()
    data class Missing(val reason:String):RateResult()
}
fun interface RateLookup { fun get(key:QuoteKey):RateResult }
data class EstimateLine(val id:Long,val date:LocalDate,val currency:String,val netAmount:BigDecimal,val usd:BigDecimal?,val quote:FxQuote?=null,val missing:String?=null)
data class BillingEstimate(val cycle:BillingCycle,val lines:List<EstimateLine>,val undated:Int,val unlinkedAdjustments:Int,val pendingAdjustments:Int,val latest:FxQuote?,val latestMissing:String?) {
    val foreignLines get()=lines.filter {it.currency!="CNY"}
    val yuanLines get()=lines.filter {it.currency=="CNY"}
    val nativeCnySubtotal:BigDecimal? get()=yuanLines.filter {it.missing==null}.let {if(it.isEmpty() && yuanLines.isNotEmpty()) null else it.fold(BigDecimal.ZERO){sum,line->sum+line.netAmount}}
    val covered get()=foreignLines.count {it.usd!=null}
    val missing get()=foreignLines.size-covered
    val usdSubtotal:BigDecimal? get()=if(covered==0) null else lines.mapNotNull {it.usd}.fold(BigDecimal.ZERO,BigDecimal::add)
    val cnySubtotal:BigDecimal? get()=usdSubtotal?.let {if(it.signum()==0) BigDecimal.ZERO else latest?.rate?.multiply(it)}
    val complete get()=yuanLines.none {it.missing!=null} && missing==0 && undated==0 && unlinkedAdjustments==0 && pendingAdjustments==0 && (foreignLines.isEmpty() || usdSubtotal?.signum()==0 || latest!=null)
}
object BillingEstimator {
    private fun Event.fullDate():LocalDate?=date?.let {d->d.year?.let {LocalDate.of(it,d.month,d.day)}}
    fun estimate(entries:List<Entry>,links:List<Link>,cycle:BillingCycle,today:LocalDate,rates:RateLookup):BillingEstimate {
        val valid=entries.filter {!it.excluded && it.senderVerified && it.event?.currenciesRecognized==true && it.event.accountKind==AccountKind.CREDIT}
        val purchases=valid.filter {it.event!!.kind==Kind.CREDIT_PURCHASE && it.category==Category.SPENDING}
        val undated=purchases.count {it.event!!.fullDate()==null}
        val candidates=links.filter {l->valid.any {it.id==l.adjustmentId} && purchases.any {it.id==l.purchaseId}}
        val active=candidates.filter {l->valid.first {it.id==l.adjustmentId}.event!!.fullDate()?.let {it<=today}==true}
        val pending=candidates.count {l->purchases.first {it.id==l.purchaseId}.event!!.fullDate()?.let(cycle::contains)==true && l !in active}
        val unlinked=valid.count {r->r.event!!.kind in listOf(Kind.CREDIT_REVERSAL,Kind.CREDIT_REFUND) && r.event.fullDate()?.let(cycle::contains)==true && candidates.none {it.adjustmentId==r.id}}
        fun validated(key:QuoteKey):RateResult {
            if(key.date!=null && key.date>today) return RateResult.Missing("未来交易日期，未查询汇率")
            val result=rates.get(key)
            if(result is RateResult.Available) {
                val q=result.quote;val target=key.date ?: today
                if(q.base!=key.base || q.quote!=key.quote) return RateResult.Missing("报价方向不一致")
                if(q.date>target || q.date<target.minusDays(14)) return RateResult.Missing("没有近14天内可核验的有效报价")
            }
            return result
        }
        val lines=purchases.mapNotNull {r->
            val e=r.event!!;val date=e.fullDate() ?: return@mapNotNull null
            if(!cycle.contains(date)) return@mapNotNull null
            val offsets=active.filter {it.purchaseId==r.id}.fold(BigDecimal.ZERO) {sum,l->sum+valid.first {it.id==l.adjustmentId}.event!!.money.amount}
            val net=e.money.amount-offsets
            when {
                date>today -> EstimateLine(r.id,date,e.money.currency,net,null,missing="未来交易日期，未查询汇率")
                net.signum()<0 -> EstimateLine(r.id,date,e.money.currency,net,null,missing="关联冲减超过消费，需复核")
                e.money.currency=="CNY" -> EstimateLine(r.id,date,e.money.currency,net,null)
                net.signum()==0 || e.money.currency=="USD" -> EstimateLine(r.id,date,e.money.currency,net,net)
                else -> when(val result=validated(QuoteKey(e.money.currency,"USD",date))) {
                    is RateResult.Available -> EstimateLine(r.id,date,e.money.currency,net,net*result.quote.rate,result.quote)
                    is RateResult.Missing -> EstimateLine(r.id,date,e.money.currency,net,null,missing=result.reason)
                }
            }
        }
        val subtotal=lines.mapNotNull {it.usd}.takeIf {it.isNotEmpty()}?.fold(BigDecimal.ZERO,BigDecimal::add)
        var latest:FxQuote?=null;var error:String?=null
        if(subtotal!=null && subtotal.signum()!=0) when(val result=validated(QuoteKey("USD","CNY",null))) {
            is RateResult.Available -> latest=result.quote
            is RateResult.Missing -> error=result.reason
        }
        return BillingEstimate(cycle,lines,undated,unlinked,pending,latest,error)
    }
}
