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
