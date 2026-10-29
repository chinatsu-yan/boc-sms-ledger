package local.boc.ledger

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.math.BigDecimal

class LedgerTest {
    private val fixture=JsonParser.parseString(File("fixtures/synthetic/boc-sms.json").readText()).asJsonObject
    private fun sample(id: String) = fixture["cases"].asJsonArray.first { it.asJsonObject["id"].asString==id }.asJsonObject
    private fun event(id: String)=BocParser.parse(sample(id)["text"].asString).event!!
    @Test fun allCompleteFixturesMatchEveryExpectedField() {
        fixture["cases"].asJsonArray.forEach { item ->
            val c=item.asJsonObject; val id=c["id"].asString; val expected=c["expected_event"].asJsonObject
            val parsed=BocParser.parse(c["text"].asString)
            assertEquals(id,ParseStatus.PARSED,parsed.status)
            val e=parsed.event!!
            assertEquals(id,expected["type"].asString.uppercase(),e.kind.name)
            assertEquals(id,expected["account"].asJsonObject["card_tail"].asString,e.tail)
            assertEquals(id,expected["account"].asJsonObject["kind"].asString,if(e.kind.name.startsWith("DEBIT")) "debit" else "credit")
            val date=expected["occurred_on"].asJsonObject
            assertEquals(id,if(date["year"].isJsonNull) null else date["year"].asInt,e.date!!.year)
            assertEquals(id,date["month"].asInt,e.date.month); assertEquals(id,date["day"].asInt,e.date.day)
            fun checkMoney(key:String, actual:Money?) {
                val m=expected[key].asJsonObject
                assertEquals(id,m["currency"].asString,actual!!.currency)
                assertEquals(id,m["raw_currency"].asString,actual.rawCurrency)
                assertEquals(id,m["amount"].asString,actual.amount.toPlainString())
            }
            checkMoney(if(e.kind==Kind.CREDIT_FX_REPAYMENT) "repayment_money" else "money",e.money)
            if(expected.has("balance_after")) checkMoney("balance_after",e.balance)
            if(expected.has("merchant")) assertEquals(id,expected["merchant"].takeUnless { it.isJsonNull }?.asString,e.merchant)
            if(expected.has("channel")) assertTrue(id,e.channel!!.startsWith(expected["channel"].asString))
            if(expected.has("source_money")) {
                checkMoney("source_money",e.sourceMoney)
                assertEquals("1111",e.sourceTail)
                assertEquals("0.147059",e.reportedRate!!.toPlainString())
                assertTrue(e.rateDirection!!.startsWith("USD/CNY"))
                assertEquals("680.00",e.sourceMoney!!.amount.toPlainString())
            }
        }
    }
    @Test fun allBoundaryFixturesAreRejectedAsSpecified() {
        fixture["boundary_cases"].asJsonArray.forEach { item ->
            val c=item.asJsonObject; val p=BocParser.parse(c["text"].asString)
            assertEquals(c["id"].asString,c["expected_status"].asString.uppercase(),p.status.name)
            assertNull(p.event)
        }
    }
    @Test fun exactDecimalCurrencyAndGrouping() {
        assertEquals("12345",BocParser.money("JPY","12,345").amount.toPlainString())
        assertEquals("CNY",BocParser.money("RMB","680.00").currency)
        listOf("12,34","0.75.00","-1.00","1e3","1,234,56","1.000").forEach { bad ->
            try { BocParser.money("USD",bad); fail(bad) } catch(_:IllegalArgumentException) { }
        }
        val text=sample("credit_usd_purchase")["text"].asString.replace("0.75","0.53")
        val amount=BocParser.parse(text).event!!.money
        assertEquals("USD",amount.currency)
        assertEquals(BigDecimal("0.53"),amount.amount)
        assertEquals(BigDecimal("0.30"),BigDecimal("0.10")+BigDecimal("0.20"))
    }
    @Test fun anchoredTemplateDoesNotParseQuotesOrTrailingFailure() {
        val text=sample("credit_usd_purchase")["text"].asString
        assertNull(BocParser.parse("转发：$text").event)
        assertNull(BocParser.parse(text+"交易失败").event)
        assertNull(BocParser.parse("在xxx消费USD0.53元").event)
    }
    @Test fun dateYearAndRateDoNotInventInformation() {
        assertNull(event("debit_online_payment").date!!.year)
        val text=sample("credit_fx_repayment")["text"].asString.replace("0.147059","9.000000")
        val e=BocParser.parse(text).event!!
        assertNull(e.rateDirection); assertEquals("680.00",e.sourceMoney!!.amount.toPlainString())
        assertEquals("100.00",e.money.amount.toPlainString())
        assertEquals(ParseStatus.NEEDS_REVIEW,BocParser.parse(sample("credit_usd_purchase")["text"].asString.replace("2026年06月11日","0000年06月11日")).status)
    }
    @Test fun zeroBalanceIsValidButZeroTransactionIsNot() {
        val body=sample("debit_online_payment")["text"].asString
        assertEquals(BigDecimal("0.00"),BocParser.parse(body.replace("2040.40","0.00")).event!!.balance!!.amount)
        assertEquals(ParseStatus.NEEDS_REVIEW,BocParser.parse(body.replace("12.00","0.00")).status)
    }
    @Test fun merchantKeywordsDoNotTurnACompleteSuccessTemplateIntoReminder() {
        val body=sample("credit_usd_purchase")["text"].asString.replace("CLOUD_DEMO","优惠商店_失败重试")
        assertEquals(ParseStatus.PARSED,BocParser.parse(body).status)
        assertEquals("优惠商店_失败重试",BocParser.parse(body).event!!.merchant)
    }

