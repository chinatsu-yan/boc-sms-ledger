package local.boc.ledger
import org.junit.Test
import org.junit.Assert.*
import java.time.LocalDate
import java.time.ZoneId
class SmsDateTest {
 private fun time(s:String)=LocalDate.parse(s).atTime(12,0).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
 @Test fun receiptAnchorsOrdinaryAndNewYearDates() {
  assertEquals(BodyDate(2023,11,15),SmsDate.resolve(BodyDate(null,11,15),time("2023-11-15")))
  assertEquals(BodyDate(2023,12,31),SmsDate.resolve(BodyDate(null,12,31),time("2024-01-01")))
  assertEquals(BodyDate(2024,1,1),SmsDate.resolve(BodyDate(null,1,1),time("2024-01-02")))
  assertEquals(BodyDate(2024,2,29),SmsDate.resolve(BodyDate(null,2,29),time("2024-03-01")))
 }
 @Test fun explicitYearAndUncertainEvidenceRemainUnchanged() {
  val explicit=BodyDate(2020,12,31);assertEquals(explicit,SmsDate.resolve(explicit,time("2024-01-01")))
  val future=BodyDate(null,10,30);assertEquals(future,SmsDate.resolve(future,time("2026-10-29")))
  assertEquals(BodyDate(null,2,29),SmsDate.resolve(BodyDate(null,2,29),time("2026-03-01")))
  assertEquals(BodyDate(null,2,29),SmsDate.resolve(BodyDate(null,2,29),time("2025-03-01")))
  assertEquals(BodyDate(null,10,1),SmsDate.resolve(BodyDate(null,10,1),0))
 }
}
