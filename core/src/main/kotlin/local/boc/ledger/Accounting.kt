package local.boc.ledger

import java.math.BigDecimal

object Accounting {
    fun validateLink(entries: List<Entry>, links: List<Link>, adjustmentId: Long, purchaseId: Long): String? {
        val a=entries.find { it.id==adjustmentId && !it.excluded && it.senderVerified }?.event ?: return "调整事件不可用"
        val p=entries.find { it.id==purchaseId && !it.excluded && it.senderVerified }?.event ?: return "消费事件不可用"
        if(!a.currenciesRecognized || !p.currenciesRecognized) return "币种待确认，暂不能关联"
        if(a.kind !in listOf(Kind.CREDIT_REVERSAL,Kind.CREDIT_REFUND) || p.kind!=Kind.CREDIT_PURCHASE) return "仅支持撤销/退货关联信用卡消费"
        if(a.tail!=p.tail || a.money.currency!=p.money.currency) return "账户或币种不一致"
        if(links.any { it.adjustmentId==adjustmentId }) return "此事件已关联，请先解除"
        val used=links.filter { it.purchaseId==purchaseId }.fold(BigDecimal.ZERO) { sum,l -> sum + (entries.find { it.id==l.adjustmentId }?.takeIf { !it.excluded && it.senderVerified }?.event?.money?.amount ?: BigDecimal.ZERO) }
        if(used + a.money.amount > p.money.amount) return "累计撤销/退货超过原消费"
        return null
    }
    // Aggregate first over the complete ledger; filter afterwards so links outside a visible period remain effective.
    fun totals(all: List<Entry>, links: List<Link>, visibleIds: Set<Long> = all.map { it.id }.toSet()): Map<String,CurrencyTotal> {
        val valid=all.filter { !it.excluded && it.senderVerified && it.event?.currenciesRecognized==true }
        val active=links.filter { l -> valid.any { it.id==l.purchaseId } && valid.any { it.id==l.adjustmentId } }
        val result=sortedMapOf<String,CurrencyTotal>()
        for(row in valid.filter { it.id in visibleIds }) {
            val e=row.event!!; val c=e.money.currency; val t=result[c]?:CurrencyTotal(); val n=e.money.amount
            result[c]=when {
                e.kind==Kind.CREDIT_REVERSAL -> if(active.any { it.adjustmentId==row.id }) t else t.copy(unlinkedReversal=t.unlinkedReversal+n)
                e.kind==Kind.CREDIT_REFUND -> if(active.any { it.adjustmentId==row.id }) t else t.copy(unlinkedRefund=t.unlinkedRefund+n)
                e.kind==Kind.CREDIT_FX_REPAYMENT -> t.copy(repayment=t.repayment+n)
                row.category==Category.SPENDING -> {
                    val offset=active.filter { it.purchaseId==row.id }.fold(BigDecimal.ZERO) { sum,l -> sum + valid.first { it.id==l.adjustmentId }.event!!.money.amount }
                    t.copy(spending=t.spending+n,confirmedOffset=t.confirmedOffset+offset)
                }
                row.category==Category.INCOME -> t.copy(income=t.income+n)
                else -> t
            }
        }
        return result
    }
}
