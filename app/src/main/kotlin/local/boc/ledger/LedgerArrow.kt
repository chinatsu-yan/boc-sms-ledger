package local.boc.ledger
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

class LedgerArrow(private val direction:Int=0):Drawable() {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=0xff355e67.toInt();strokeWidth=1.8f;strokeCap=Paint.Cap.ROUND;style=Paint.Style.STROKE}
    override fun draw(c:Canvas) {
        c.save();c.translate(bounds.left.toFloat(),bounds.top.toFloat());c.scale(bounds.width()/16f,bounds.height()/16f)
        if(direction!=0)c.rotate(if(direction<0)90f else -90f,8f,8f)
        c.drawLine(4f,6f,8f,10f,paint);c.drawLine(8f,10f,12f,6f,paint);c.restore()
    }
    override fun setAlpha(alpha:Int){paint.alpha=alpha}
    override fun setColorFilter(filter:ColorFilter?){paint.colorFilter=filter}
    @Deprecated("Drawable opacity is no longer used") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
