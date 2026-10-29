package local.boc.ledger
import android.test.AndroidTestCase
class AssociationTest:AndroidTestCase() {
    fun testReplayAndExplicitUnlinkKeepUserChoice() {
        val name="association-test.db";context.deleteDatabase(name)
        LedgerDb(context,name).use {db->
            val purchase="您的信用卡4826于2026年06月01日，在SYNTH_STORE消费USD20.00元。【中国银行】"
            val reversal="您的信用卡4826于2026年06月02日，在SYNTH_STORE撤销USD20.00元。【中国银行】"
            db.ingest("reversal","95566",reversal,2000,"synthetic",true);db.autoAssociate();assertEquals(0,db.links().size)
            db.ingest("purchase","95566",purchase,1000,"synthetic",true);db.autoAssociate();assertEquals(1,db.links().size)
            val id=db.links().single().adjustmentId;assertTrue(db.history(id).any {it.contains("自动关联")})
            db.autoAssociate();db.reparse();db.autoAssociate();assertEquals(1,db.links().size)
            db.unlink(id);db.autoAssociate();assertEquals(0,db.links().size)
            assertEquals(2,db.all().size)
        }
        context.deleteDatabase(name)
    }
}
