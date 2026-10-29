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
        super.onDraw(canvas);val scale=minOf(width-paddingLeft-paddingRight,height-paddingTop-paddingBottom)/24f;canvas.save();canvas.translate((width-24*scale)/2,(height-24*scale)/2);canvas.scale(scale,scale)
        val p=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=if(active) Color.rgb(53,103,109) else Color.rgb(137,148,151);style=Paint.Style.STROKE;strokeWidth=1.6f;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
        when(kind) {
            0->{val path=Path();path.moveTo(3f,10f);path.lineTo(12f,3f);path.lineTo(21f,10f);path.lineTo(21f,21f);path.lineTo(15f,21f);path.lineTo(15f,14f);path.lineTo(9f,14f);path.lineTo(9f,21f);path.lineTo(3f,21f);path.close();canvas.drawPath(path,p)}
            1->for(y in listOf(6f,12f,18f)){canvas.drawLine(8f,y,21f,y,p);canvas.drawCircle(3f,y,0.6f,p)}
            2->{canvas.drawRoundRect(2f,4f,22f,20f,3f,3f,p);canvas.drawLine(2f,9f,22f,9f,p);canvas.drawLine(6f,15f,10f,15f,p)}
            4->{canvas.drawRect(3f,12f,7f,21f,p);canvas.drawRect(10f,4f,14f,21f,p);canvas.drawRect(17f,8f,21f,21f,p)}
            5->{canvas.drawRoundRect(4f,7f,16f,21f,3f,3f,p);canvas.drawArc(13f,8f,22f,15f,-90f,180f,false,p);canvas.drawLine(7f,2f,7f,4f,p);canvas.drawLine(12f,2f,12f,4f,p)}
            6->{canvas.drawRoundRect(4f,7f,20f,21f,2f,2f,p);canvas.drawArc(8f,2f,16f,12f,180f,180f,false,p)}
            7->{canvas.drawRoundRect(5f,3f,19f,18f,3f,3f,p);canvas.drawLine(5f,10f,19f,10f,p);canvas.drawLine(7f,21f,9f,18f,p);canvas.drawLine(17f,21f,15f,18f,p)}
            8->{canvas.drawLine(3f,11f,12f,3f,p);canvas.drawLine(12f,3f,21f,11f,p);canvas.drawRect(5f,11f,19f,21f,p)}
            9->{canvas.drawArc(4f,4f,20f,20f,10f,290f,false,p);canvas.drawLine(16f,3f,16f,8f,p);canvas.drawLine(16f,8f,21f,8f,p)}
            10->{canvas.drawLine(12f,3f,21f,12f,p);canvas.drawLine(21f,12f,12f,21f,p);canvas.drawLine(12f,21f,3f,12f,p);canvas.drawLine(3f,12f,12f,3f,p)}
            else->{canvas.drawCircle(12f,12f,7f,p);canvas.drawCircle(12f,12f,3f,p);for(i in 0..7){canvas.save();canvas.rotate(i*45f,12f,12f);canvas.drawLine(12f,2f,12f,5f,p);canvas.restore()}}
        };canvas.restore()
    }
}
