package local.boc.ledger

/** Use the installed runtime's registry, not acceptance of an arbitrary three-letter token. */
object CurrencySupport {
    private val unitsByCode: Map<String,Int> by lazy {
        java.util.Currency.getAvailableCurrencies()
            .filter { it.defaultFractionDigits >= 0 }
            .associate { it.currencyCode to it.defaultFractionDigits }
    }
    // A newer valid code can be absent from an older platform; null means unconfirmed, not invalid.
    fun minorUnits(code: String): Int? = unitsByCode[code]
}
