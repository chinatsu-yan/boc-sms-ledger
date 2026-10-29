package local.boc.ledger

import java.math.BigDecimal
import java.time.LocalDate

enum class Kind(val label: String) {
    DEBIT_PAYMENT("借记卡支付"), DEBIT_TRANSFER_IN("借记卡转入"), DEBIT_TRANSFER_OUT("借记卡转出"),
    CREDIT_PURCHASE("信用卡消费"), CREDIT_REVERSAL("信用卡撤销"), CREDIT_REFUND("信用卡退货"), CREDIT_FX_REPAYMENT("购汇还款")
}
enum class AccountKind(val label: String) { DEBIT("借记卡"), CREDIT("信用卡") }
enum class Category(val label: String) { PENDING("用途待确认"), SPENDING("消费"), INCOME("收入"), TRANSFER("本人调拨"), REPAYMENT("还款本金"), OTHER("其他非收支") }
data class Money(val currency: String, val amount: BigDecimal, val rawCurrency: String = currency,
    val currencyRecognized: Boolean = CurrencySupport.minorUnits(currency) != null) {
    init { require(currency.matches(Regex("[A-Z]{3}"))); require(amount >= BigDecimal.ZERO) }
    override fun toString() = "$currency ${amount.toPlainString()}"
}
data class BodyDate(val year: Int?, val month: Int, val day: Int) {
    init { require(year == null || year in 1..9999); LocalDate.of(year ?: 2000, month, day) }
    override fun toString() = if (year == null) "%02d-%02d（年份未知）".format(month, day) else "%04d-%02d-%02d".format(year, month, day)
}
data class Event(
    val kind: Kind, val tail: String, val date: BodyDate?, val money: Money,
    val merchant: String? = null, val balance: Money? = null, val channel: String? = null,
    val sourceTail: String? = null, val sourceMoney: Money? = null, val reportedRate: BigDecimal? = null,
    val rateDirection: String? = null
) {
    init { require(tail.matches(Regex("[0-9]{4}"))); require(money.amount > BigDecimal.ZERO); require(sourceMoney == null || sourceMoney.amount > BigDecimal.ZERO) }
    val accountKind get() = when(kind) { Kind.DEBIT_PAYMENT,Kind.DEBIT_TRANSFER_IN,Kind.DEBIT_TRANSFER_OUT -> AccountKind.DEBIT; Kind.CREDIT_PURCHASE,Kind.CREDIT_REVERSAL,Kind.CREDIT_REFUND,Kind.CREDIT_FX_REPAYMENT -> AccountKind.CREDIT }
    val account: String get() = accountKind.label + " · " + tail
    val currenciesRecognized get() = money.currencyRecognized && (sourceMoney?.currencyRecognized ?: true)
    val unconfirmedCurrencyCodes get() = listOfNotNull(money,sourceMoney).filterNot { it.currencyRecognized }.map { it.currency }.distinct()
    val defaultCategory get() = if(!currenciesRecognized) Category.PENDING else when(kind) { Kind.CREDIT_PURCHASE -> Category.SPENDING; Kind.CREDIT_FX_REPAYMENT -> Category.REPAYMENT; else -> Category.PENDING }
}
enum class ParseStatus(val label: String) { PARSED("已识别"), NEEDS_REVIEW("未识别待确认"), IGNORED("非交易") }
data class ParseResult(val status: ParseStatus, val event: Event? = null, val reason: String = "")
data class Entry(val id: Long, val event: Event?, val category: Category, val excluded: Boolean = false, val senderVerified: Boolean = true)
data class Link(val adjustmentId: Long, val purchaseId: Long)
data class CurrencyTotal(
    val spending: BigDecimal = BigDecimal.ZERO, val confirmedOffset: BigDecimal = BigDecimal.ZERO,
    val unlinkedRefund: BigDecimal = BigDecimal.ZERO, val unlinkedReversal: BigDecimal = BigDecimal.ZERO,
    val income: BigDecimal = BigDecimal.ZERO, val repayment: BigDecimal = BigDecimal.ZERO
) { val net get() = spending - confirmedOffset }
