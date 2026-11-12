package app.plugxaura.iptv;

import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public final class SplashActivity extends AppCompatActivity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable openApp = () -> {
        if (isFinishing() || isDestroyed()) return;
        Class<?> destination = PlugXApp.token(this).isEmpty() ? LoginActivity.class : HomeActivity.class;
        startActivity(new Intent(this, destination));
        finish();
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setNavigationBarColor(Color.BLACK);
        int width = (int)(getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density);
        int height = (int)(getResources().getDisplayMetrics().heightPixels / getResources().getDisplayMetrics().density);
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        boolean shortScreen = height < 400;

        FrameLayout scene = new FrameLayout(this);
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xff090407, 0xff320914, 0xff120c14, 0xff070508});
        scene.setBackground(background);
        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER_HORIZONTAL);
        center.setPadding(Ui.dp(this,20), Ui.dp(this,8), Ui.dp(this,20), Ui.dp(this,8));
        FrameLayout.LayoutParams centerParams = new FrameLayout.LayoutParams(
                Math.min(getResources().getDisplayMetrics().widthPixels, Ui.dp(this,landscape?560:430)),
                -2, Gravity.CENTER);
        scene.addView(center, centerParams);

        int logoSize = shortScreen ? 86 : landscape ? 150 : width < 360 ? 120 : 146;
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.plugx_logo);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(Ui.dp(this,logoSize),Ui.dp(this,logoSize));
        logoParams.gravity = Gravity.CENTER_HORIZONTAL;
        center.addView(logo,logoParams);
        TextView name = new TextView(this);
        name.setText("PLUG X IPTV");
        name.setTextColor(Color.WHITE);
        name.setTextSize(shortScreen ? 22 : 28);
        name.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        name.setLetterSpacing(.08f);
        name.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(-1,-2);
        nameParams.topMargin = Ui.dp(this,shortScreen?8:18);
        center.addView(name,nameParams);
        TextView line = new TextView(this);
        line.setText("LIVE TV  •  EVERYWHERE");
        line.setTextColor(0xffffd390);
        line.setTextSize(shortScreen ? 10 : 12);
        line.setLetterSpacing(.18f);
        line.setGravity(Gravity.CENTER);
        center.addView(line,new LinearLayout.LayoutParams(-1,-2));
        ProgressBar progress = new ProgressBar(this);
        progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(0xffffc973));
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(Ui.dp(this,28),Ui.dp(this,28));
        progressParams.gravity = Gravity.CENTER_HORIZONTAL;
        progressParams.topMargin = Ui.dp(this,shortScreen?14:30);
        center.addView(progress,progressParams);
        TextView loading = new TextView(this);
        loading.setText("Preparing your live channels…");
        loading.setTextColor(0xffd9c2c9);
        loading.setTextSize(12);
        loading.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams loadingParams = new LinearLayout.LayoutParams(-1,-2);
        loadingParams.topMargin = Ui.dp(this,8);
        center.addView(loading,loadingParams);

        setContentView(scene);
        handler.postDelayed(openApp,650);
    }
    @Override protected void onDestroy(){handler.removeCallbacks(openApp);super.onDestroy();}
}
