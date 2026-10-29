package local.boc.ledger
import android.test.AndroidTestCase
/** Open preserved emulator ledgers so external before/after logical dumps can compare the migration. */
class UpgradeCurrentTest:AndroidTestCase() {
 fun testOpenExistingLedgersWithoutMutation() {for(name in listOf("ledger.db","demo.db")) LedgerDb(context,name).use {db->assertEquals(2,db.readableDatabase.version);assertTrue(db.categories().contains("餐饮"));db.all();db.links()}}
}
