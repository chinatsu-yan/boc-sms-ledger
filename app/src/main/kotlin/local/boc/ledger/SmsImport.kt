package local.boc.ledger

import android.Manifest
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PersistableBundle
import android.provider.Telephony
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object SmsImport {
    fun hasRead(context:Context)=context.checkSelfPermission(Manifest.permission.READ_SMS)==PackageManager.PERMISSION_GRANTED
    fun enabled(context:Context)=context.getSharedPreferences("import",Context.MODE_PRIVATE).getBoolean("enabled",false)
    fun enableAutomatic(context:Context) {
        val prefs=context.getSharedPreferences("import",Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean("enabled",true)
            if(!prefs.contains("start")) putLong("start",0L)
            if(!prefs.contains("watermark")) putLong("watermark",prefs.getLong("start",0L))
        }.apply()
    }
    fun prepareAutomatic(context:Context):Boolean {
        if(!hasRead(context)) return false
        val prefs=context.getSharedPreferences("import",Context.MODE_PRIVATE)
        if(!prefs.contains("enabled")) enableAutomatic(context)
        return enabled(context)
    }
    fun pauseAutomatic(context:Context) {
        context.getSharedPreferences("import",Context.MODE_PRIVATE).edit().putBoolean("enabled",false).apply()
        context.getSystemService(JobScheduler::class.java).cancel(41)
    }
    fun candidate(sender:String,body:String)=sender=="95566" || body.contains("【中国银行】")
    fun scan(context:Context, from:Long, until:Long, cancelled:AtomicBoolean=AtomicBoolean(false)):Int {
        if(!hasRead(context)) throw SecurityException("需要短信读取权限")
        var added=0
        LedgerDb(context).use { db ->
            context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI,arrayOf("_id","address","body","date","sub_id"),"date >= ? AND date <= ?",arrayOf(from.toString(),until.toString()),"date ASC, _id ASC")?.use { c ->
                while(c.moveToNext()) {
                    if(cancelled.get()) throw InterruptedException("已停止导入")
                    val sender=c.getString(1).orEmpty();val body=c.getString(2).orEmpty()
                    if(candidate(sender,body) && db.ingest(c.getString(0),sender,body,c.getLong(3),c.getString(4).orEmpty())) added++
                }
            } ?: error("短信提供方暂不可用，请稍后重试")
        }
        return added
    }
    fun incremental(context:Context,cancelled:AtomicBoolean=AtomicBoolean(false)):Int {
        val prefs=context.getSharedPreferences("import",Context.MODE_PRIVATE)
        if(!prefs.getBoolean("enabled",false)) return 0
        val until=System.currentTimeMillis()
        val start=prefs.getLong("start",until)
        val from=maxOf(start,prefs.getLong("watermark",start)-24*60*60*1000)
        val count=scan(context,from,until,cancelled)
        prefs.edit().putLong("watermark",until).apply()
        return count
    }
    fun schedule(context:Context,attempt:Int=0) {
        val prefs=context.getSharedPreferences("import",Context.MODE_PRIVATE)
        if(!prefs.getBoolean("enabled",false) || !hasRead(context)) return
        val extras=PersistableBundle().apply { putInt("attempt",attempt) }
        val delay=when(attempt) { 0->2000L; 1->10000L; else->30000L }
        context.getSystemService(JobScheduler::class.java).schedule(JobInfo.Builder(41,ComponentName(context,SmsScanJob::class.java)).setMinimumLatency(delay).setOverrideDeadline(delay+15000).setExtras(extras).build())
    }
}
class SmsWakeReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        // Broadcast is only a wake-up. Android's inbox is the canonical source (including multipart assembly).
        // Never create a second transaction from an unidentifiable broadcast body.
        if(intent.action==Telephony.Sms.Intents.SMS_RECEIVED_ACTION) SmsImport.schedule(context)
    }
}
class SmsScanJob:JobService() {
    private val executor=Executors.newSingleThreadExecutor()
    private var cancelled=AtomicBoolean(false)
    override fun onStartJob(params:JobParameters):Boolean {
        val flag=AtomicBoolean(false);cancelled=flag
        executor.execute {
            try {
                SmsImport.incremental(this,flag)
                getSharedPreferences("import",MODE_PRIVATE).edit().remove("background_error").apply()
            } catch(_:SecurityException) {
                getSharedPreferences("import",MODE_PRIVATE).edit().putString("background_error","短信权限已撤销，请在应用内重新授权").apply()
            } catch(_:InterruptedException) { }
            catch(_:Exception) {
                getSharedPreferences("import",MODE_PRIVATE).edit().putString("background_error","后台补扫未完成，请打开应用重试").apply()
            }
            if(!flag.get()) {
                jobFinished(params,false)
                // A broadcast may precede inbox insertion. Two bounded follow-ups rescan an overlap safely.
                val attempt=params.extras.getInt("attempt",0)
                if(attempt<2) SmsImport.schedule(this,attempt+1)
            }
        }
        return true
    }
    override fun onStopJob(params:JobParameters):Boolean { cancelled.set(true); return true }
    override fun onDestroy() { cancelled.set(true);executor.shutdownNow();super.onDestroy() }
}
