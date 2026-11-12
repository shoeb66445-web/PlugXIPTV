package app.plugxaura.iptv;

import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.app.Activity;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.view.*;
import android.widget.*;

final class Ui {
    static int BG=0xff10080d,CARD=0xff241017,CARD2=0xff31131c,TEXT=Color.WHITE,MUTED=0xffc7b5bd;
    static final int RED=0xffff3151,GOLD=0xffffc76e;
    static void applyTheme(Context c){boolean light=PlugXApp.prefs(c).getBoolean("light",false);BG=light?0xfff6eef2:0xff10080d;CARD=light?0xffffffff:0xff241017;CARD2=light?0xfffbe4ea:0xff31131c;TEXT=light?0xff27131c:Color.WHITE;MUTED=light?0xff674e58:0xffc7b5bd;if(c instanceof Activity){Activity a=(Activity)c;a.getWindow().setStatusBarColor(BG);a.getWindow().setNavigationBarColor(BG);int flags=a.getWindow().getDecorView().getSystemUiVisibility();flags=light?flags|View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR:flags&~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;if(android.os.Build.VERSION.SDK_INT>=26)flags=light?flags|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR:flags&~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;a.getWindow().getDecorView().setSystemUiVisibility(flags);}}
    static int dp(Context c,float x){return Math.round(x*c.getResources().getDisplayMetrics().density);}
    static GradientDrawable shape(int fill,int stroke,int radius){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(radius);d.setStroke(2,stroke);return d;}
    static LinearLayout column(Context c){LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.VERTICAL);return l;}
    static LinearLayout row(Context c){LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    static TextView text(Context c,String value,int size,boolean bold){TextView t=new TextView(c);t.setText(value);t.setTextColor(TEXT);t.setTextSize(size);t.setTypeface(Typeface.DEFAULT,bold?Typeface.BOLD:Typeface.NORMAL);t.setGravity(Gravity.CENTER_VERTICAL);return t;}
    static TextView muted(Context c,String value,int size){TextView t=text(c,value,size,false);t.setTextColor(MUTED);return t;}
    static String lastSync(Context c){long time=PlugXApp.prefs(c).getLong("last_sync_at",0);return time>0?"Last Sync: "+new java.text.SimpleDateFormat("d MMM yyyy  •  h:mm a",java.util.Locale.getDefault()).format(new java.util.Date(time)):"Last Sync: Waiting for first sync";}
    static Button button(Context c,String label){Button b=new Button(c);b.setText(label);b.setTextColor(TEXT);b.setAllCaps(false);b.setBackground(shape(CARD,0xff663241,dp(c,18)));b.setFocusable(true);b.setPadding(dp(c,12),dp(c,8),dp(c,12),dp(c,8));focus(b,c);return b;}
    static FrameLayout imageCard(Context c,String title,String image){
        FrameLayout card=new FrameLayout(c);card.setFocusable(true);card.setClickable(true);
        boolean light=PlugXApp.prefs(c).getBoolean("light",false);int radius=dp(c,18);card.setBackground(shape(light?0xfff9e5ec:0xff281019,light?0xffc99fab:0xff713349,radius));card.setClipToOutline(true);
        GradientDrawable art=new GradientDrawable(GradientDrawable.Orientation.TL_BR,light?new int[]{0xff84213c,0xff4a132b,0xff21111c}:new int[]{0xff63233c,0xff311328,0xff110910});
        art.setCornerRadius(radius);View backdrop=new View(c);backdrop.setBackground(art);card.addView(backdrop,new FrameLayout.LayoutParams(-1,-1));
        TextView ornament=text(c,"✦",58,true);ornament.setTextColor(0x44ffc76e);ornament.setGravity(Gravity.CENTER);FrameLayout.LayoutParams op=new FrameLayout.LayoutParams(dp(c,100),dp(c,100),Gravity.TOP|Gravity.RIGHT);op.setMargins(0,-dp(c,23),-dp(c,15),0);card.addView(ornament,op);
        ImageView cover=new ImageView(c);cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(cover,new FrameLayout.LayoutParams(-1,-1));
        ImageLoader.into(c,image,cover);
        GradientDrawable shade=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0x0010080d,0x4410080d,0xe810080d});
        View gradient=new View(c);gradient.setBackground(shade);card.addView(gradient,new FrameLayout.LayoutParams(-1,-1));
        TextView kicker=text(c,"PLUG X  /  LIVE",10,true);kicker.setTextColor(0xffffd08b);kicker.setLetterSpacing(.14f);FrameLayout.LayoutParams kp=new FrameLayout.LayoutParams(-1,dp(c,30),Gravity.TOP);kp.setMargins(dp(c,13),dp(c,9),0,0);card.addView(kicker,kp);
        TextView name=text(c,title,18,true);name.setTextColor(Color.WHITE);name.setGravity(Gravity.BOTTOM|Gravity.LEFT);name.setMaxLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setPadding(dp(c,13),0,dp(c,10),dp(c,12));card.addView(name,new FrameLayout.LayoutParams(-1,-1));
        GradientDrawable ring=shape(Color.TRANSPARENT,GOLD,radius);ring.setStroke(dp(c,3),GOLD);ring.setAlpha(0);card.getOverlay().add(ring);
        card.addOnLayoutChangeListener((v,left,top,right,bottom,oldLeft,oldTop,oldRight,oldBottom)->ring.setBounds(dp(c,2),dp(c,2),right-left-dp(c,2),bottom-top-dp(c,2)));
        final ValueAnimator[] ringAnimation={null};
        card.setOnFocusChangeListener((view,on)->{if(ringAnimation[0]!=null)ringAnimation[0].cancel();ringAnimation[0]=ValueAnimator.ofInt(ring.getAlpha(),on?255:0);ringAnimation[0].setDuration(150);ringAnimation[0].addUpdateListener(animation->{ring.setAlpha((Integer)animation.getAnimatedValue());card.invalidate();});ringAnimation[0].start();});
        return card;
    }
    static void focus(View v,Context c){
        if(android.os.Build.VERSION.SDK_INT>=21)v.setStateListAnimator(null);
        v.setElevation(0);v.setFocusable(true);
        boolean light=PlugXApp.prefs(c).getBoolean("light",false);
        int idleStroke=light?0xffc99fab:0xff663241;
        GradientDrawable surface=shape(CARD,idleStroke,dp(c,18));surface.setStroke(dp(c,1),idleStroke);v.setBackground(surface);
        final ValueAnimator[] transition={null};final int[] currentFill={CARD},currentStroke={idleStroke};final float[] currentWidth={1f};
        v.setOnFocusChangeListener((view,on)->{
            if(transition[0]!=null)transition[0].cancel();
            int fromFill=currentFill[0],toFill=on?(light?0xff77233d:0xff8f203c):CARD;
            int fromStroke=currentStroke[0],toStroke=on?GOLD:idleStroke;float fromWidth=currentWidth[0],toWidth=on?3f:1f;
            transition[0]=ValueAnimator.ofFloat(0f,1f);transition[0].setDuration(150);
            ArgbEvaluator colors=new ArgbEvaluator();
            transition[0].addUpdateListener(animation->{float fraction=(Float)animation.getAnimatedValue();currentFill[0]=(Integer)colors.evaluate(fraction,fromFill,toFill);currentStroke[0]=(Integer)colors.evaluate(fraction,fromStroke,toStroke);currentWidth[0]=fromWidth+(toWidth-fromWidth)*fraction;surface.setColor(currentFill[0]);surface.setStroke(dp(c,currentWidth[0]),currentStroke[0]);view.invalidate();});
            transition[0].start();
            if(view instanceof TextView)((TextView)view).setTextColor(on?Color.WHITE:TEXT);
            if(view instanceof Spinner){View selected=((Spinner)view).getSelectedView();if(selected instanceof TextView)((TextView)selected).setTextColor(on?Color.WHITE:TEXT);}
        });
    }
    static LinearLayout.LayoutParams margins(Context c,int w,int h,int m){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(w<0?w:dp(c,w),h<0?h:dp(c,h));p.setMargins(dp(c,m),dp(c,m),dp(c,m),dp(c,m));return p;}
    static EditText edit(Context c,String hint){EditText e=new EditText(c);e.setSingleLine();e.setHint(hint);e.setHintTextColor(MUTED);e.setTextColor(TEXT);e.setBackground(shape(CARD,0xff663241,dp(c,14)));e.setPadding(dp(c,14),dp(c,10),dp(c,14),dp(c,10));focus(e,c);return e;}
    static ArrayAdapter<String> spinnerAdapter(Context c,String[] labels){return new ArrayAdapter<String>(c,android.R.layout.simple_spinner_item,labels){@Override public View getView(int position,View convertView,android.view.ViewGroup parent){TextView v=(TextView)super.getView(position,convertView,parent);v.setText(labels[position]+"   ▾");v.setTextColor(TEXT);v.setTextSize(16);v.setPadding(dp(c,14),0,dp(c,14),0);return v;}@Override public View getDropDownView(int position,View convertView,android.view.ViewGroup parent){TextView v=(TextView)super.getDropDownView(position,convertView,parent);v.setTextColor(TEXT);v.setBackgroundColor(CARD);v.setTextSize(16);v.setPadding(dp(c,16),dp(c,10),dp(c,16),dp(c,10));return v;}};}
    static final class RemoteSpinner extends Spinner {
        private final String[] options;
        RemoteSpinner(Context c,String[] labels){super(c,Spinner.MODE_DROPDOWN);options=labels;setAdapter(spinnerAdapter(c,labels));setFocusable(true);setClickable(true);}
        @Override public boolean performClick(){
            Context c=getContext();android.app.Dialog dialog=new android.app.Dialog(c);
            LinearLayout panel=column(c);panel.setPadding(dp(c,18),dp(c,16),dp(c,18),dp(c,16));panel.setBackground(shape(CARD,GOLD,dp(c,20)));
            TextView heading=text(c,"Choose an option",19,true);panel.addView(heading,margins(c,-1,42,2));
            ScrollView scroll=new ScrollView(c);LinearLayout items=column(c);Button selected=null;Button previous=null;
            for(int i=0;i<options.length;i++){
                final int index=i;Button option=button(c,(i==getSelectedItemPosition()?"✓  ":"    ")+options[i]);option.setGravity(Gravity.CENTER_VERTICAL|Gravity.LEFT);option.setTextSize(16);option.setId(View.generateViewId());
                items.addView(option,margins(c,-1,52,3));option.setOnClickListener(v->{setSelection(index);dialog.dismiss();requestFocus();});
                if(previous!=null){previous.setNextFocusDownId(option.getId());option.setNextFocusUpId(previous.getId());}previous=option;
                if(i==getSelectedItemPosition())selected=option;
            }
            scroll.addView(items);panel.addView(scroll,new LinearLayout.LayoutParams(-1,-1));dialog.setContentView(panel);dialog.show();
            android.view.Window window=dialog.getWindow();if(window!=null){window.setBackgroundDrawableResource(android.R.color.transparent);int screenWidth=c.getResources().getDisplayMetrics().widthPixels,screenHeight=c.getResources().getDisplayMetrics().heightPixels;window.setLayout(Math.min((int)(screenWidth*.88f),dp(c,560)),Math.min((int)(screenHeight*.8f),dp(c,72+options.length*60)));}
            if(selected!=null)selected.requestFocus();else if(items.getChildCount()>0)items.getChildAt(0).requestFocus();
            return true;
        }
        @Override public boolean onKeyDown(int keyCode,KeyEvent event){if(keyCode==KeyEvent.KEYCODE_DPAD_CENTER||keyCode==KeyEvent.KEYCODE_ENTER||keyCode==KeyEvent.KEYCODE_NUMPAD_ENTER){if(event.getRepeatCount()==0)performClick();return true;}return super.onKeyDown(keyCode,event);}
    }
    static void alert(Context c,String title,String message){new androidx.appcompat.app.AlertDialog.Builder(c).setTitle(title).setMessage(message).setPositiveButton("OK",null).show();}
}
