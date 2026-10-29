package local.boc.ledger

import java.time.LocalDate
import java.time.YearMonth

data class ObservedEntry(val entry:Entry,val received:Long)
data class BalanceSnapshot(val account:String,val currency:String,val money:Money?,val received:Long?)
object DebitDashboard {
    fun totals(rows:List<Entry>,visible:Set<Long>)=Accounting.totals(rows.filter {it.event?.accountKind==AccountKind.DEBIT},emptyList(),visible)
    // A snapshot is evidence, independent of the selected income/spending period.
    // Use the most recently received available balance; never synthesize one from cash flow.
    fun balances(rows:List<ObservedEntry>):List<BalanceSnapshot> = rows.filter {
        !it.entry.excluded && it.entry.senderVerified && it.entry.event?.accountKind==AccountKind.DEBIT && it.entry.event.currenciesRecognized
    }.groupBy {it.entry.event!!.account to it.entry.event.money.currency}.map { (key,group) ->
        val latest=group.filter {it.entry.event!!.balance?.let {b->b.currencyRecognized && b.currency==key.second}==true}
            .maxWithOrNull(compareBy<ObservedEntry> {it.received}.thenBy {it.entry.id})
        BalanceSnapshot(key.first,key.second,latest?.entry?.event?.balance,latest?.received)
    }.sortedWith(compareBy<BalanceSnapshot>{it.account}.thenBy {it.currency})
}
object RepaymentDate {
    fun forCycle(cycle:BillingCycle,monthOffset:Int,day:Int):LocalDate {
        require(monthOffset in 0..1 && day in 1..31)
        val month=YearMonth.from(cycle.end).plusMonths(monthOffset.toLong())
        return month.atDay(minOf(day,month.lengthOfMonth()))
    }
    fun valid(statementDay:Int,monthOffset:Int,day:Int)=statementDay in 1..31 && day in 1..31 && monthOffset in 0..1 && (monthOffset==1 || day>=statementDay)
}

/** Account cash movement, independent of economic category. */
fun DebitDashboard.flows(rows:List<Entry>,visible:Set<Long>):Map<String,CurrencyTotal> {
    val result=sortedMapOf<String,CurrencyTotal>()
    rows.filter {it.id in visible && !it.excluded && it.senderVerified && it.event?.currenciesRecognized==true && it.event.accountKind==AccountKind.DEBIT}.forEach {row->
        val event=row.event!!;val old=result[event.money.currency] ?: CurrencyTotal()
        result[event.money.currency]=if(event.kind==Kind.DEBIT_TRANSFER_IN) old.copy(income=old.income+event.money.amount) else old.copy(spending=old.spending+event.money.amount)
    }
    return result
}

/** The first due date on or after today, not the newest accumulating statement. */
fun RepaymentDate.upcomingCycle(today:LocalDate,statementDay:Int,monthOffset:Int,day:Int,offset:Int=0):BillingCycle {
    require(valid(statementDay,monthOffset,day))
    val month=YearMonth.from(today)
    val base=(-2L..2L).map {delta->
        val endMonth=month.plusMonths(delta)
        val end=endMonth.atDay(minOf(statementDay,endMonth.lengthOfMonth()))
        BillingCycle.containing(end,statementDay)
    }.filter {forCycle(it,monthOffset,day)>=today}.minBy {forCycle(it,monthOffset,day)}
    return BillingCycle.containing(base.end,statementDay,offset)
}
