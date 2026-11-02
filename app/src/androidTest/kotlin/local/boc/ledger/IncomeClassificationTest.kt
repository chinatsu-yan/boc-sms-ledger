package local.boc.ledger
import android.test.AndroidTestCase
import java.math.BigDecimal
@Suppress("DEPRECATION")
class IncomeClassificationTest:AndroidTestCase() {
    fun testIncomeCategoriesMigrateAndManualBatchSurvivesReparse() {
        val name="income-classification-test.db";context.deleteDatabase(name)
        val body="您的借记卡账户8428，于08月17日收入(公积金)(提取托付)人民币100.00元，交易后余额2000.00【中国银行】"
        var incomeIds=emptySet<Long>();var spendingId=0L
        LedgerDb(context,name).use {db->
            db.ingest("income-a","95566",body,1723896000000,"synthetic",true);db.ingest("income-b","95566",body,1723896000001,"synthetic",true)
            incomeIds=db.all().map{it.id}.toSet()
            db.ingest("spending","95566",body.replace("收入(公积金)(提取托付)","网上支付支取"),1723896000002,"synthetic",true)
            spendingId=db.all().first{it.id !in incomeIds}.id;db.classify(setOf(spendingId),"餐饮")
            db.writableDatabase.execSQL("DROP TABLE income_categories");db.writableDatabase.version=3
        }
        LedgerDb(context,name).use {db->
            assertEquals("餐饮",db.labels()[spendingId]);assertTrue("公积金" in db.categories(true))
            db.classify(incomeIds,"公积金");db.reparse();assertTrue(db.all().filter{it.id in incomeIds}.all{it.category==Category.INCOME})
            assertTrue(incomeIds.all{db.labels()[it]=="公积金"});assertEquals(BigDecimal("200.00"),Income.points(db.all().map(StoredSms::entry),db.labels(),incomeIds).fold(BigDecimal.ZERO){a,p->a+p.amount})
            db.addCategory("报销",true);val first=incomeIds.first();db.classify(setOf(first),"报销");assertEquals("报销",db.labels()[first])
            try {db.classify(incomeIds+spendingId,"待分类");fail("mixed batch")} catch(_:IllegalArgumentException){}
            assertEquals("报销",db.labels()[first]);assertEquals("餐饮",db.labels()[spendingId])
            db.classify(setOf(first),"转账");assertEquals(1,Income.points(db.all().map(StoredSms::entry),db.labels(),incomeIds).size)
            db.classify(setOf(first),"待分类");assertEquals(2,Income.points(db.all().map(StoredSms::entry),db.labels(),incomeIds).size)
        };context.deleteDatabase(name)
    }
}
