package local.boc.ledger

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.test.AndroidTestCase
import android.test.mock.MockContentResolver

@Suppress("DEPRECATION")
class AutoScanTest:AndroidTestCase() {
    data class Sms(val id:Long,val body:String,val at:Long,val sender:String="95566")
    class Inbox:ContentProvider() {
        val rows=mutableListOf<Sms>();var queries=0;var firstFrom:Long?=null
        override fun onCreate()=true
        override fun query(uri:Uri,projection:Array<out String>?,selection:String?,args:Array<out String>?,sort:String?):Cursor {
            queries++;val from=args!![0].toLong();val until=args[1].toLong();if(firstFrom==null)firstFrom=from
            return MatrixCursor(arrayOf("_id","address","body","date","sub_id")).apply {rows.filter {it.at in from..until}.forEach {addRow(arrayOf<Any>(it.id,it.sender,it.body,it.at,"synthetic"))}}
        }
        override fun getType(uri:Uri)="vnd.android.cursor.dir/sms"
        override fun insert(uri:Uri,values:ContentValues?):Uri?=null
        override fun delete(uri:Uri,selection:String?,args:Array<out String>?)=0
        override fun update(uri:Uri,values:ContentValues?,selection:String?,args:Array<out String>?)=0
    }
    class Isolated(base:Context,val resolver:MockContentResolver):ContextWrapper(base) {
        var allowed=false
        override fun checkSelfPermission(permission:String)=if(permission==Manifest.permission.READ_SMS) {if(allowed) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED} else super.checkSelfPermission(permission)
        override fun getContentResolver():ContentResolver=resolver
        override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences("autoscan-test-$name",mode)
        override fun openOrCreateDatabase(name:String,mode:Int,factory:SQLiteDatabase.CursorFactory?):SQLiteDatabase=baseContext.openOrCreateDatabase("autoscan-test-$name",mode,factory)
        override fun openOrCreateDatabase(name:String,mode:Int,factory:SQLiteDatabase.CursorFactory?,handler:DatabaseErrorHandler?):SQLiteDatabase=baseContext.openOrCreateDatabase("autoscan-test-$name",mode,factory,handler)
    }
    fun testInitialGrantIncrementalNewSmsAndRevocationNeedNoManualImport() {
        context.deleteDatabase("autoscan-test-ledger.db")
        val provider=Inbox();val resolver=MockContentResolver().apply {addProvider("sms",provider)}
        val fake=Isolated(context,resolver)
        provider.attachInfo(fake,android.content.pm.ProviderInfo().apply {authority="sms";exported=true})
        val prefs=fake.getSharedPreferences("import",0)
        prefs.edit().clear().commit()
        val now=System.currentTimeMillis()
        val credit="您的信用卡5555于2024年02月20日，在DEMO消费USD2.00元。【中国银行】"
        provider.rows.add(Sms(1,credit,now-5000))
        provider.rows.add(Sms(2,"未适配格式【中国银行】",now-4000))
        provider.rows.add(Sms(3,"其他银行通知",now-3000,"95588"))
        assertFalse(SmsImport.prepareAutomatic(fake));assertFalse(prefs.contains("enabled"));assertEquals(0,provider.queries)
        fake.allowed=true
        assertTrue(SmsImport.prepareAutomatic(fake));assertEquals(0L,prefs.getLong("start",-1))
        assertEquals(2,SmsImport.incremental(fake));assertEquals(0L,provider.firstFrom)
        assertEquals(0,SmsImport.incremental(fake))
        provider.rows.add(Sms(4,credit,now-1000))
        assertEquals(1,SmsImport.incremental(fake))
        LedgerDb(fake).use {assertEquals(3,it.all().size);assertEquals(2,it.all().count {row->row.recognizedTransaction})}
        val watermark=prefs.getLong("watermark",-1);fake.allowed=false
        try {SmsImport.incremental(fake);fail("READ_SMS revoked")} catch(_:SecurityException){}
        assertEquals(watermark,prefs.getLong("watermark",-2))
        LedgerDb(fake).use {assertEquals(3,it.all().size)}
        fake.allowed=true;assertTrue(SmsImport.prepareAutomatic(fake));assertEquals(0,SmsImport.incremental(fake))
        prefs.edit().putBoolean("enabled",false).commit();val queries=provider.queries
        assertFalse(SmsImport.prepareAutomatic(fake));assertEquals(0,SmsImport.incremental(fake));assertEquals(queries,provider.queries)
        prefs.edit().clear().commit();context.deleteDatabase("autoscan-test-ledger.db")
    }
}