    @Test fun twdAndHkdPurchaseVariantsKeepOriginalCurrencyAndFields() {
        val variants=JsonParser.parseString(File("fixtures/synthetic/currency-variants.json").readText()).asJsonObject
        val entries=variants["cases"].asJsonArray.mapIndexed { index,item ->
            val c=item.asJsonObject;val expected=c["expected"].asJsonObject
            val result=BocParser.parse(c["text"].asString)
            assertEquals(c["id"].asString,ParseStatus.PARSED,result.status)
            val e=result.event!!
            assertEquals(Kind.CREDIT_PURCHASE,e.kind)
            assertEquals(expected["currency"].asString,e.money.currency)
            assertEquals(expected["currency"].asString,e.money.rawCurrency)
            assertEquals(expected["amount"].asString,e.money.amount.toPlainString())
            assertEquals(expected["merchant"].asString,e.merchant)
            assertEquals(expected["tail"].asString,e.tail)
            assertEquals(expected["year"].asInt,e.date!!.year)
            assertEquals(expected["month"].asInt,e.date.month)
            assertEquals(expected["day"].asInt,e.date.day)
            Entry(index.toLong(),e,Category.SPENDING)
        }
        val totals=Accounting.totals(entries,emptyList())
        assertEquals(setOf("TWD","HKD"),totals.keys)
        assertEquals(BigDecimal("2360.00"),totals["TWD"]!!.net)
        assertEquals(BigDecimal("38.50"),totals["HKD"]!!.net)
    }
    @Test fun newCurrencyInvalidInputsStayVisibleForReview() {
        val variants=JsonParser.parseString(File("fixtures/synthetic/currency-variants.json").readText()).asJsonObject
        variants["boundary_cases"].asJsonArray.forEach { item ->
            val c=item.asJsonObject;val result=BocParser.parse(c["text"].asString)
            assertEquals(c["id"].asString,ParseStatus.NEEDS_REVIEW,result.status)
            assertNull(result.event)
        }
    }
    @Test fun newCurrenciesUseExistingReversalAndRefundTemplates() {
        val twd="您的信用卡4444于2025年04月07日，在DEMO-SHOP撤销TWD2,360.00元。【中国银行】"
        val hkd="您的信用卡4444于2025年04月19日成功退货HKD38.50元【中国银行】"
        assertEquals(Kind.CREDIT_REVERSAL,BocParser.parse(twd).event!!.kind)
        assertEquals("TWD",BocParser.parse(twd).event!!.money.currency)
        assertEquals(Kind.CREDIT_REFUND,BocParser.parse(hkd).event!!.kind)
        assertEquals("HKD",BocParser.parse(hkd).event!!.money.currency)
    }
    private fun rows()=listOf(
        Entry(1,event("credit_sgd_purchase"),Category.SPENDING),
        Entry(2,event("credit_sgd_reversal"),Category.PENDING),
        Entry(3,event("credit_usd_purchase"),Category.SPENDING),
        Entry(4,event("credit_refund_without_merchant"),Category.PENDING),
        Entry(5,event("credit_fx_repayment"),Category.REPAYMENT),
        Entry(6,event("debit_interbank_incoming"),Category.PENDING),
        Entry(7,event("debit_mobile_interbank_outgoing"),Category.PENDING))
    @Test fun reversalRequiresManualLinkAndPreservesBothEvents() {
        val rows=rows()
        assertEquals(BigDecimal("2.50"),Accounting.totals(rows,emptyList())["SGD"]!!.net)
        assertNull(Accounting.validateLink(rows,emptyList(),2,1))
        val t=Accounting.totals(rows,listOf(Link(2,1)))
        assertEquals(BigDecimal("0.00"),t["SGD"]!!.net)
        assertEquals(BigDecimal("0.75"),t["USD"]!!.net)
        assertEquals(BigDecimal("18.90"),t["USD"]!!.unlinkedRefund)
        assertEquals(BigDecimal("100.00"),t["USD"]!!.repayment)
        assertEquals(BigDecimal.ZERO,t["CNY"]!!.income)
        assertEquals(7,rows.size)
        assertEquals(BigDecimal("2.50"),Accounting.totals(rows,emptyList())["SGD"]!!.net)
    }
    @Test fun partialReversalAndOverAdjustment() {
        val rows=rows().toMutableList(); val a=rows[1].event!!
        rows[1]=rows[1].copy(event=a.copy(money=Money("SGD",BigDecimal("1.00"))))
        rows.add(Entry(8,a.copy(money=Money("SGD",BigDecimal("2.00"))),Category.PENDING))
        assertNull(Accounting.validateLink(rows,emptyList(),2,1))
        assertEquals(BigDecimal("1.50"),Accounting.totals(rows,listOf(Link(2,1)))["SGD"]!!.net)
        assertNotNull(Accounting.validateLink(rows,listOf(Link(2,1)),8,1))
        assertNotNull(Accounting.validateLink(rows,listOf(Link(2,1)),2,1))
        assertNotNull(Accounting.validateLink(rows,emptyList(),2,3))
    }
    @Test fun ambiguousPurchasesNeverAutoLinkAndOutOfRangeAdjustmentStillApplies() {
        val rows=rows()+rows()[0].copy(id=8)
        assertEquals(BigDecimal("5.00"),Accounting.totals(rows,emptyList())["SGD"]!!.net)
        assertEquals(BigDecimal("0.00"),Accounting.totals(rows,listOf(Link(2,1)),setOf(1))["SGD"]!!.net)
        assertEquals(BigDecimal("2.50"),Accounting.totals(rows,listOf(Link(2,1)),setOf(8))["SGD"]!!.net)
    }
    @Test fun excludedAndUnverifiedEvidenceCannotCountOrLink() {
        val rows=rows().map { if(it.id==2L) it.copy(senderVerified=false) else it }
        assertNotNull(Accounting.validateLink(rows,emptyList(),2,1))
        assertEquals(BigDecimal("2.50"),Accounting.totals(rows,listOf(Link(2,1)))["SGD"]!!.net)
        assertNull(Accounting.totals(rows.map { it.copy(excluded=true) },emptyList())["SGD"])
    }
}
