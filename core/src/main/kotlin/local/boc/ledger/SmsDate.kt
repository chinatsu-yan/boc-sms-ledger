package local.boc.ledger

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** Month/day evidence anchored to this SMS's timestamp, never the import date. */
object SmsDate {
    private val bankZone=ZoneId.of("Asia/Shanghai")
    fun resolve(date:BodyDate?,received:Long):BodyDate? {
        if(date==null || date.year!=null || received<=0) return date
        val smsDay=Instant.ofEpochMilli(received).atZone(bankZone).toLocalDate()
        val candidates=(smsDay.year-1..smsDay.year+1).mapNotNull {year->
            val month=java.time.YearMonth.of(year,date.month)
            if(date.day<=month.lengthOfMonth()) month.atDay(date.day) else null
        }.sortedBy {abs(ChronoUnit.DAYS.between(it,smsDay))}
        val nearest=candidates.firstOrNull() ?: return date
        val distance=abs(ChronoUnit.DAYS.between(nearest,smsDay))
        // A nearer future occurrence or equally near years is ambiguous, not evidence for last year.
        if(distance>183 || nearest>smsDay || candidates.drop(1).any {abs(ChronoUnit.DAYS.between(it,smsDay))==distance}) return date
        return BodyDate(nearest.year,date.month,date.day)
    }
    fun fullDate(event:Event?):LocalDate?=event?.date?.let {d->d.year?.let {LocalDate.of(it,d.month,d.day)}}
}
