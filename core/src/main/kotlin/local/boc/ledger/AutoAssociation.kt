package local.boc.ledger

import java.time.LocalDate
import java.time.temporal.ChronoUnit

object AutoAssociation {
    private fun Event.fullDate()=date?.let {d->d.year?.let {LocalDate.of(it,d.month,d.day)}}
    fun propose(entries:List<Entry>,existing:List<Link>,blocked:Set<Long> = emptySet()):List<Link> {
        val valid=entries.filter {!it.excluded && it.senderVerified && it.event?.currenciesRecognized==true}
        val purchases=valid.filter {it.event!!.kind==Kind.CREDIT_PURCHASE && it.category==Category.SPENDING}
        val links=existing.toMutableList();val proposed=mutableListOf<Link>()
        val adjustments=valid.filter {it.event!!.kind in listOf(Kind.CREDIT_REVERSAL,Kind.CREDIT_REFUND)}.sortedBy {it.id}
        for(row in adjustments) {
            if(row.id in blocked || links.any {it.adjustmentId==row.id}) continue
            val a=row.event!!;val merchant=a.merchant?.trim()?.takeIf {it.isNotEmpty()} ?: continue
            val date=a.fullDate() ?: continue
            if(date>LocalDate.now()) continue
            // Same amount without a merchant is explicitly insufficient. No fuzzy merchant matching.
            val candidates=purchases.filter {p->
                val e=p.event!!;val pd=e.fullDate()
                p.id !in blocked && e.tail==a.tail && e.money.currency==a.money.currency && e.merchant?.trim()==merchant &&
                    pd!=null && ChronoUnit.DAYS.between(pd,date) in 0..30 && a.money.amount<=e.money.amount
            }
            // Do not make an ambiguous match appear unique by subtracting other adjustments.
            if(candidates.size!=1) continue
            val purchase=candidates.single()
            if(Accounting.validateLink(valid,links,row.id,purchase.id)!=null) continue
            val link=Link(row.id,purchase.id);links+=link;proposed+=link
        }
        return proposed
    }
}
