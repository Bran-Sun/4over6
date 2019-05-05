package com.example.a4over6;

public class BackendThread extends Thread {
    MainActivity _parent;
    BackendThread(MainActivity p) {
        _parent = p;
    }

    public void run() {
        _parent.runBackendThread();
    }
}
