package com.example.a4over6;

import android.view.View;

public class BackendThread extends Thread {
    MainActivity _parent;
    String _ipv6, _port, _ip_pipe, _flow_pipe;
    BackendThread(MainActivity p, String ipv6, String port, String ip_pipe, String flow_pipe) {
        _parent = p;
        _ipv6 = ipv6;
        _port = port;
        _ip_pipe = ip_pipe;
        _flow_pipe = flow_pipe;
    }

    public void run() {
        _parent.runBackendThread(_ipv6, _port, _ip_pipe, _flow_pipe);
    }
}
