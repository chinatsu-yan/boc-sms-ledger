package local.boc.ledger
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Color
import android.view.View
class LedgerIcon(context:Context,private val kind:Int):View(context) {
    var active=false;set(value){field=value;invalidate()}
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas);val scale=minOf(width,height)/24f;canvas.save();canvas.translate((width-24*scale)/2,(height-24*scale)/2);canvas.scale(scale,scale)
        val p=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=if(active) Color.rgb(52,74,112) else Color.rgb(146,153,162);style=Paint.Style.STROKE;strokeWidth=1.6f;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
        when(kind) {
            0->{val path=Path();path.moveTo(3f,10f);path.lineTo(12f,3f);path.lineTo(21f,10f);path.lineTo(21f,21f);path.lineTo(15f,21f);path.lineTo(15f,14f);path.lineTo(9f,14f);path.lineTo(9f,21f);path.lineTo(3f,21f);path.close();canvas.drawPath(path,p)}
            1->for(y in listOf(6f,12f,18f)){canvas.drawLine(8f,y,21f,y,p);canvas.drawCircle(3f,y,0.6f,p)}
            2->{canvas.drawRoundRect(2f,4f,22f,20f,3f,3f,p);canvas.drawLine(2f,9f,22f,9f,p);canvas.drawLine(6f,15f,10f,15f,p)}
            else->{canvas.drawCircle(12f,12f,7f,p);canvas.drawCircle(12f,12f,3f,p);for(i in 0..7){canvas.save();canvas.rotate(i*45f,12f,12f);canvas.drawLine(12f,2f,12f,5f,p);canvas.restore()}}
        };canvas.restore()
    }
}
