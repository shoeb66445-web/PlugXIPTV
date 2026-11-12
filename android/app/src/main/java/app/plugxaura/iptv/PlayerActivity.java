package app.plugxaura.iptv;

import android.content.*;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.*;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.Tracks;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.datasource.*;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.*;
import androidx.media3.exoplayer.drm.*;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.ui.PlayerView;
import com.google.gson.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import okhttp3.*;

public class PlayerActivity extends AppCompatActivity {
    private final Handler handler=new Handler();
    private ArrayList<JsonObject> channels=new ArrayList<>();
    private int index=-1,playlistId,categoryId,generation=0,retries=0;
    private boolean favorites;
    private ExoPlayer player;
    private DefaultTrackSelector trackSelector;
    private PlayerView view;
    private TextView title,status,live,gesturePercent;
    private ImageView watermark;
    private ProgressBar loading;
    private FrameLayout videoBox;
    private RecyclerViewList channelList;
    private LinearLayout listPanel,headerBar;
    private LinearLayout playerActions;
    private Button favoriteButton,listButton,fullButton,qualityButton,langButton;
    private final ArrayList<Button> headerControls=new ArrayList<>(),videoControls=new ArrayList<>();
    private boolean fullScreen=false,listVisible=true,stoppedForBackground=false,enteringPip=false;
    private int aspectMode=0,normalSystemUi;
    private String currentId="";
    private JsonObject currentChannel;
    private boolean maintenanceShown=false;
    private OkHttpClient sharedHttp;
    private String sharedProxyConfig="";

    // Tracks currently available from stream
    private final List<TrackInfo> videoTracks=new ArrayList<>();
    private final List<TrackInfo> audioTracks=new ArrayList<>();

    private static class TrackInfo {
        final int groupIndex, trackIndex;
        final String label;
        TrackInfo(int g,int t,String l){groupIndex=g;trackIndex=t;label=l;}
    }

