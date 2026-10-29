package local.boc.ledger

import java.math.BigDecimal
import java.math.RoundingMode

object BocParser {
    const val VERSION = 2
    private const val MONEY = "(人民币|RMB|CNY|USD|SGD|JPY|TWD|HKD)([0-9.,]+)"
    private val debit = Regex("您的借记卡账户([0-9]{4})，于([0-9]{2})月([0-9]{2})日(网上支付支取|收入\\(网银跨行\\)|手机银行支取\\(跨行支付\\))人民币([0-9.,]+)元,交易后余额([0-9.,]+)【中国银行】")
    private const val PREFIX = "您的信用卡([0-9]{4})于([0-9]{4})年([0-9]{2})月([0-9]{2})日"
    private val credit = Regex(PREFIX + "，在(.+?)(消费|撤销)" + MONEY + "元。?【中国银行】")
    private val refund = Regex(PREFIX + "成功退货" + MONEY + "元。?【中国银行】")
    private val fx = Regex(PREFIX + "购汇还款，购汇转出卡号([0-9]{4})，购汇转入" + MONEY + "，购汇转出" + MONEY + "，汇率([0-9.]+)【中国银行】")
    private val amount = Regex("(?:[0-9]+|[1-9][0-9]{0,2}(?:,[0-9]{3})+)(?:\\.[0-9]+)?")
    fun money(raw: String, number: String): Money {
        require(amount.matches(number)) { "金额分组或小数格式无效" }
        val currency = if (raw == "人民币" || raw == "RMB") "CNY" else raw
        val decimal = BigDecimal(number.replace(",", ""))
        val scale = java.util.Currency.getInstance(currency).defaultFractionDigits
        require(scale >= 0 && decimal.scale() <= scale && decimal.precision() <= 24) { "币种精度无效" }
        return Money(currency, decimal, raw)
    }
    fun parse(body: String): ParseResult {
        val text = body.trim().replace("\r", "").replace("\n", "")
        val nonTransaction = listOf("验证码", "失败", "本期应还", "请按时还款", "提醒：", "温馨提示", "优惠", "营销").any(text::contains)
        return try {
            val event = debit.matchEntire(text)?.let { m ->
                val g = m.groupValues
                val kind = when(g[4]) { "网上支付支取" -> Kind.DEBIT_PAYMENT; "收入(网银跨行)" -> Kind.DEBIT_TRANSFER_IN; else -> Kind.DEBIT_TRANSFER_OUT }
                Event(kind, g[1], BodyDate(null,g[2].toInt(),g[3].toInt()), money("人民币",g[5]), balance = money("人民币",g[6]), channel = when(kind) { Kind.DEBIT_PAYMENT -> "网上支付"; Kind.DEBIT_TRANSFER_IN -> "网银跨行"; else -> "手机银行 / 跨行支付" })
            } ?: credit.matchEntire(text)?.let { m ->
                val g=m.groupValues
                Event(if(g[6]=="消费") Kind.CREDIT_PURCHASE else Kind.CREDIT_REVERSAL,g[1],date(g),money(g[7],g[8]),merchant=g[5])
            } ?: refund.matchEntire(text)?.let { m ->
                val g=m.groupValues
                Event(Kind.CREDIT_REFUND,g[1],date(g),money(g[5],g[6]))
            } ?: fx.matchEntire(text)?.let { m ->
                val g=m.groupValues
                val incoming=money(g[6],g[7]); val outgoing=money(g[8],g[9]); val rate=BigDecimal(g[10])
                require(rate > BigDecimal.ZERO && rate.scale() <= 12)
                val direction=if((outgoing.amount*rate).setScale(java.util.Currency.getInstance(incoming.currency).defaultFractionDigits,RoundingMode.HALF_UP).compareTo(incoming.amount)==0) "${incoming.currency}/${outgoing.currency}（由实付金额推得）" else null
                Event(Kind.CREDIT_FX_REPAYMENT,g[1],date(g),incoming,sourceTail=g[5],sourceMoney=outgoing,reportedRate=rate,rateDirection=direction)
            }
            if(event != null) ParseResult(ParseStatus.PARSED,event)
            else if(nonTransaction) ParseResult(ParseStatus.IGNORED,reason="验证码、提醒、营销或失败通知")
            else ParseResult(ParseStatus.NEEDS_REVIEW,reason="尚未支持的完整模板，请查看原文")
        } catch (_: IllegalArgumentException) { ParseResult(ParseStatus.NEEDS_REVIEW,reason="金额、日期或币种格式无效") }
        catch (_: java.time.DateTimeException) { ParseResult(ParseStatus.NEEDS_REVIEW,reason="日期无效") }
    }
    private fun date(g: List<String>) = BodyDate(g[2].toInt(),g[3].toInt(),g[4].toInt())
}
