package com.example.a4over6;

import android.content.Intent;
import android.net.VpnService;
import android.os.Environment;
import android.os.Handler;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends AppCompatActivity {

    private EditText _ipv6, _port;
    private Button _link, _unlink;
    private TextView _status; //when starting, checking the network status

    private TimerTask _task;
    private Timer _timer;
    private static Handler _handle = new Handler();
    File _extDir;
    private final String pipe_write = "cmd_pipe";
    private final String pipe_read = "cmd_pipe2";
    private final int BUF_SIZE = 1024;
    byte[] _readBuf = new byte[BUF_SIZE];

    private boolean _backend_run;
    private Thread _backend;
    private MyVpnService _vpnService;

    // Used to load the 'native-lib' library on application startup.
    static {
        System.loadLibrary("native-lib");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        _extDir = Environment.getExternalStorageDirectory();

        _backend_run = false;
        _vpnService = null;

        initBackend();
        initView();
        //TODO
        //checkNetwork();
        startTimer();
    }

    protected void initBackend() {
        _backend = new BackendThread(this);
    }

    protected void initView() {
        _ipv6 = (EditText)findViewById(R.id.editText_ip);
        _port = (EditText)findViewById(R.id.editText_port);
        _link = (Button)findViewById(R.id.button_link);
        _unlink = (Button)findViewById(R.id.button_unlink);
        _status = (TextView)findViewById(R.id.textView_status);

        initLinkBtn();
        initUnLinkBtn();
    }

    protected void initUnLinkBtn() {
        _unlink.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                write_to_pipeline("1");
            }
        });
    }

    //Link button send ipv6 and port to backend
    protected void initLinkBtn() {
        //_link.setClickable(false); //first check the network status;
        _link.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!_backend_run) {
                    write_to_pipeline("0" + _ipv6.getText() + " " + _port.getText());
                    _backend.start();
                    _backend_run = true;
                }
            }
        });
    }

    protected void startTimer() {
        _task = new TimerTask() {
            @Override
            public void run() {
                final String ans = readStatusFromJNI();
                if (ans != null) {
                    processMsgFromBack(ans);
                }
            }
        };

        _timer = new Timer();
        _timer.schedule(_task, 0, 1000);

    }

    protected void processMsgFromBack(final String msg) {
        String[] infos = msg.split(" ");
        if (infos[0] ==  "1") {  //backend unlink
            //TODO
        } else if (infos[0] == "0") {
            if (_vpnService == null) {
                Runnable run = new Runnable() {
                    @Override
                    public void run() {
                        _status.setText(msg);
                    }
                };
                _handle.post(run);
                openVpnService();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (resultCode == RESULT_OK) {
            Intent intent = new Intent(this, MyVpnService.class);

            startService(intent);
        }
    }

    protected void openVpnService() {
        Intent intent = VpnService.prepare(this);
        if (intent != null) {
            startActivityForResult(intent,0);
        } else {
            onActivityResult(0, RESULT_OK, null);
        }


    }

    protected String readStatusFromJNI() {
        int readLen = 0;
        try {
            File file = new File(_extDir, pipe_read);
            FileInputStream fileInputStream = new FileInputStream(file);
            BufferedInputStream in = new BufferedInputStream(fileInputStream);
            readLen = in.read(_readBuf, 0, BUF_SIZE);//读取管道
            in.close();
        } catch (Exception e) {
            Log.d("frontend", "read pipe error");
        }
        if (readLen > 0) return new String(_readBuf);
        else return null;
    }

    //check whether in ipv6 status, if it is than clickable,
    //https://stackoverflow.com/questions/40770555/getting-ipv4-and-ipv6-programatically-of-android-device-with-non-deprecated-meth
    protected void checkNetwork() {
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            while (en.hasMoreElements()) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements(); ) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    //System.out.println("ip1--:" + inetAddress);
                    //System.out.println("ip2--:" + inetAddress.getHostAddress());
                    Log.d("time", "t");
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet6Address) {
                        String ipaddress = inetAddress.getHostAddress().toString();
                        _status.setText("find ipv6 address: " + ipaddress);
                        _link.setClickable(true);
                        return;
                    }
                }
            }
        } catch (Exception ex) {
            Log.e("canon't find IP Address", ex.toString());
        }
        _status.setText("can't find ipv6 address, please check again");
        return;
    }

    protected void write_to_pipeline(String text) {
        //Log.d("write c", "get write");
        try {
            File file = new File(_extDir, pipe_write);
            FileOutputStream fileOutputStream = new FileOutputStream(file);
            BufferedOutputStream out = new BufferedOutputStream(fileOutputStream);
            out.write(text.getBytes(), 0, text.length());
            out.flush();
            out.close();
        } catch (Exception e) {
            Log.d("Frontend write error:", e.getMessage());
        }
    }

    /**
     * A native method that is implemented by the 'native-lib' native library,
     * which is packaged with this application.
     */
    public native String stringFromJNI();
    public native String getInfoFromJNI(String s);
    public native void runBackendThread();
}
