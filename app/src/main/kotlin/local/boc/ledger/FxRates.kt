package local.boc.ledger

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.Locale

fun interface PublicRateTransport { fun get(path:String):String }
class HttpsRateTransport:PublicRateTransport {
    override fun get(path:String):String {
        val connection=URL("https://api.frankfurter.dev$path").openConnection() as HttpURLConnection
        connection.connectTimeout=7000;connection.readTimeout=7000;connection.instanceFollowRedirects=false
        connection.setRequestProperty("Accept","application/json")
        connection.setRequestProperty("User-Agent","BocSmsLedger")
        try {
            val status=connection.responseCode
            if(status!=200) throw IllegalStateException("公开行情服务 HTTP $status")
            return connection.inputStream.bufferedReader().use {it.readText()}.also {require(it.length<1_000_000)}
        } finally {connection.disconnect()}
    }
}
data class CachedRate(val quote:FxQuote?,val expiresAt:Long,val retryAfter:Long,val error:String?)
class FxCache(context:Context,name:String="fx-cache.db"):SQLiteOpenHelper(context,name,null,1),java.io.Closeable {
    override fun onCreate(db:SQLiteDatabase) {db.execSQL("CREATE TABLE rates(cache_key TEXT PRIMARY KEY, base TEXT, quote TEXT, rate_date TEXT, rate TEXT, source TEXT, fetched_at INTEGER, expires_at INTEGER NOT NULL, retry_after INTEGER NOT NULL, error TEXT)")}
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int) {error("Unsupported FX cache migration")}
    fun load(key:QuoteKey):CachedRate?=readableDatabase.rawQuery("SELECT * FROM rates WHERE cache_key=?",arrayOf(key.cacheKey)).use {c->
        if(!c.moveToFirst()) null else {
            fun s(name:String)=c.getString(c.getColumnIndexOrThrow(name))
            fun n(name:String)=c.getLong(c.getColumnIndexOrThrow(name))
            val quote=if(c.isNull(c.getColumnIndexOrThrow("rate"))) null else FxQuote(s("base"),s("quote"),LocalDate.parse(s("rate_date")),BigDecimal(s("rate")),s("source"),n("fetched_at"))
            CachedRate(quote,n("expires_at"),n("retry_after"),s("error"))
        }
    }
    fun save(key:QuoteKey,value:CachedRate) {
        writableDatabase.insertWithOnConflict("rates",null,ContentValues().apply {
            put("cache_key",key.cacheKey);put("base",key.base);put("quote",key.quote)
            val q=value.quote
            put("rate_date",q?.date?.toString());put("rate",q?.rate?.toPlainString());put("source",q?.source);put("fetched_at",q?.fetchedAt)
            put("expires_at",value.expiresAt);put("retry_after",value.retryAfter);put("error",value.error)
        },SQLiteDatabase.CONFLICT_REPLACE)
    }
}
object PublicRateParser {
    data class Observation(val provider:String,val date:LocalDate)
    fun select(raw:String,key:QuoteKey,today:LocalDate):Observation {
        val objectValue=JSONObject(raw)
        require(objectValue.getString("base")==key.base && objectValue.getString("quote")==key.quote) {"行情币对不一致"}
        val target=key.date ?: today
        val providers=objectValue.optJSONArray("providers") ?: error("没有可核验有效日的来源报价")
        val observations=buildList {
            for(i in 0 until providers.length()) {
                val row=providers.getJSONObject(i);val provider=row.getString("key");val date=LocalDate.parse(row.getString("date"))
                if(provider.matches(Regex("[A-Z0-9_-]{1,30}")) && date<=target && date>=target.minusDays(14)) add(Observation(provider,date))
            }
        }
        return observations.sortedWith(compareByDescending<Observation> {it.date}.thenBy {if(it.provider=="ECB") 0 else 1}.thenBy {it.provider}).firstOrNull() ?: error("近14天没有可核验有效报价")
    }
    fun direct(raw:String,key:QuoteKey,observation:Observation,now:Long):FxQuote {
        val value=JSONObject(raw)
        require(value.getString("base")==key.base && value.getString("quote")==key.quote) {"行情币对不一致"}
        require(LocalDate.parse(value.getString("date"))==observation.date) {"来源报价日不一致"}
        // Read the original numeric token, not JSONObject's Double representation.
        val tokens=Regex("\\\"rate\\\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)(?=\\s*[,}])").findAll(raw).toList()
        require(tokens.size==1) {"报价数字格式不明确"}
        val rate=BigDecimal(tokens.single().groupValues[1])
        require(rate>BigDecimal.ZERO && rate.precision()<=64 && kotlin.math.abs(rate.scale())<=100) {"报价数值无效"}
        return FxQuote(key.base,key.quote,observation.date,rate,"Frankfurter v2（来源键 ${observation.provider}，参考率）",now)
    }
}
class FxRepository(
    private val cache:FxCache,private val transport:PublicRateTransport=HttpsRateTransport(),
    private val clock:()->Long=System::currentTimeMillis,private val network:Boolean=true,
    private val force:Boolean=false,private val cancelled:()->Boolean={false},private var remaining:Int=120
):RateLookup {
    override fun get(key:QuoteKey):RateResult {
        if(cancelled() || Thread.currentThread().isInterrupted) throw InterruptedException()
        val now=clock();val today=java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        val requestedDate=key.date
        if(requestedDate!=null && requestedDate>today) return RateResult.Missing("未来交易日期，未查询汇率")
        val stored=cache.load(key)
        fun cached():RateResult.Available=RateResult.Available(stored!!.quote!!.copy(cached=true,stale=stored.error!=null || now>=stored.expiresAt))
        val veryRecent=stored?.quote?.let {now-it.fetchedAt<60_000L} ?: false
        if(stored?.error!=null && now<stored.retryAfter-14*60*1000L) return if(stored.quote!=null) cached() else RateResult.Missing(stored.error)
        if(stored?.quote!=null && (now<stored.expiresAt && (!force || key.date!=null || veryRecent) || now<stored.retryAfter && !force || !network)) return cached()
        if(stored?.quote==null && stored!=null && now<stored.retryAfter && !force) return RateResult.Missing(stored.error ?: "暂无参考报价")
        if(!network) return RateResult.Missing("离线且本地没有所需报价")
        if(remaining<2) return if(stored?.quote!=null) cached() else RateResult.Missing("本次报价查询尚未完成，稍后回到本页继续")
        fun request(path:String):String {
            if(cancelled() || Thread.currentThread().isInterrupted) throw InterruptedException()
            remaining--;return transport.get(path)
        }
        return try {
            val pair="${key.base.lowercase(Locale.ROOT)}/${key.quote.lowercase(Locale.ROOT)}"
            val date=key.date?.let {"&date=$it"}.orEmpty()
            val observation=PublicRateParser.select(request("/v2/rate/$pair?expand=providers$date"),key,today)
            val raw=request("/v2/providers/${observation.provider.lowercase(Locale.ROOT)}/rate/$pair?date=${observation.date}")
            val quote=PublicRateParser.direct(raw,key,observation,now)
            val ttl=if(key.date==null) 60*60*1000L else 30*24*60*60*1000L
            cache.save(key,CachedRate(quote,now+ttl,0,null))
            RateResult.Available(quote)
        } catch(error:InterruptedException) {throw error}
        catch(_:Exception) {
            if(cancelled() || Thread.currentThread().isInterrupted) throw InterruptedException()
            val reason="公开参考报价暂缺或联网失败"
            cache.save(key,CachedRate(stored?.quote,stored?.expiresAt ?: 0,now+15*60*1000L,reason))
            if(stored?.quote!=null) RateResult.Available(stored.quote.copy(cached=true,stale=true)) else RateResult.Missing(reason)
        }
    }
}