    private final Runnable presence=new Runnable(){public void run(){if(currentChannel!=null)sendHeartbeat(currentChannel);handler.postDelayed(this,60000);}};
    private final Runnable refresh=new Runnable(){public void run(){PlugXApp.request(PlayerActivity.this,"bootstrap",null,(result,error)->{if(error==null){PlugXApp.catalog(PlayerActivity.this,result);rebuildList();applyPlayerSettings();if(maintenanceEnabled())showMaintenance();}});handler.postDelayed(this,240000);}};
    private final Runnable hideChannelList=new Runnable(){public void run(){if(listPanel==null||fullScreen||!listVisible)return;listVisible=false;listPanel.setVisibility(View.GONE);if(view!=null)view.requestFocus();}};

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        Ui.applyTheme(this);
        normalSystemUi=getWindow().getDecorView().getSystemUiVisibility()&~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if(Build.VERSION.SDK_INT>=26)normalSystemUi&=~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(normalSystemUi);
        getWindow().setStatusBarColor(0xff000000);
        getWindow().setNavigationBarColor(0xff000000);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        playlistId=getIntent().getIntExtra("playlist_id",0);
        categoryId=getIntent().getIntExtra("category_id",0);
        favorites=getIntent().getBooleanExtra("favorites",false);
        buildUi();
        rebuildList();
        int id=b!=null?b.getInt("channel_id",0):getIntent().getIntExtra("channel_id",0);
        for(int i=0;i<channels.size();i++)if(PlugXApp.integer(channels.get(i),"id",0)==id){index=i;break;}
        if(index<0&&channels.size()>0)index=0;
        if(index>=0)play(index);
        handler.postDelayed(refresh,240000);
    }

    @Override protected void onSaveInstanceState(Bundle b){b.putInt("channel_id",PlugXApp.integer(currentChannel,"id",0));super.onSaveInstanceState(b);}

    private void buildUi(){
        boolean landscape=getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE;
        headerControls.clear();videoControls.clear();
        LinearLayout outer=Ui.column(this);outer.setBackgroundColor(Ui.BG);setContentView(outer);
        LinearLayout body=new LinearLayout(this);body.setOrientation(landscape?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);outer.addView(body,new LinearLayout.LayoutParams(-1,-1));
        listPanel=Ui.column(this);listPanel.setBackground(Ui.shape(Ui.CARD,0xff6e2939,Ui.dp(this,12)));
        TextView listTitle=Ui.text(this,"CHANNELS",14,true);listTitle.setTextColor(Ui.GOLD);listPanel.addView(listTitle,Ui.margins(this,-1,32,8));
        channelList=new RecyclerViewList(this);
        channelList.setOnTouchListener((v,event)->{if(event.getActionMasked()==MotionEvent.ACTION_DOWN||event.getActionMasked()==MotionEvent.ACTION_MOVE)scheduleListHide();return false;});
        listPanel.addView(channelList,new LinearLayout.LayoutParams(-1,0,1));
        videoBox=new FrameLayout(this);videoBox.setBackgroundColor(0xff000000);
        view=new PlayerView(this);view.setUseController(false);view.setFocusable(true);
        view.setContentDescription("Live video. Up and down change channels. OK opens player controls.");
        view.setId(View.generateViewId());
        view.setOnFocusChangeListener((v,focused)->{if(focused)showControls();});
        videoBox.addView(view,new FrameLayout.LayoutParams(-1,-1));
        watermark=new ImageView(this);watermark.setScaleType(ImageView.ScaleType.FIT_CENTER);
        videoBox.addView(watermark,new FrameLayout.LayoutParams(Ui.dp(this,90),Ui.dp(this,60),Gravity.TOP|Gravity.RIGHT));
        loading=new ProgressBar(this);loading.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.GOLD));
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(Ui.dp(this,36),Ui.dp(this,36),Gravity.CENTER);lp.bottomMargin=Ui.dp(this,35);
        videoBox.addView(loading,lp);
        status=Ui.text(this,"Loading…",14,true);status.setGravity(Gravity.CENTER);status.setTextColor(-1);
        status.setShadowLayer(Ui.dp(this,4),0,Ui.dp(this,1),0xff000000);
        FrameLayout.LayoutParams sp=new FrameLayout.LayoutParams(-1,-2,Gravity.CENTER);
        sp.topMargin=Ui.dp(this,42);sp.setMargins(Ui.dp(this,20),Ui.dp(this,42),Ui.dp(this,20),0);
        videoBox.addView(status,sp);
        applyPlayerSettings();
        if(landscape){body.addView(listPanel,new LinearLayout.LayoutParams(0,-1,0.3f));body.addView(videoBox,new LinearLayout.LayoutParams(0,-1,0.7f));}
        else{body.addView(videoBox,new LinearLayout.LayoutParams(-1,0,0.42f));body.addView(listPanel,new LinearLayout.LayoutParams(-1,0,0.58f));}
        listPanel.setVisibility(fullScreen||!listVisible?View.GONE:View.VISIBLE);

        headerBar=Ui.row(this);headerBar.setGravity(Gravity.CENTER_VERTICAL);
        headerBar.setPadding(Ui.dp(this,5),Ui.dp(this,3),Ui.dp(this,5),Ui.dp(this,3));
        headerBar.setBackground(overlayShade(true));
        FrameLayout.LayoutParams hp=new FrameLayout.LayoutParams(-1,Ui.dp(this,60),Gravity.TOP);
        videoBox.addView(headerBar,hp);

        control(headerBar,"‹","Back",this::finish);
        title=Ui.text(this,"Live TV",landscape?17:14,true);title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);title.setTextColor(-1);
        headerBar.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        live=Ui.text(this,"● LIVE",12,true);live.setTextColor(0xffff4d65);
        headerBar.addView(live,Ui.margins(this,52,38,0));
        android.app.UiModeManager mode=(android.app.UiModeManager)getSystemService(UI_MODE_SERVICE);
        if(Build.VERSION.SDK_INT>=26&&mode!=null&&mode.getCurrentModeType()!=Configuration.UI_MODE_TYPE_TELEVISION)
            control(headerBar,"▣","Floating player",this::floatPlayer);
        control(headerBar,"⋮","More controls",this::moreControls);

        playerActions=Ui.row(this);playerActions.setGravity(Gravity.CENTER_VERTICAL);
        playerActions.setPadding(Ui.dp(this,4),Ui.dp(this,10),Ui.dp(this,4),Ui.dp(this,3));
        playerActions.setBackground(overlayShade(false));
        FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(-1,Ui.dp(this,64),Gravity.BOTTOM);
        videoBox.addView(playerActions,cp);

        control(playerActions,"","Previous channel",()->change(-1));
        control(playerActions,"","Next channel",()->change(1));
        listButton=control(playerActions,"","Channel list",()->{
            listVisible=!listVisible;
            listPanel.setVisibility(fullScreen?View.GONE:listVisible?View.VISIBLE:View.GONE);
            if(listVisible)scheduleListHide();else handler.removeCallbacks(hideChannelList);
        });
        favoriteButton=control(playerActions,"","Favorite",()->{if(currentChannel!=null){favorite(currentChannel);updateFavoriteButton();}});
        // Quality button — shows real stream quality label
        qualityButton=control(playerActions,"HQ","Picture quality",this::chooseQuality);
        langButton=control(playerActions,"LANG","Language",this::chooseAudio);
        control(playerActions,"","Picture fit",this::cycleAspect);
        fullButton=control(playerActions,"","Fullscreen",()->setFullscreen(!fullScreen));
        wireControlFocus();

        gesturePercent=Ui.text(this,"",22,true);gesturePercent.setTextColor(-1);gesturePercent.setGravity(Gravity.CENTER);
        gesturePercent.setBackground(Ui.shape(0xe0130e15,Ui.GOLD,Ui.dp(this,20)));
        gesturePercent.setPadding(Ui.dp(this,18),Ui.dp(this,12),Ui.dp(this,18),Ui.dp(this,12));
        FrameLayout.LayoutParams gp=new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER);
        videoBox.addView(gesturePercent,gp);gesturePercent.setVisibility(View.GONE);

        handler.post(new Runnable(){boolean bright=true;public void run(){if(isFinishing())return;live.setAlpha(bright?1f:.52f);bright=!bright;handler.postDelayed(this,800);}});
        view.setOnTouchListener(new PlayerGestures());
        showControls();
        if(listVisible)scheduleListHide();
    }

    private android.graphics.drawable.GradientDrawable overlayShade(boolean top){
        return new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                top?new int[]{0xe5000000,0x88000000,0x00000000}:new int[]{0x00000000,0x88000000,0xe5000000});
    }

    private class PlayerGestures implements View.OnTouchListener {
        float x,y,brightness;int volume;boolean vertical;
        public boolean onTouch(View target,MotionEvent event){
            AudioManager audio=(AudioManager)getSystemService(AUDIO_SERVICE);
            switch(event.getActionMasked()){
                case MotionEvent.ACTION_DOWN:
                    x=event.getX();y=event.getY();vertical=false;
                    WindowManager.LayoutParams p=getWindow().getAttributes();
                    brightness=p.screenBrightness>=0?p.screenBrightness:readBrightness();
                    volume=audio.getStreamVolume(AudioManager.STREAM_MUSIC);return true;
                case MotionEvent.ACTION_MOVE:
                    float dx=event.getX()-x,dy=event.getY()-y;
                    if(!vertical&&Math.abs(dy)>Ui.dp(PlayerActivity.this,12)&&Math.abs(dy)>Math.abs(dx))vertical=true;
                    if(vertical){
                        float fraction=-dy/Math.max(1f,view.getHeight()*.72f);
                        if(x<view.getWidth()/2f){
                            float value=Math.max(.01f,Math.min(1f,brightness+fraction));
                            WindowManager.LayoutParams w=getWindow().getAttributes();w.screenBrightness=value;getWindow().setAttributes(w);
                            percent("☀",Math.round(value*100));
                        }else{
                            int max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                            int value=Math.max(0,Math.min(max,volume+Math.round(fraction*max)));
                            audio.setStreamVolume(AudioManager.STREAM_MUSIC,value,0);
                            percent("♪",Math.round(value*100f/Math.max(1,max)));
                        }
                    }return true;
                case MotionEvent.ACTION_UP:
                    float travel=event.getX()-x;
                    if(vertical){handler.postDelayed(()->{if(gesturePercent!=null)gesturePercent.setVisibility(View.GONE);},850);}
                    else if(Math.abs(travel)>Ui.dp(PlayerActivity.this,65))change(travel<0?1:-1);
                    else if(event.getEventTime()-event.getDownTime()>500&&index>=0){favorite(channels.get(index));updateFavoriteButton();}
                    else showControls();
                    return true;
                case MotionEvent.ACTION_CANCEL:if(gesturePercent!=null)gesturePercent.setVisibility(View.GONE);return true;
                default:return true;
            }
        }
    }

    private float readBrightness(){try{return Math.max(.01f,android.provider.Settings.System.getInt(getContentResolver(),android.provider.Settings.System.SCREEN_BRIGHTNESS)/255f);}catch(Exception ignored){return .5f;}}
    private void percent(String icon,int value){if(gesturePercent==null)return;gesturePercent.setText(icon+"  "+value+"%");gesturePercent.setVisibility(View.VISIBLE);}
    private void scheduleListHide(){handler.removeCallbacks(hideChannelList);if(listVisible&&!fullScreen)handler.postDelayed(hideChannelList,10000);}

    private void rebuildList(){
        JsonObject data=PlugXApp.catalog();if(data==null)return;
        String id=currentId;channels.clear();
        Set<String> saved=PlugXApp.prefs(this).getStringSet("favorites",Collections.emptySet());
        for(JsonElement e:PlugXApp.array(data,"channels")){
            JsonObject o=e.getAsJsonObject();
            if(playlistId>0&&PlugXApp.integer(o,"playlist_id",0)!=playlistId)continue;
            if(categoryId>0&&PlugXApp.integer(o,"category_id",0)!=categoryId)continue;
            if(favorites&&!saved.contains(PlugXApp.str(o,"id","")))continue;
            channels.add(o);
        }
        index=-1;
        if(!id.isEmpty())for(int i=0;i<channels.size();i++)if(PlugXApp.str(channels.get(i),"id","").equals(id)){index=i;currentChannel=channels.get(i);break;}
        channelList.update(channels);
    }

    private void play(int next){
        if(next<0||next>=channels.size())return;
        index=next;JsonObject channel=channels.get(index);currentChannel=channel;
        currentId=PlugXApp.str(channel,"id","");generation++;retries=0;
        startPlayback(channel,generation);channelList.select(index);
        updateFavoriteButton();sendHeartbeat(channel);scheduleListHide();
    }

    private Button control(LinearLayout row,String symbol,String description,Runnable action){
        ControlIcon icon=(description.equals("Picture quality")||description.equals("HQ")||description.equals("Language"))?null:new ControlIcon(description);
        Button b=new Button(this){@Override protected void onDraw(Canvas canvas){super.onDraw(canvas);if(icon!=null){int size=Ui.dp(PlayerActivity.this,22);icon.setBounds((getWidth()-size)/2,(getHeight()-size)/2,(getWidth()+size)/2,(getHeight()+size)/2);icon.draw(canvas);}}};
        b.setAllCaps(false);b.setText(icon==null?symbol:"");b.setTextSize(description.equals("Language")?9:description.equals("Picture quality")?11:14);
        b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setTextColor(-1);b.setPadding(0,0,0,0);
        b.setMinWidth(0);b.setMinimumWidth(0);b.setContentDescription(description);
        b.setBackground(Ui.shape(0x33000000,0x00ffffff,Ui.dp(this,30)));b.setFocusable(true);b.setId(View.generateViewId());b.setTag(icon);
        if(Build.VERSION.SDK_INT>=21)b.setStateListAnimator(null);
        b.setOnFocusChangeListener((v,yes)->{
            b.setTextColor(yes?0xff240c16:-1);
            if(icon!=null)icon.setColor(yes?0xff240c16:Color.WHITE);
            android.graphics.drawable.GradientDrawable surface=Ui.shape(yes?Ui.GOLD:0x33000000,yes?0xffffe5a8:0x00ffffff,Ui.dp(this,30));
            surface.setStroke(Ui.dp(this,yes?3:1),yes?0xffffe5a8:0x00ffffff);b.setBackground(surface);b.invalidate();
            if(yes)showControls();
        });
        LinearLayout.LayoutParams p=row==headerBar?new LinearLayout.LayoutParams(Ui.dp(this,42),Ui.dp(this,42)):new LinearLayout.LayoutParams(0,Ui.dp(this,48),1);
        p.setMargins(Ui.dp(this,2),0,Ui.dp(this,2),0);row.addView(b,p);
        if(row==headerBar)headerControls.add(b);else videoControls.add(b);
        b.setOnClickListener(v->{action.run();showControls();});
        return b;
    }

    private void wireControlFocus(){
        if(headerControls.isEmpty()||videoControls.isEmpty())return;
        view.setNextFocusUpId(headerControls.get(0).getId());view.setNextFocusDownId(videoControls.get(0).getId());
        for(int i=0;i<headerControls.size();i++){Button b=headerControls.get(i);b.setNextFocusLeftId(headerControls.get(Math.max(0,i-1)).getId());b.setNextFocusRightId(headerControls.get(Math.min(headerControls.size()-1,i+1)).getId());b.setNextFocusDownId(videoControls.get(Math.min(i,videoControls.size()-1)).getId());}
        for(int i=0;i<videoControls.size();i++){Button b=videoControls.get(i);b.setNextFocusLeftId(videoControls.get(Math.max(0,i-1)).getId());b.setNextFocusRightId(videoControls.get(Math.min(videoControls.size()-1,i+1)).getId());b.setNextFocusUpId(headerControls.get(Math.min(i,headerControls.size()-1)).getId());}
    }

    private static final class ControlIcon extends Drawable {
        private final String name;private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);private int color=Color.WHITE;private boolean filled;
        ControlIcon(String label){name=label;}void setColor(int value){color=value;invalidateSelf();}void setFilled(boolean value){filled=value;invalidateSelf();}
        @Override public void draw(Canvas canvas){
            Rect b=getBounds();canvas.save();canvas.translate(b.left,b.top);canvas.scale(b.width()/24f,b.height()/24f);
            p.setColor(color);p.setStrokeWidth(2.2f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);p.setStyle(Paint.Style.STROKE);
            switch(name){
                case "Back":canvas.drawLine(20,12,5,12,p);canvas.drawLine(5,12,12,5,p);canvas.drawLine(5,12,12,19,p);break;
                case "Previous channel":case "Next channel":{boolean next=name.startsWith("Next");Path triangle=new Path();if(next){triangle.moveTo(5,4);triangle.lineTo(17,12);triangle.lineTo(5,20);triangle.close();canvas.drawLine(19,4,19,20,p);}else{triangle.moveTo(19,4);triangle.lineTo(7,12);triangle.lineTo(19,20);triangle.close();canvas.drawLine(5,4,5,20,p);}p.setStyle(Paint.Style.FILL);canvas.drawPath(triangle,p);break;}
                case "Channel list":for(int y=6;y<=18;y+=6){canvas.drawCircle(4,y,1,p);canvas.drawLine(9,y,21,y,p);}break;
                case "Favorite":{Path star=new Path();for(int i=0;i<10;i++){double angle=Math.PI*i/5-Math.PI/2;float r=i%2==0?10:4.7f,x=12+(float)Math.cos(angle)*r,y=12+(float)Math.sin(angle)*r;if(i==0)star.moveTo(x,y);else star.lineTo(x,y);}star.close();p.setStyle(filled?Paint.Style.FILL:Paint.Style.STROKE);canvas.drawPath(star,p);break;}
                case "Picture fit":canvas.drawRect(3,5,21,19,p);canvas.drawLine(7,9,10,9,p);canvas.drawLine(7,9,7,12,p);canvas.drawLine(17,15,14,15,p);canvas.drawLine(17,15,17,12,p);break;
                case "Fullscreen":case "Exit fullscreen":canvas.drawLine(3,9,3,3,p);canvas.drawLine(3,3,9,3,p);canvas.drawLine(15,3,21,3,p);canvas.drawLine(21,3,21,9,p);canvas.drawLine(3,15,3,21,p);canvas.drawLine(3,21,9,21,p);canvas.drawLine(15,21,21,21,p);canvas.drawLine(21,21,21,15,p);break;
                case "More controls":p.setStyle(Paint.Style.FILL);canvas.drawCircle(12,5,2,p);canvas.drawCircle(12,12,2,p);canvas.drawCircle(12,19,2,p);break;
                case "Floating player":canvas.drawRect(2,4,22,20,p);canvas.drawRect(11,11,20,18,p);break;
                default:canvas.drawCircle(12,12,9,p);
            }
            canvas.restore();
        }
        @Override public void setAlpha(int alpha){p.setAlpha(alpha);invalidateSelf();}
        @Override public void setColorFilter(android.graphics.ColorFilter filter){p.setColorFilter(filter);invalidateSelf();}
        @Override public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
    }

    private final Runnable hideControls=()->{if(playerActions!=null&&!playerActions.hasFocus()&&headerBar!=null&&!headerBar.hasFocus()){playerActions.setVisibility(View.GONE);headerBar.setVisibility(View.GONE);}};
    private void showControls(){if(playerActions==null||headerBar==null)return;if(Build.VERSION.SDK_INT>=26&&isInPictureInPictureMode())return;playerActions.setVisibility(View.VISIBLE);headerBar.setVisibility(View.VISIBLE);handler.removeCallbacks(hideControls);handler.postDelayed(hideControls,6000);}

    private void setFullscreen(boolean enabled){
        fullScreen=enabled;
        listPanel.setVisibility(enabled?View.GONE:listVisible?View.VISIBLE:View.GONE);
        int flags=normalSystemUi;
        if(enabled)flags|=View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        getWindow().getDecorView().setSystemUiVisibility(flags);
        fullButton.setContentDescription(enabled?"Exit fullscreen":"Fullscreen");
        android.app.UiModeManager mode=(android.app.UiModeManager)getSystemService(UI_MODE_SERVICE);
        boolean tv=mode!=null&&mode.getCurrentModeType()==Configuration.UI_MODE_TYPE_TELEVISION;
        if(!tv)setRequestedOrientation(enabled?ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE:ActivityInfo.SCREEN_ORIENTATION_SENSOR);
        showControls();
        if(enabled&&tv)handler.post(()->{if(fullScreen&&view!=null){view.requestFocus();handler.removeCallbacks(hideControls);handler.postDelayed(hideControls,1600);}});
        else if(!enabled&&listVisible)scheduleListHide();
    }

    private void floatPlayer(){
        if(Build.VERSION.SDK_INT<26)return;
        try{enteringPip=true;boolean entered=enterPictureInPictureMode(new android.app.PictureInPictureParams.Builder().setAspectRatio(new android.util.Rational(16,9)).build());if(!entered){enteringPip=false;Ui.alert(this,"Floating player","Floating playback is unavailable on this device.");}}
        catch(Exception e){enteringPip=false;Ui.alert(this,"Floating player","This device does not support floating playback.");}
    }

    private void updateFavoriteButton(){
        if(favoriteButton==null)return;
        String id=PlugXApp.str(currentChannel,"id","");
        boolean added=PlugXApp.prefs(this).getStringSet("favorites",Collections.emptySet()).contains(id);
        if(favoriteButton.getTag() instanceof ControlIcon)((ControlIcon)favoriteButton.getTag()).setFilled(added);
        favoriteButton.invalidate();favoriteButton.setContentDescription(added?"Remove favorite":"Add favorite");
    }

    private void cycleAspect(){
        aspectMode=(aspectMode+1)%3;
        view.setResizeMode(aspectMode==1?AspectRatioFrameLayout.RESIZE_MODE_FILL:aspectMode==2?AspectRatioFrameLayout.RESIZE_MODE_ZOOM:AspectRatioFrameLayout.RESIZE_MODE_FIT);
        Toast.makeText(this,aspectMode==0?"Fit":aspectMode==1?"Fill":"Zoom",Toast.LENGTH_SHORT).show();
    }

    /** FIX: Real quality selection — reads actual video tracks from ExoPlayer, falls back to resolution cap setting */
    private void chooseQuality(){
        // Build real video track list from currently playing stream
        List<TrackInfo> realTracks=new ArrayList<>(videoTracks);

        if(realTracks.isEmpty()){
            // Stream has no selectable video tracks (single-quality or not loaded yet)
            // Fall back to resolution-cap mode
            String[] labels={"Auto (recommended)","360p","480p","720p HD","1080p Full HD","1440p QHD","4K / 2160p"};
            int[] heights={0,360,480,720,1080,1440,2160};
            int savedHeight=PlugXApp.prefs(this).getInt("quality",0);
            int currentIdx=0;for(int i=0;i<heights.length;i++)if(heights[i]==savedHeight){currentIdx=i;break;}
            new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Picture quality")
                .setSingleChoiceItems(labels,currentIdx,(dialog,which)->{
                    PlugXApp.prefs(this).edit().putInt("quality",heights[which]).apply();
                    applyQuality();
                    updateQualityButtonLabel();
                    dialog.dismiss();
                    Toast.makeText(this,"Quality: "+labels[which],Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Close",null).show();
            return;
        }

        // Stream has multiple real quality tracks — show them
        String[] labels=new String[realTracks.size()+1];
        labels[0]="Auto (recommended)";
        for(int i=0;i<realTracks.size();i++)labels[i+1]=realTracks.get(i).label;

        // Find currently overridden track
        int selected=0;
        if(trackSelector!=null){
            DefaultTrackSelector.Parameters params=trackSelector.getParameters();
            // If override exists find it
            if(!params.overrides.isEmpty())selected=1; // just highlight first override roughly
        }

        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Picture quality")
            .setSingleChoiceItems(labels,selected,(dialog,which)->{
                if(which==0){
                    // Auto — clear overrides
                    if(trackSelector!=null){
                        trackSelector.setParameters(trackSelector.buildUponParameters()
                            .clearVideoSizeConstraints()
                            .setMaxVideoBitrate(Integer.MAX_VALUE)
                            .build());
                    }
                    PlugXApp.prefs(this).edit().putInt("quality",0).apply();
                    updateQualityButtonLabel();
                    Toast.makeText(this,"Quality: Auto",Toast.LENGTH_SHORT).show();
                }else{
                    TrackInfo t=realTracks.get(which-1);
                    if(trackSelector!=null&&player!=null){
                        Tracks currentTracks=player.getCurrentTracks();
                        List<Tracks.Group> videoGroups=new ArrayList<>();
                        for(Tracks.Group g:currentTracks.getGroups())if(g.getType()==C.TRACK_TYPE_VIDEO)videoGroups.add(g);
                        if(t.groupIndex<videoGroups.size()){
                            Tracks.Group group=videoGroups.get(t.groupIndex);
                            DefaultTrackSelector.Parameters.Builder builder=trackSelector.buildUponParameters();
                            builder.setOverrideForType(new TrackSelectionOverride(group.getMediaTrackGroup(),t.trackIndex));
                            trackSelector.setParameters(builder.build());
                        }
                    }
                    updateQualityButtonLabel();
                    Toast.makeText(this,"Quality: "+t.label,Toast.LENGTH_SHORT).show();
                }
                dialog.dismiss();
            })
            .setNegativeButton("Close",null).show();
    }

    /** FIX: Language/audio track selection */
    private void chooseAudio(){
        if(audioTracks.isEmpty()){Toast.makeText(this,"No alternate audio tracks available.",Toast.LENGTH_SHORT).show();return;}
        String[] labels=new String[audioTracks.size()+1];
        labels[0]="Auto (default)";
        for(int i=0;i<audioTracks.size();i++)labels[i+1]=audioTracks.get(i).label;
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Language / Audio track")
            .setSingleChoiceItems(labels,0,(dialog,which)->{
                if(which==0){
                    if(trackSelector!=null)trackSelector.setParameters(trackSelector.buildUponParameters().clearOverridesOfType(C.TRACK_TYPE_AUDIO).build());
                    Toast.makeText(this,"Audio: Auto",Toast.LENGTH_SHORT).show();
                }else{
                    TrackInfo t=audioTracks.get(which-1);
                    if(trackSelector!=null&&player!=null){
                        Tracks currentTracks=player.getCurrentTracks();
                        List<Tracks.Group> audioGroups=new ArrayList<>();
                        for(Tracks.Group g:currentTracks.getGroups())if(g.getType()==C.TRACK_TYPE_AUDIO)audioGroups.add(g);
                        if(t.groupIndex<audioGroups.size()){
                            Tracks.Group group=audioGroups.get(t.groupIndex);
                            DefaultTrackSelector.Parameters.Builder builder=trackSelector.buildUponParameters();
                            builder.setOverrideForType(new TrackSelectionOverride(group.getMediaTrackGroup(),t.trackIndex));
                            trackSelector.setParameters(builder.build());
                        }
                    }
                    Toast.makeText(this,"Audio: "+t.label,Toast.LENGTH_SHORT).show();
                }
                dialog.dismiss();
                // Update button label after a short delay so player can apply the track switch
                handler.postDelayed(()->updateLangButtonLabel(),600);
            })
            .setNegativeButton("Close",null).show();
    }

    /** Updates language button label to show active audio language */
    private void updateLangButtonLabel(){
        if(langButton==null)return;
        if(audioTracks.isEmpty()){langButton.setText("LANG");langButton.setAlpha(0.45f);return;}
        langButton.setAlpha(1f);
        // Try to find the currently selected audio format
        String label="LANG";
        if(player!=null){
            Format f=player.getAudioFormat();
            if(f!=null&&f.language!=null&&!f.language.isEmpty()){
                java.util.Locale locale=new java.util.Locale(f.language);
                String name=locale.getDisplayLanguage(java.util.Locale.ENGLISH);
                // Shorten to 3-4 chars max for button
                label=name.isEmpty()||name.equals(f.language)?f.language.toUpperCase(java.util.Locale.ROOT).substring(0,Math.min(4,f.language.length())):name.length()>4?name.substring(0,4).toUpperCase(java.util.Locale.ROOT):name.toUpperCase(java.util.Locale.ROOT);
            }else if(audioTracks.size()>1){
                label=audioTracks.size()+"AUD";
            }
        }
        langButton.setText(label);
    }

    /** Updates quality button label to show real current quality */
    private void updateQualityButtonLabel(){
        if(qualityButton==null)return;
        String label="HQ";
        if(player!=null&&player.getVideoFormat()!=null){
            Format f=player.getVideoFormat();
            if(f.height>0){
                if(f.height>=2160)label="4K";
                else if(f.height>=1440)label="QHD";
                else if(f.height>=1080)label="FHD";
                else if(f.height>=720)label="HD";
                else label=f.height+"p";
            }
        }else{
            int savedH=PlugXApp.prefs(this).getInt("quality",0);
            if(savedH>0){
                if(savedH>=2160)label="4K";
                else if(savedH>=1440)label="QHD";
                else if(savedH>=1080)label="FHD";
                else if(savedH>=720)label="HD";
                else label=savedH+"p";
            }
        }
        qualityButton.setText(label);
    }

    /** Reads real available video & audio tracks from ExoPlayer into our lists */
    private void readAvailableTracks(){
        videoTracks.clear();audioTracks.clear();
        if(player==null)return;
        Tracks currentTracks=player.getCurrentTracks();
        int vGroupIdx=0,aGroupIdx=0;
        for(Tracks.Group group:currentTracks.getGroups()){
            int type=group.getType();
            if(type==C.TRACK_TYPE_VIDEO){
                for(int t=0;t<group.length;t++){
                    if(group.isTrackSupported(t)){
                        Format f=group.getTrackFormat(t);
                        String label=buildVideoLabel(f);
                        videoTracks.add(new TrackInfo(vGroupIdx,t,label));
                    }
                }
                vGroupIdx++;
            }else if(type==C.TRACK_TYPE_AUDIO){
                for(int t=0;t<group.length;t++){
                    if(group.isTrackSupported(t)){
                        Format f=group.getTrackFormat(t);
                        String label=buildAudioLabel(f);
                        audioTracks.add(new TrackInfo(aGroupIdx,t,label));
                    }
                }
                aGroupIdx++;
            }
        }
    }

    private String buildVideoLabel(Format f){
        StringBuilder sb=new StringBuilder();
        if(f.height>0){
            if(f.height>=2160)sb.append("4K / 2160p");
            else if(f.height>=1440)sb.append("1440p QHD");
            else if(f.height>=1080)sb.append("1080p Full HD");
            else if(f.height>=720)sb.append("720p HD");
            else if(f.height>=480)sb.append("480p");
            else if(f.height>=360)sb.append("360p");
            else sb.append(f.height).append("p");
            if(f.width>0)sb.append(" (").append(f.width).append("×").append(f.height).append(")");
        }else{
            sb.append("Stream");
        }
        if(f.bitrate>0)sb.append("  ").append(f.bitrate/1000).append(" kbps");
        return sb.toString();
    }

    private String buildAudioLabel(Format f){
        StringBuilder sb=new StringBuilder();
        // Language name
        if(f.language!=null&&!f.language.isEmpty()){
            java.util.Locale locale=new java.util.Locale(f.language);
            String langName=locale.getDisplayLanguage(java.util.Locale.ENGLISH);
            sb.append(langName.isEmpty()||langName.equals(f.language)?f.language.toUpperCase():langName);
        }else{
            sb.append("Audio");
        }
        // Channel config
        if(f.channelCount>0){
            sb.append(f.channelCount==1?" (Mono)":f.channelCount==2?" (Stereo)":f.channelCount==6?" (5.1)":f.channelCount==8?" (7.1)":" ("+f.channelCount+"ch)");
        }
        if(f.bitrate>0)sb.append("  ").append(f.bitrate/1000).append(" kbps");
        // Label/name
        if(f.label!=null&&!f.label.isEmpty())sb.append("  [").append(f.label).append("]");
        return sb.toString();
    }

    private int qualityIndex(int[] heights){int selected=PlugXApp.prefs(this).getInt("quality",0);for(int i=0;i<heights.length;i++)if(heights[i]==selected)return i;return 0;}

    /** FIX: applyQuality uses trackSelector for proper track-aware quality filtering */
    private void applyQuality(){
        if(player==null||trackSelector==null)return;
        int height=PlugXApp.prefs(this).getInt("quality",0);
        if(height>0){
            trackSelector.setParameters(trackSelector.buildUponParameters()
                .setMaxVideoSize(Integer.MAX_VALUE,height)
                .build());
        }else{
            trackSelector.setParameters(trackSelector.buildUponParameters()
                .clearVideoSizeConstraints()
                .setMaxVideoBitrate(Integer.MAX_VALUE)
                .build());
        }
    }

    private void moreControls(){
        String[] actions={"Retry current channel","Language / Audio track","Picture fit / fill / zoom","Channel information","Volume up","Volume down","Brightness up","Brightness down"};
        new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Live TV controls").setItems(actions,(dialog,which)->{
            switch(which){
                case 0:if(currentChannel!=null){generation++;retries=0;startPlayback(currentChannel,generation);}break;
                case 1:chooseAudio();break;
                case 2:aspectMode=(aspectMode+1)%3;view.setResizeMode(aspectMode==1?AspectRatioFrameLayout.RESIZE_MODE_FILL:aspectMode==2?AspectRatioFrameLayout.RESIZE_MODE_ZOOM:AspectRatioFrameLayout.RESIZE_MODE_FIT);Toast.makeText(this,aspectMode==0?"Fit":aspectMode==1?"Fill":"Zoom",Toast.LENGTH_SHORT).show();break;
                case 3:showChannelInfo();break;
                case 4:case 5:{AudioManager audio=(AudioManager)getSystemService(AUDIO_SERVICE);int now=audio.getStreamVolume(AudioManager.STREAM_MUSIC),max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);audio.setStreamVolume(AudioManager.STREAM_MUSIC,Math.max(0,Math.min(max,now+(which==4?1:-1))),AudioManager.FLAG_SHOW_UI);break;}
                case 6:case 7:{WindowManager.LayoutParams p=getWindow().getAttributes();float current=p.screenBrightness<0?.5f:p.screenBrightness;p.screenBrightness=Math.max(.03f,Math.min(1f,current+(which==6?.1f:-.1f)));getWindow().setAttributes(p);Toast.makeText(this,"Brightness "+Math.round(p.screenBrightness*100)+"%",Toast.LENGTH_SHORT).show();break;}
            }
        }).show();
    }

    private void showChannelInfo(){
        if(currentChannel==null)return;
        String name=PlugXApp.str(currentChannel,"name","Channel");
        String manifest=PlugXApp.str(currentChannel,"manifest_type","Auto");
        String resolution="Auto";
        int videoCount=videoTracks.size(),audioCount=audioTracks.size();
        if(player!=null&&player.getVideoFormat()!=null){Format f=player.getVideoFormat();if(f.height>0)resolution=f.width+" × "+f.height;}
        String audioInfo=audioCount>0?audioCount+" track(s) available":"Single audio";
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(name)
            .setMessage("Stream: "+manifest+"\nVideo: "+resolution+(videoCount>1?"\nVideo tracks: "+videoCount:"")+"\n"+audioInfo+"\nQuality setting: "+(PlugXApp.prefs(this).getInt("quality",0)==0?"Auto":PlugXApp.prefs(this).getInt("quality",0)+"p"))
            .setPositiveButton("Close",null).show();
    }

    private JsonObject playerSettings(){return PlugXApp.object(PlugXApp.catalog(),"player_settings");}
    private boolean maintenanceEnabled(){return PlugXApp.bool(playerSettings(),"maintenance_mode");}

    private void showMaintenance(){
        if(maintenanceShown)return;maintenanceShown=true;
        if(player!=null){player.release();player=null;view.setPlayer(null);}
        String html=PlugXApp.str(playerSettings(),"maintenance_text","Server under maintenance. Please check back shortly.");
        new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Server Maintenance").setMessage(android.text.Html.fromHtml(html)).setCancelable(false).setPositiveButton("Home",(dialog,which)->finish()).show();
    }

    private void applyPlayerSettings(){
        if(watermark==null||videoBox==null)return;
        JsonObject s=playerSettings();String url=PlugXApp.str(s,"watermark_url","");
        if(url.isEmpty()){watermark.setVisibility(View.GONE);return;}
        watermark.setVisibility(View.VISIBLE);ImageLoader.into(this,url,watermark);
        int size=Math.max(5,Math.min(100,PlugXApp.integer(s,"watermark_size",15)));
        int opacity=Math.max(10,Math.min(100,PlugXApp.integer(s,"watermark_opacity",80)));
        watermark.setAlpha(opacity/100f);
        videoBox.post(()->{
            if(watermark==null||isFinishing())return;
            String position=PlugXApp.str(playerSettings(),"watermark_position","TOP_RIGHT");
            int gravity=(position.startsWith("BOTTOM")?Gravity.BOTTOM:Gravity.TOP)|(position.endsWith("LEFT")?Gravity.LEFT:Gravity.RIGHT);
            int width=Math.max(Ui.dp(this,24),videoBox.getWidth()*size/100);
            FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(width,Math.max(Ui.dp(this,20),width*2/3),gravity);
            p.setMargins(Ui.dp(this,12),Ui.dp(this,12),Ui.dp(this,12),Ui.dp(this,12));
            watermark.setLayoutParams(p);
        });
    }

    private void change(int delta){if(channels.isEmpty())return;play(index<0?(delta>0?0:channels.size()-1):(index+delta+channels.size())%channels.size());}

    private void sendHeartbeat(JsonObject o){
        if(o==null)return;
        JsonObject b=new JsonObject();b.addProperty("channel_id",PlugXApp.integer(o,"id",0));b.addProperty("playlist_id",PlugXApp.integer(o,"playlist_id",0));b.addProperty("version_code",BuildConfig.VERSION_CODE);b.addProperty("proxy_active",PlugXApp.prefs(this).getBoolean("proxy_enabled",false));
        PlugXApp.request(this,"heartbeat",b,(r,e)->{});
    }

    @Override protected void onStart(){super.onStart();if(stoppedForBackground&&currentChannel!=null){stoppedForBackground=false;generation++;retries=0;startPlayback(currentChannel,generation);}handler.removeCallbacks(presence);handler.postDelayed(presence,60000);}
    @Override protected void onStop(){handler.removeCallbacks(presence);boolean pip=Build.VERSION.SDK_INT>=26&&(enteringPip||isInPictureInPictureMode());if(!pip&&player!=null){player.stop();stoppedForBackground=true;}if(!pip&&currentChannel!=null)sendHeartbeat(currentChannel);super.onStop();}
    @Override public void onPictureInPictureModeChanged(boolean inPip,Configuration config){super.onPictureInPictureModeChanged(inPip,config);enteringPip=inPip;if(listPanel!=null)listPanel.setVisibility(inPip?View.GONE:fullScreen?View.GONE:listVisible?View.VISIBLE:View.GONE);if(headerBar!=null)headerBar.setVisibility(inPip?View.GONE:View.VISIBLE);if(playerActions!=null)playerActions.setVisibility(inPip?View.GONE:View.VISIBLE);if(!inPip)showControls();}

    @Override public void onConfigurationChanged(Configuration config){
        super.onConfigurationChanged(config);
        if(view!=null)view.setPlayer(null);
        buildUi();
        if(player!=null){
            view.setPlayer(player);
            view.setResizeMode(aspectMode==1?AspectRatioFrameLayout.RESIZE_MODE_FILL:aspectMode==2?AspectRatioFrameLayout.RESIZE_MODE_ZOOM:AspectRatioFrameLayout.RESIZE_MODE_FIT);
            int visibility=player.getPlaybackState()==Player.STATE_READY?View.GONE:View.VISIBLE;
            status.setVisibility(visibility);loading.setVisibility(visibility);
        }
        rebuildList();
        if(currentChannel!=null)title.setText(PlugXApp.str(currentChannel,"name","Live TV"));
        updateFavoriteButton();updateQualityButtonLabel();
        if(fullScreen)listPanel.setVisibility(View.GONE);
        if(Build.VERSION.SDK_INT>=26&&isInPictureInPictureMode()){headerBar.setVisibility(View.GONE);listPanel.setVisibility(View.GONE);playerActions.setVisibility(View.GONE);}
    }

    private OkHttpClient http(){
        boolean enabled=PlugXApp.prefs(this).getBoolean("proxy_enabled",false);String host=PlugXApp.prefs(this).getString("proxy_host","");int port=PlugXApp.prefs(this).getInt("proxy_port",0);String kind=PlugXApp.prefs(this).getString("proxy_type","socks5");
        String config=enabled+"|"+host+"|"+port+"|"+kind;
        if(sharedHttp!=null&&config.equals(sharedProxyConfig))return sharedHttp;
        OkHttpClient.Builder b=new OkHttpClient.Builder().connectTimeout(12,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).followRedirects(true);
        LegacyTls.addTrustedRoot(this,b);
        if(enabled&&!host.isEmpty()&&port>0){Proxy.Type type=kind.equals("socks5")?Proxy.Type.SOCKS:Proxy.Type.HTTP;b.proxy(new Proxy(type,new InetSocketAddress(host,port)));}
        sharedHttp=b.build();sharedProxyConfig=config;return sharedHttp;
    }

    private void startPlayback(JsonObject channel,int expected){
        if(maintenanceEnabled()){showMaintenance();return;}
        videoTracks.clear();audioTracks.clear();
        if(player!=null){view.setPlayer(null);player.release();player=null;trackSelector=null;}
        title.setText(PlugXApp.str(channel,"name","Live TV"));
        status.setText("Loading channel…");status.setVisibility(View.VISIBLE);loading.setVisibility(View.VISIBLE);
        // Reset quality button
        if(qualityButton!=null)qualityButton.setText("HQ");
        // Reset lang button
        if(langButton!=null){langButton.setText("LANG");langButton.setAlpha(0.45f);}
        final long startedAt=SystemClock.elapsedRealtime();
        try{
            String url=PlugXApp.str(channel,"stream_url","");
            if(url.isEmpty())throw new IllegalArgumentException("Stream address is missing.");
            Map<String,String> headers=new HashMap<>();
            JsonObject supplied=PlugXApp.object(channel,"headers");
            for(Map.Entry<String,JsonElement> e:supplied.entrySet())if(e.getValue().isJsonPrimitive())headers.put(e.getKey(),e.getValue().getAsString());
            headers.put("Accept","*/*");
            String ua=headers.remove("User-Agent");
            OkHttpDataSource.Factory httpSource=new OkHttpDataSource.Factory(http());
            if(ua!=null&&!ua.trim().isEmpty())httpSource.setUserAgent(ua);
            httpSource.setDefaultRequestProperties(headers);
            DefaultMediaSourceFactory media=new DefaultMediaSourceFactory(new DefaultDataSource.Factory(this,httpSource));
            DefaultDrmSessionManagerProvider drmProvider=new DefaultDrmSessionManagerProvider();
            drmProvider.setDrmHttpDataSourceFactory(httpSource);
            media.setDrmSessionManagerProvider(drmProvider);
            MediaItem.Builder item=new MediaItem.Builder().setUri(url);
            String kind=PlugXApp.str(channel,"manifest_type","").toLowerCase(java.util.Locale.ROOT),base=url.split("\\?",2)[0].toLowerCase(java.util.Locale.ROOT);
            if(kind.contains("hls")||kind.contains("m3u8")||base.endsWith(".m3u8"))item.setMimeType(MimeTypes.APPLICATION_M3U8);
            else if(kind.contains("dash")||kind.contains("mpd")||base.endsWith(".mpd"))item.setMimeType(MimeTypes.APPLICATION_MPD);
            else if(kind.contains("ism")||base.endsWith(".ism/manifest"))item.setMimeType(MimeTypes.APPLICATION_SS);
            String drm=PlugXApp.str(channel,"license_type","").toLowerCase(java.util.Locale.ROOT),keys=PlugXApp.str(channel,"license_key","");
            if(!drm.isEmpty()&&!(drm.contains("clearkey")&&ClearKeySet.isPlaceholder(keys))){
                boolean clear=drm.contains("clearkey");
                UUID uuid=clear?C.CLEARKEY_UUID:drm.contains("playready")?C.PLAYREADY_UUID:C.WIDEVINE_UUID;
                MediaItem.DrmConfiguration.Builder cfg=new MediaItem.DrmConfiguration.Builder(uuid);
                if(clear&&!keys.trim().isEmpty()){
                    String jwk=ClearKeySet.toJwk(keys);
                    MediaDrmCallback callback=new MediaDrmCallback(){
                        public byte[] executeProvisionRequest(UUID u,ExoMediaDrm.ProvisionRequest r){return new byte[0];}
                        public byte[] executeKeyRequest(UUID u,ExoMediaDrm.KeyRequest r){return jwk.getBytes(StandardCharsets.UTF_8);}
                    };
                    DefaultDrmSessionManager session=new DefaultDrmSessionManager.Builder().setMultiSession(false).setUuidAndExoMediaDrmProvider(uuid,LegacyClearKeyDrm.PROVIDER).build(callback);
                    media.setDrmSessionManagerProvider(x->session);
                }else{
                    String license=PlugXApp.str(channel,"license_url","");
                    if(!license.isEmpty())cfg.setLicenseUri(license);
                    cfg.setLicenseRequestHeaders(headers);
                }
                item.setDrmConfiguration(cfg.build());
            }
            // FIX: Use DefaultTrackSelector so we can control tracks properly
            trackSelector=new DefaultTrackSelector(this);
            player=new ExoPlayer.Builder(this,new DefaultRenderersFactory(this).setEnableDecoderFallback(true))
                .setTrackSelector(trackSelector)
                .setMediaSourceFactory(media)
                .build();
            view.setPlayer(player);
            // Apply saved quality preference via trackSelector
            applyQuality();
            player.addListener(new Player.Listener(){
                boolean firstFrame=false;
                public void onPlaybackStateChanged(int state){
                    if(expected!=generation)return;
                    if(state==Player.STATE_READY){
                        status.setVisibility(View.GONE);loading.setVisibility(View.GONE);
                        if(!firstFrame){
                            firstFrame=true;
                            // Read tracks once ready
                            readAvailableTracks();
                            updateQualityButtonLabel();
                            updateLangButtonLabel();
                            android.util.Log.i("PlugXPlayback","ready_ms="+(SystemClock.elapsedRealtime()-startedAt)+" v_tracks="+videoTracks.size()+" a_tracks="+audioTracks.size()+" channel="+PlugXApp.str(channel,"name","Channel"));
                        }
                    }else if(state==Player.STATE_BUFFERING){
                        status.setText("Buffering…");status.setVisibility(View.VISIBLE);loading.setVisibility(View.VISIBLE);
                    }
                }
                public void onPlayerError(PlaybackException error){if(expected!=generation)return;retry(expected);}
                // FIX: Also update tracks when tracks change (e.g. DASH/HLS manifest fully loaded)
                public void onTracksChanged(Tracks tracks){
                    if(expected!=generation)return;
                    readAvailableTracks();
                    updateQualityButtonLabel();
                    updateLangButtonLabel();
                }
                // FIX: Update quality label when video size changes (actual playing resolution)
                public void onVideoSizeChanged(VideoSize videoSize){
                    if(expected!=generation)return;
                    updateQualityButtonLabel();
                }
            });
            player.setMediaItem(item.build());player.prepare();player.play();
        }catch(Exception e){
            loading.setVisibility(View.GONE);
            status.setText("Unable to play this channel. Try another stream or refresh.");
        }
    }

    private void retry(int expected){
        if(expected!=generation)return;
        if(retries++>=3){loading.setVisibility(View.GONE);status.setText("Unable to play this channel. Please check the stream or connection.");status.setVisibility(View.VISIBLE);return;}
        status.setText("Refreshing channel…");loading.setVisibility(View.VISIBLE);
        PlugXApp.request(this,"bootstrap",null,(value,error)->{
            if(expected!=generation)return;
            if(error==null){PlugXApp.catalog(this,value);rebuildList();}
            JsonObject channel=currentChannel;
            if(channel!=null)handler.postDelayed(()->{if(expected==generation)startPlayback(channel,expected);},error==null?1500:2000);
            else{loading.setVisibility(View.GONE);status.setText("Unable to play this channel.");status.setVisibility(View.VISIBLE);}
        });
    }

    private void favorite(JsonObject o){
        String id=PlugXApp.str(o,"id","");
        Set<String> values=new HashSet<>(PlugXApp.prefs(this).getStringSet("favorites",Collections.emptySet()));
        if(!values.add(id))values.remove(id);
        PlugXApp.prefs(this).edit().putStringSet("favorites",values).apply();
        Toast.makeText(this,values.contains(id)?"Added to Favorites":"Removed from Favorites",Toast.LENGTH_SHORT).show();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e){
        if(e.getAction()!=KeyEvent.ACTION_DOWN)return super.dispatchKeyEvent(e);
        int code=e.getKeyCode();
        if(code==KeyEvent.KEYCODE_DPAD_UP||code==KeyEvent.KEYCODE_DPAD_DOWN||code==KeyEvent.KEYCODE_DPAD_LEFT||code==KeyEvent.KEYCODE_DPAD_RIGHT)scheduleListHide();
        if(code==KeyEvent.KEYCODE_VOLUME_UP||code==KeyEvent.KEYCODE_VOLUME_DOWN){
            AudioManager audio=(AudioManager)getSystemService(AUDIO_SERVICE);
            int max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int value=Math.max(0,Math.min(max,audio.getStreamVolume(AudioManager.STREAM_MUSIC)+(code==KeyEvent.KEYCODE_VOLUME_UP?1:-1)));
            audio.setStreamVolume(AudioManager.STREAM_MUSIC,value,0);
            percent("♪",Math.round(value*100f/Math.max(1,max)));
            handler.postDelayed(()->{if(gesturePercent!=null)gesturePercent.setVisibility(View.GONE);},1000);
            return true;
        }
        if(code==KeyEvent.KEYCODE_CHANNEL_UP||code==KeyEvent.KEYCODE_MEDIA_NEXT){change(1);return true;}
        if(code==KeyEvent.KEYCODE_CHANNEL_DOWN||code==KeyEvent.KEYCODE_MEDIA_PREVIOUS){change(-1);return true;}
        boolean controlsFocused=(playerActions!=null&&playerActions.hasFocus())||(headerBar!=null&&headerBar.hasFocus());
        boolean onVideo=view!=null&&(view.hasFocus()||((fullScreen||!listVisible)&&!controlsFocused));
        if(onVideo){
            if(code==KeyEvent.KEYCODE_DPAD_UP||code==KeyEvent.KEYCODE_DPAD_DOWN){change(code==KeyEvent.KEYCODE_DPAD_DOWN?1:-1);showControls();return true;}
            if(code==KeyEvent.KEYCODE_DPAD_RIGHT||code==KeyEvent.KEYCODE_DPAD_LEFT){change(code==KeyEvent.KEYCODE_DPAD_RIGHT?1:-1);showControls();return true;}
            if(code==KeyEvent.KEYCODE_DPAD_CENTER||code==KeyEvent.KEYCODE_ENTER){showControls();if(!videoControls.isEmpty())videoControls.get(0).requestFocus();return true;}
        }
        return super.dispatchKeyEvent(e);
    }

    @Override public void onBackPressed(){if(fullScreen){setFullscreen(false);return;}super.onBackPressed();}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);if(player!=null)player.release();super.onDestroy();}

    private class RecyclerViewList extends androidx.recyclerview.widget.RecyclerView{
        private final ChannelAdapter adapter;
        RecyclerViewList(Context c){super(c);setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(c));adapter=new ChannelAdapter();setAdapter(adapter);}
        void update(ArrayList<JsonObject> items){adapter.notifyDataSetChanged();}
        void select(int position){adapter.notifyDataSetChanged();scrollToPosition(position);}
    }

    private class ChannelAdapter extends androidx.recyclerview.widget.RecyclerView.Adapter<ChannelAdapter.Holder>{
        class Holder extends androidx.recyclerview.widget.RecyclerView.ViewHolder{LinearLayout row;ImageView logo;TextView number,name,caption;Holder(LinearLayout r,ImageView l,TextView n,TextView t,TextView c){super(r);row=r;logo=l;number=n;name=t;caption=c;}}
        public Holder onCreateViewHolder(android.view.ViewGroup parent,int type){
            LinearLayout row=Ui.row(PlayerActivity.this);row.setFocusable(true);row.setClickable(true);
            row.setPadding(Ui.dp(PlayerActivity.this,8),Ui.dp(PlayerActivity.this,4),Ui.dp(PlayerActivity.this,8),Ui.dp(PlayerActivity.this,4));
            androidx.recyclerview.widget.RecyclerView.LayoutParams lp=new androidx.recyclerview.widget.RecyclerView.LayoutParams(-1,Ui.dp(PlayerActivity.this,82));
            lp.setMargins(Ui.dp(PlayerActivity.this,6),Ui.dp(PlayerActivity.this,3),Ui.dp(PlayerActivity.this,6),Ui.dp(PlayerActivity.this,3));
            row.setLayoutParams(lp);
            TextView number=Ui.text(PlayerActivity.this,"",12,true);number.setGravity(Gravity.CENTER);row.addView(number,Ui.margins(PlayerActivity.this,32,32,2));
            FrameLayout art=new FrameLayout(PlayerActivity.this);art.setBackground(Ui.shape(Ui.CARD2,0xff754655,Ui.dp(PlayerActivity.this,10)));
            TextView mark=Ui.text(PlayerActivity.this,"▶",17,true);mark.setTextColor(Ui.RED);mark.setGravity(Gravity.CENTER);art.addView(mark,new FrameLayout.LayoutParams(-1,-1));
            ImageView logo=new ImageView(PlayerActivity.this);logo.setScaleType(ImageView.ScaleType.FIT_CENTER);art.addView(logo,new FrameLayout.LayoutParams(-1,-1));
            row.addView(art,Ui.margins(PlayerActivity.this,48,48,2));
            LinearLayout labels=Ui.column(PlayerActivity.this);
            TextView name=Ui.text(PlayerActivity.this,"",15,true);name.setMaxLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);labels.addView(name);
            TextView caption=Ui.muted(PlayerActivity.this,"LIVE STREAM",10);caption.setLetterSpacing(.08f);labels.addView(caption);
            row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
            return new Holder(row,logo,number,name,caption);
        }
        public void onBindViewHolder(Holder h,int pos){
            JsonObject o=channels.get(pos);boolean selected=pos==index;
            h.number.setText(String.valueOf(PlugXApp.integer(o,"channel_number",pos+1)));
            h.number.setTextColor(selected?Ui.GOLD:Ui.MUTED);
            h.name.setText(PlugXApp.str(o,"name","Channel"));
            h.caption.setText(selected?"●  NOW PLAYING":"LIVE STREAM");
            h.caption.setTextColor(selected?Ui.RED:Ui.MUTED);
            ImageLoader.into(PlayerActivity.this,PlugXApp.str(o,"logo",""),h.logo);
            h.row.setBackground(Ui.shape(selected?0xff5a1b32:Ui.CARD,selected?Ui.RED:(PlugXApp.prefs(PlayerActivity.this).getBoolean("light",false)?0xffc99fab:0xff663241),Ui.dp(PlayerActivity.this,14)));
            h.name.setTextColor(selected?android.graphics.Color.WHITE:Ui.TEXT);
            h.row.setNextFocusRightId(view.getId());
            h.row.setOnFocusChangeListener((v,yes)->{
                android.graphics.drawable.GradientDrawable surface=Ui.shape(yes?0xff79233c:selected?0xff5a1b32:Ui.CARD,yes?Ui.GOLD:selected?Ui.RED:0xff663241,Ui.dp(PlayerActivity.this,14));
                surface.setStroke(Ui.dp(PlayerActivity.this,yes?3:1),yes?Ui.GOLD:selected?Ui.RED:0xff663241);
                h.row.setBackground(surface);h.name.setTextColor(yes||selected?android.graphics.Color.WHITE:Ui.TEXT);
            });
            h.row.setOnClickListener(v->play(pos));
            h.row.setOnLongClickListener(v->{favorite(o);notifyItemChanged(pos);return true;});
        }
        public int getItemCount(){return channels.size();}
    }
}
