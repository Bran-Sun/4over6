package com.example.a4over6;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;

public class MyVpnService extends VpnService {
    ParcelFileDescriptor inter;

    private BroadcastReceiver stopBr = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d("vpn service", "destroy service");
            if ("stop_kill".equals(intent.getAction())) {
                try {
                    if (inter != null) {
                        inter.close();
                        inter = null;
                    }
                } catch (Exception e) {
                    Log.d("vpnService", e.toString());
                }
                stopSelf();
            }
        }
    };

    @Override
    public void onCreate() {
        LocalBroadcastManager lbm =
                LocalBroadcastManager.getInstance(this);
        lbm.registerReceiver(stopBr, new IntentFilter("stop_kill"));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d("frontend", "on command");
        String filename = intent.getStringExtra("pipe");
        int ipv6_socket = intent.getIntExtra("socket", 0);

        Builder builder = new Builder();
        builder.addAddress(intent.getStringExtra("ip_vir"), 24);
        builder.setMtu(intent.getIntExtra("MTU", 1500));
        builder.setSession(intent.getStringExtra("session"));
        builder.addRoute(intent.getStringExtra("route"), 0);
        builder.addDnsServer(intent.getStringExtra("dns0"));
        builder.addDnsServer(intent.getStringExtra("dns1"));
        builder.addDnsServer(intent.getStringExtra("dns2"));
        //TODO

        inter = builder.establish();

        protect(ipv6_socket);

        Integer fd = inter.getFd();
        Log.d("vpnService", fd.toString());

        String fd_buf = "1 " + fd.toString() + " ";

        try {
            File file = new File(filename);
            FileOutputStream fileOutputStream = new FileOutputStream(file);
            BufferedOutputStream out = new BufferedOutputStream(fileOutputStream);
            out.write(fd_buf.getBytes(), 0, fd_buf.length());
            out.flush();
            out.close();
        } catch (Exception e) {
            Log.d("Frontend write error:", e.getMessage());
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.d("vpn service", "destroy service");
        try {
            if (inter != null) {
                inter.close();
                inter = null;
            }
        } catch (Exception e) {
            Log.d("vpnService", e.toString());
        }
        stopSelf();
        super.onDestroy();
    }
}
