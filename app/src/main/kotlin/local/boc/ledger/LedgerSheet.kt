package local.boc.ledger

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*

/** Shared bottom sheet surface for ledger actions, editors, and choices. */
class LedgerSheet private constructor(context:Context,private val builder:Builder):Dialog(context) {
    companion object {private val open=mutableListOf<LedgerSheet>();fun closeAll() {open.toList().reversed().forEach {it.dismiss()}}}
    private val actions=mutableMapOf<Int,Button>()
    private fun dp(n:Int)=(n*context.resources.displayMetrics.density).toInt()
    private fun label(s:CharSequence,size:Float)=TextView(context).apply {text=s;textSize=size;setTextColor(0xff263844.toInt())}
    override fun onCreate(state:android.os.Bundle?) {
        super.onCreate(state)
        val root=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(12),dp(20),dp(18));background=GradientDrawable().apply {setColor(0xfffafaf7.toInt());cornerRadius=dp(24).toFloat()}}
        val handle=View(context).apply {background=GradientDrawable().apply {setColor(0xffd8e1dd.toInt());cornerRadius=dp(2).toFloat()}}
        root.addView(handle,LinearLayout.LayoutParams(dp(32),dp(4)).apply {gravity=Gravity.CENTER;bottomMargin=dp(14)})
        val heading=LinearLayout(context).apply {orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        heading.addView(label("‹",28f).apply {gravity=Gravity.CENTER;contentDescription="返回上一层";setOnClickListener {dismiss()}},LinearLayout.LayoutParams(dp(32),dp(44)))
        heading.addView(label(builder.title,20f).apply {setTypeface(null,Typeface.BOLD)},LinearLayout.LayoutParams(0,-2,1f));root.addView(heading)
        val body=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL;setPadding(0,dp(12),0,dp(12))}
        builder.message?.let {body.addView(label(it,14f).apply {setLineSpacing(dp(4).toFloat(),1f);setTextIsSelectable(true)})}
        builder.items?.forEachIndexed {i,value->body.addView(Button(context).apply {text=value;textSize=14f;isAllCaps=false;gravity=Gravity.START or Gravity.CENTER_VERTICAL;minHeight=dp(52);setPadding(dp(14),dp(12),dp(14),dp(12));setTextColor(0xff355e67.toInt());stateListAnimator=null;background=GradientDrawable().apply {setColor(0xffedf2ee.toInt());cornerRadius=dp(12).toFloat()};setOnClickListener {builder.itemClick?.onClick(this@LedgerSheet,i);if(open.lastOrNull()===this@LedgerSheet) dismiss()}},LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=dp(7)})}
        builder.view?.let {body.addView(it)}
        val scroll=ScrollView(context).apply {isFillViewport=false;addView(body)}
        val maxBody=(context.resources.displayMetrics.heightPixels*.62f).toInt()
        body.measure(View.MeasureSpec.makeMeasureSpec(context.resources.displayMetrics.widthPixels-dp(56),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        root.addView(scroll,LinearLayout.LayoutParams(-1,minOf(body.measuredHeight,maxBody)))
        val footer=LinearLayout(context).apply {orientation=LinearLayout.HORIZONTAL;gravity=Gravity.END}
        for(which in listOf(DialogInterface.BUTTON_NEUTRAL,DialogInterface.BUTTON_NEGATIVE,DialogInterface.BUTTON_POSITIVE)) builder.buttons[which]?.let {(name,listener)->
            val primary=which==DialogInterface.BUTTON_POSITIVE
            val button=Button(context).apply {text=name;textSize=14f;isAllCaps=false;minHeight=dp(48);stateListAnimator=null;setTextColor(if(primary) Color.WHITE else 0xff355e67.toInt());background=GradientDrawable().apply {setColor(if(primary) 0xff355e67.toInt() else 0xffeaf0eb.toInt());cornerRadius=dp(13).toFloat()};setOnClickListener {listener?.onClick(this@LedgerSheet,which);dismiss()}}
            actions[which]=button;footer.addView(button,LinearLayout.LayoutParams(0,-2,1f).apply {leftMargin=dp(5);topMargin=dp(10)})
        }
        root.addView(footer);root.isFocusableInTouchMode=true;setContentView(root);root.requestFocus()
        window!!.apply {setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT));addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);attributes=attributes.apply {dimAmount=.28f};setGravity(Gravity.BOTTOM)}
        setCanceledOnTouchOutside(true)
    }
    override fun dismiss() {open.remove(this);super.dismiss()}
    override fun show() {open.add(this);super.show();window!!.setLayout(context.resources.displayMetrics.widthPixels-dp(16),WindowManager.LayoutParams.WRAP_CONTENT)}
    fun getButton(which:Int):Button=actions.getValue(which)
    class Builder(private val context:Context) {
        var title:CharSequence="详情";private set
        var message:CharSequence?=null;private set
        var view:View?=null;private set
        var items:Array<out String>?=null;private set
        var itemClick:DialogInterface.OnClickListener?=null;private set
        val buttons=mutableMapOf<Int,Pair<String,DialogInterface.OnClickListener?>>()
        fun setTitle(value:CharSequence)=apply {title=value}
        fun setMessage(value:CharSequence)=apply {message=value}
        fun setView(value:View)=apply {view=value}
        fun setItems(value:Array<out String>,click:DialogInterface.OnClickListener)=apply {items=value;itemClick=click}
        fun setPositiveButton(name:String,click:DialogInterface.OnClickListener?)=apply {buttons[DialogInterface.BUTTON_POSITIVE]=name to click}
        fun setNegativeButton(name:String,click:DialogInterface.OnClickListener?)=apply {buttons[DialogInterface.BUTTON_NEGATIVE]=name to click}
        fun setNeutralButton(name:String,click:DialogInterface.OnClickListener?)=apply {buttons[DialogInterface.BUTTON_NEUTRAL]=name to click}
        fun create()=LedgerSheet(context,this)
        fun show()=create().also {it.show()}
    }
}
class LedgerSpinner(context:Context):Spinner(context) {
    override fun performClick():Boolean {LedgerSheet.Builder(context).setTitle("选择").setItems((0 until count).map {getItemAtPosition(it).toString()}.toTypedArray()) {_,i->setSelection(i)}.setNegativeButton("取消",null).show();return true}
}
