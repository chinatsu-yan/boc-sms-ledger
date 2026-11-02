package local.boc.ledger

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class DebitIncomeTest {
    private val sample="您的借记卡账户8246，于08月17日收入(公积金)(提取托付)人民币720.00元，交易后余额3150.20【中国银行】"
    @Test fun multipleIncomeDescriptionsAndChineseCommaAreParsed() {
        val result=BocParser.parse(sample)
        assertEquals(ParseStatus.PARSED,result.status)
        val event=result.event!!
        assertEquals(Kind.DEBIT_TRANSFER_IN,event.kind);assertEquals("8246",event.tail)
        assertEquals(BigDecimal("720.00"),event.money.amount);assertEquals("CNY",event.money.currency)
        assertEquals(BigDecimal("3150.20"),event.balance!!.amount)
        assertEquals("公积金 / 提取托付",event.channel);assertEquals(Category.PENDING,event.defaultCategory)
        val flow=DebitDashboard.flows(listOf(Entry(1,event,event.defaultCategory)),setOf(1)).getValue("CNY")
        assertEquals(BigDecimal("720.00"),flow.income);assertEquals(BigDecimal.ZERO,flow.spending)
    }
    @Test fun incomeDescriptionsArePreservedWithoutAChannelWhitelist() {
        for(channel in listOf("网银跨行","工资","转账")) {
            val event=BocParser.parse(sample.replace("(公积金)(提取托付)","($channel)").replace('，',',')).event!!
            assertEquals(Kind.DEBIT_TRANSFER_IN,event.kind);assertEquals(channel,event.channel)
        }
    }
    @Test fun incompleteOrFailedIncomeDoesNotCreateAFlow() {
        for(body in listOf(sample+"交易失败",sample.replace("收入","预计收入"),sample.replace("720.00","7,20.00"),sample.replace("(公积金)(提取托付)","(公积金")))
            assertNull(BocParser.parse(body).event)
    }
}
