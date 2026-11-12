package app.plugxaura.iptv;

import android.content.*;
import android.content.pm.PackageManager;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent intent){if(!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())||!PlugXApp.prefs(c).getBoolean("boot_tv",false))return;
        boolean tv=c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK)||c.getResources().getConfiguration().uiMode==android.content.res.Configuration.UI_MODE_TYPE_TELEVISION;
        if(tv){Intent start=new Intent(c,LoginActivity.class);start.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);c.startActivity(start);}
    }
}
