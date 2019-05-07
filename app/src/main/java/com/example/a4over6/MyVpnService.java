package com.example.a4over6;

import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;

public class MyVpnService extends VpnService {

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String filename = intent.getStringExtra("pipe");
        int ipv6_socket = intent.getIntExtra("socket", 0);

        Builder builder = new Builder();
        builder.addAddress(intent.getStringExtra("ip_vir"), 24);
        builder.setMtu(intent.getIntExtra("MTU", 1500));
        builder.setSession(intent.getStringExtra("session"));
        builder.addRoute(intent.getStringExtra("route"), 24);
        builder.addDnsServer(intent.getStringExtra("dns0"));
        builder.addDnsServer(intent.getStringExtra("dns1"));
        builder.addDnsServer(intent.getStringExtra("dns2"));
        //TODO

        ParcelFileDescriptor inter = builder.establish();

        protect(ipv6_socket);

        Integer fd = inter.getFd();
        String fd_buf = fd.toString();

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
}
