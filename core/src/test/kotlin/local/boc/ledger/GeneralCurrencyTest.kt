package local.boc.ledger

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.math.BigDecimal

class GeneralCurrencyTest {
    private val fixtures=JsonParser.parseString(File("fixtures/synthetic/general-currencies.json").readText()).asJsonObject
    @Test fun standardCurrencyMatrixAndAllMoneyTemplatesPreserveExactAmounts() {
        fixtures["cases"].asJsonArray.forEach { item ->
            val c=item.asJsonObject;val expected=c["expected"].asJsonObject
            val result=BocParser.parse(c["text"].asString)
            assertEquals(c["id"].asString,ParseStatus.PARSED,result.status)
            val e=result.event!!
            assertTrue(e.currenciesRecognized)
            assertEquals(expected["currency"].asString,e.money.currency)
            assertEquals(expected["raw_currency"].asString,e.money.rawCurrency)
            assertEquals(expected["amount"].asString,e.money.amount.toPlainString())
            assertEquals(expected["kind"].asString,e.kind.name)
            assertEquals("5555",e.tail)
            assertEquals(BodyDate(2024,2,20),e.date)
            if(expected.has("merchant")) assertEquals(expected["merchant"].asString,e.merchant)
            if(expected.has("source_currency")) {
                assertEquals(expected["source_currency"].asString,e.sourceMoney!!.currency)
                assertEquals(expected["source_amount"].asString,e.sourceMoney.amount.toPlainString())
                assertEquals("0.050000",e.reportedRate!!.toPlainString())
                assertTrue(e.rateDirection!!.startsWith("KWD/CNY"))
            }
        }
    }
    @Test fun everyRuntimeCurrencyWithUnitRulesCanUseTheExistingTemplate() {
        val currencies=java.util.Currency.getAvailableCurrencies().filter {it.defaultFractionDigits>=0}
        assertTrue(currencies.size>100)
        for(currency in currencies) {
            val number=BigDecimal("1234").setScale(currency.defaultFractionDigits).toPlainString()
            val body="您的信用卡5555于2024年02月20日，在DEMOUSD消费${currency.currencyCode}${number}元。【中国银行】"
            val result=BocParser.parse(body)
            assertEquals(currency.currencyCode,ParseStatus.PARSED,result.status)
            assertEquals(currency.currencyCode,result.event!!.money.currency)
            assertEquals(number,result.event.money.amount.toPlainString())
        }
        println("JVM currency entries with unit rules exercised: ${currencies.size}")
    }
    @Test fun malformedAmountsAndUnknownCodesAreNeverConfirmed() {
        fixtures["boundary_cases"].asJsonArray.forEach { item ->
            val c=item.asJsonObject;val result=BocParser.parse(c["text"].asString)
            assertEquals(c["id"].asString,c["expected_status"].asString.uppercase(),result.status.name)
            if(c["draft"].asBoolean) {
                val e=result.event!!
                assertEquals(c["currency"].asString,e.money.currency)
                assertEquals(c["amount"].asString,e.money.amount.toPlainString())
                assertFalse(e.currenciesRecognized)
                assertEquals(Category.PENDING,e.defaultCategory)
                assertTrue(result.reason.contains(e.money.currency))
                assertTrue(Accounting.totals(listOf(Entry(1,e,Category.SPENDING)),emptyList()).isEmpty())
            } else assertNull(c["id"].asString,result.event)
        }
    }
    @Test fun validCodeMissingFromAnOlderPlatformIsRetainedAndNotAutoClassified() {
        val body="您的信用卡5555于2024年02月20日，在DEMOUSD消费EUR1,234.50元。【中国银行】"
        assertEquals(ParseStatus.PARSED,BocParser.parse(body).status)
        val result=BocParser.parseWithCurrencyLookup(body) {code->if(code=="EUR") null else CurrencySupport.minorUnits(code)}
        assertEquals(ParseStatus.NEEDS_REVIEW,result.status)
        val e=result.event!!
        assertEquals("EUR",e.money.currency);assertEquals("1234.50",e.money.amount.toPlainString())
        assertFalse(e.currenciesRecognized);assertEquals(Category.PENDING,e.defaultCategory)
        assertTrue(Accounting.totals(listOf(Entry(1,e,Category.SPENDING)),emptyList()).isEmpty())
    }
    @Test fun unconfirmedCurrencyBlocksPurchaseRefundReversalAndEitherFxSide() {
        val prefix="您的信用卡5555于2024年02月20日"
        val bodies=listOf(prefix+"，在DEMOUSD消费ZZZ12.3456元。【中国银行】",prefix+"，在DEMOUSD撤销ZZZ12.3456元。【中国银行】",prefix+"成功退货ZZZ12.3456元【中国银行】",prefix+"购汇还款，购汇转出卡号6666，购汇转入ZZZ12.3456，购汇转出RMB246.90，汇率0.050000【中国银行】",prefix+"购汇还款，购汇转出卡号6666，购汇转入USD12.34，购汇转出ZZZ246.901234，汇率0.050000【中国银行】")
        val rows=bodies.mapIndexed {index,body->
            val p=BocParser.parse(body);assertEquals(ParseStatus.NEEDS_REVIEW,p.status)
            val e=p.event!!;assertFalse(e.currenciesRecognized)
            if(e.kind==Kind.CREDIT_FX_REPAYMENT) assertNull(e.rateDirection)
            Entry(index.toLong(),e,Category.SPENDING)
        }
        assertTrue(Accounting.totals(rows,emptyList()).isEmpty())
        assertNotNull(Accounting.validateLink(rows,emptyList(),1,0))
        assertEquals("246.901234",rows.last().event!!.sourceMoney!!.amount.toPlainString())
    }
    @Test fun standardCurrenciesStaySeparateAndIntegersHaveNoFloatRounding() {
        val currencies=listOf("EUR" to "1234.50","KWD" to "12.345","BHD" to "1234.567","JPY" to "12345.00")
        val rows=currencies.mapIndexed {index,(code,n)->
            val e=Event(Kind.CREDIT_PURCHASE,"5555",null,BocParser.money(code,n))
            Entry(index.toLong(),e,e.defaultCategory)
        }
        val totals=Accounting.totals(rows,emptyList())
        assertEquals(currencies.map {it.first}.toSet(),totals.keys)
        currencies.forEach {(code,n)->assertEquals(BigDecimal(n),totals[code]!!.net)}
    }
}
