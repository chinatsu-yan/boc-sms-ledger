package local.boc.ledger
import java.math.BigDecimal
import java.time.LocalDate

data class SpendingPoint(val id:Long,val currency:String,val category:String,val amount:BigDecimal)
object Spending {
    fun points(all:List<Entry>,links:List<Link>,labels:Map<Long,String>,visible:Set<Long>,today:LocalDate=LocalDate.now()):List<SpendingPoint> {
        val valid=all.filter {!it.excluded && it.senderVerified && it.event?.currenciesRecognized==true}
        return valid.filter {it.id in visible}.mapNotNull {r->
            val e=r.event!!
            if(e.kind !in listOf(Kind.CREDIT_PURCHASE,Kind.DEBIT_PAYMENT,Kind.DEBIT_TRANSFER_OUT)) return@mapNotNull null
            if(r.category!=Category.SPENDING && !(e.kind==Kind.DEBIT_PAYMENT && r.category==Category.PENDING)) return@mapNotNull null
            val offset=links.filter {it.purchaseId==r.id}.fold(BigDecimal.ZERO) {s,l->
                val a=valid.find {it.id==l.adjustmentId}?.event
                val d=a?.date?.let {v->v.year?.let {LocalDate.of(it,v.month,v.day)}}
                s+if(a!=null && d!=null && d<=today) a.money.amount else BigDecimal.ZERO
            }
            SpendingPoint(r.id,e.money.currency,labels[r.id]?.takeUnless {it in listOf("转账","还款")} ?: "待分类",(e.money.amount-offset).coerceAtLeast(BigDecimal.ZERO))
        }
    }
}
