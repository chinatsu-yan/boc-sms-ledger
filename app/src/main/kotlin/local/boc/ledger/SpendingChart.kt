package local.boc.ledger
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import java.math.BigDecimal

class SpendingChart(context:Context,private val values:List<Pair<String,BigDecimal>>,private val bars:Boolean=false,private val income:Boolean=false):View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring=RectF()
    companion object {val colors=intArrayOf(0xff497c7c.toInt(),0xff8caba2.toInt(),0xffbdccb7.toInt(),0xffd8bc91.toInt(),0xff91a3ab.toInt(),0xffdfe2db.toInt())}
    init {contentDescription=values.joinToString {"${it.first} ${it.second.toPlainString()}"}}
    override fun onDraw(c:Canvas) {
        super.onDraw(c);val density=resources.displayMetrics.density;val sum=values.fold(BigDecimal.ZERO){s,v->s+v.second};paint.style=Paint.Style.FILL
        if(!bars) {
            val size=minOf(width.toFloat(),height.toFloat())-16*density;val x=width/2f;val y=height/2f
            ring.set(x-size/2,y-size/2,x+size/2,y+size/2);var start=-90f
            if(sum.signum()==0) {paint.color=0xffe5e9e2.toInt();c.drawOval(ring,paint)}
            values.forEachIndexed {i,v->val angle=if(sum.signum()==0) 0f else v.second.divide(sum,8,java.math.RoundingMode.HALF_UP).toFloat()*360;paint.color=colors[i%colors.size];c.drawArc(ring,start,angle,true,paint);start+=angle}
            paint.color=0xfffafaf7.toInt();c.drawCircle(x,y,size/2-20*density,paint)
            paint.color=0xff74848b.toInt();paint.textSize=12*resources.displayMetrics.scaledDensity;paint.textAlign=Paint.Align.CENTER;c.drawText(if(sum.signum()==0) if(income) "暂无收入" else "暂无消费" else "${values.size} 个类别",x,y-2*density,paint);c.drawText(if(income) "收入构成" else "消费构成",x,y+17*density,paint)
        } else {
            val max=values.maxOfOrNull {it.second}?.takeIf {it.signum()>0} ?: BigDecimal.ONE
            val cell=width.toFloat()/values.size.coerceAtLeast(1);val base=height-27*density;val avail=base-26*density
            values.forEachIndexed {i,v->val h=v.second.divide(max,8,java.math.RoundingMode.HALF_UP).toFloat()*avail;paint.color=0xff8eafa7.toInt();val x=cell*i+cell*.22f;c.drawRoundRect(x,base-h,x+cell*.56f,base,5*density,5*density,paint)
                paint.color=0xff74848b.toInt();paint.textAlign=Paint.Align.CENTER;paint.textSize=10*resources.displayMetrics.scaledDensity;c.drawText(v.second.stripTrailingZeros().toPlainString(),cell*(i+.5f),base-h-8*density,paint);c.drawText(v.first,cell*(i+.5f),height-7*density,paint)}
        }
    }
}
