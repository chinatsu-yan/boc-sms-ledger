package local.boc.ledger

import org.json.JSONObject
import java.math.BigDecimal

object EventJson {
    private fun money(m: Money): JSONObject = JSONObject().put("currency",m.currency).put("amount",m.amount.toPlainString()).put("raw",m.rawCurrency)
    private fun money(o: JSONObject) = Money(o.getString("currency"),BigDecimal(o.getString("amount")),o.getString("raw"))
    fun encode(e: Event): String = JSONObject().apply {
        put("kind",e.kind.name); put("tail",e.tail); put("money",money(e.money))
        e.date?.let { put("date",JSONObject().put("year",it.year ?: JSONObject.NULL).put("month",it.month).put("day",it.day)) }
        put("merchant",e.merchant); e.balance?.let { put("balance",money(it)) }; put("channel",e.channel)
        put("sourceTail",e.sourceTail); e.sourceMoney?.let { put("sourceMoney",money(it)) }
        put("reportedRate",e.reportedRate?.toPlainString()); put("rateDirection",e.rateDirection)
    }.toString()
    fun decode(text: String): Event = JSONObject(text).let { o ->
        Event(Kind.valueOf(o.getString("kind")),o.getString("tail"),o.optJSONObject("date")?.let { BodyDate(if(it.isNull("year")) null else it.getInt("year"),it.getInt("month"),it.getInt("day")) }, money(o.getJSONObject("money")),o.nullable("merchant"),o.optJSONObject("balance")?.let(::money),o.nullable("channel"),o.nullable("sourceTail"),o.optJSONObject("sourceMoney")?.let(::money),o.nullable("reportedRate")?.let(::BigDecimal),o.nullable("rateDirection"))
    }
    private fun JSONObject.nullable(key: String): String? = if(isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
}
