package com.example.a4over6;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Environment;
import android.os.Handler;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {

    private EditText _ipv6, _port;
    private Button _link, _unlink;
    private TextView _status; //when starting, checking the network status

    private TimerTask _task;
    private Timer _timer;
    private static Handler _handle = new Handler();
    File _extDir;
    private final String ip_pipe = "ip_pipe";
    private final String flow_pipe = "flow_pipe2";
    private final int BUF_SIZE = 1024;
    byte[] _readBuf = new byte[BUF_SIZE];

    private boolean _backend_run;
    private Thread _backend;
    private MyVpnService _vpnService;
    private Integer _protect_socket;
    private int _mtu;
    private String _ip_vir, _route, _session;
    private String[] _dns;
    boolean ip_flag;

    String[] permissions = new String[]{
            Manifest.permission.INTERNET,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.BIND_VPN_SERVICE
    };

    private boolean checkPermissions() {
        int result;
        List<String> listPermissionsNeeded = new ArrayList<>();
        for (String p : permissions) {
            result = ContextCompat.checkSelfPermission(this, p);
            if (result != PackageManager.PERMISSION_GRANTED) {
                listPermissionsNeeded.add(p);
            }
        }
        if (!listPermissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this, listPermissionsNeeded.toArray(new String[listPermissionsNeeded.size()]), 100);
            return false;
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        if (requestCode == 100) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // do something
            }
            return;
        }
    }

    // Used to load the 'native-lib' library on application startup.
    static {
        System.loadLibrary("native-lib");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        checkPermissions();
        _extDir = Environment.getExternalStorageDirectory();

        ip_flag = false;
        _backend_run = false;
        _vpnService = null;
        _dns = new String[3];

        _mtu = 1500;
        _session = "android VPN";

        File f = new File(_extDir.toString() + "/" + ip_pipe);
        if (f.exists()) {
            f.delete();
        }
        File f2 = new File(_extDir.toString() + "/" + flow_pipe);
        if (f2.exists()) {
            f2.delete();
        }

        initView();
        checkNetwork();

        startTimer();
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
        //TODO
        _unlink.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //write_to_pipeline("1");
            }
        });
    }

    //Link button send ipv6 and port to backend
    protected void initLinkBtn() {
        _link.setOnClickListener(this);
        _link.setEnabled(false); //first check the network status;
    }

    protected void startTimer() {
        _task = new TimerTask() {
            @Override
            public void run() {
                final String ans = readPipeInfo();
                if (ans != null) {
                    processMsgFromBack(ans);
                }
            }
        };

        _timer = new Timer();
        _timer.schedule(_task, 0, 1000);

    }

    protected void processMsgFromBack(final String msg) {
        if (!ip_flag) {
            String[] infos = msg.split(" ");
            if (infos.length < 6) {
                Log.d("frontend", "ip error");
                return;
            }
            Log.d("frontend", "establish vpnservice");
            _protect_socket = Integer.parseInt(infos[1]);
            _ip_vir = infos[2];
            _route = infos[3];
            _dns[0] = infos[4];
            _dns[1] = infos[5];
            _dns[2] = infos[6];
            ip_flag = true;

            if (_vpnService == null) {
                Runnable run = new Runnable() {
                    @Override
                    public void run() {
                        _status.setText("establish socket!");
                    }
                };
                _handle.post(run);
                openVpnService();
            }
        } else {
            Runnable run = new Runnable() {
                @Override
                public void run() {
                    String[] infos = msg.split(" ");
                    String newMsg = "recv: " + infos[0] + "B/s, " + infos[1] + "个/s send: " + infos[2] + "B/s, " + infos[3] + "个/s";
                    _status.setText(newMsg);
                }
            };
            _handle.post(run);
        }

    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (resultCode == RESULT_OK) {
            Intent intent = new Intent( this, MyVpnService.class);
            intent.putExtra("pipe", _extDir.getAbsoluteFile() + "/" + ip_pipe);
            intent.putExtra("socket", _protect_socket);
            intent.putExtra("ip_vir", _ip_vir);
            intent.putExtra("MTU", _mtu);
            intent.putExtra("session", _session);
            intent.putExtra("route", _route);
            intent.putExtra("dns0", _dns[0]);
            intent.putExtra("dns1", _dns[1]);
            intent.putExtra("dns2", _dns[2]);
            Log.d("frontend", "start vpn service");
            startService(intent);
            Log.d("frontend", "start over");
        }
    }

    protected void openVpnService() {
        Intent intent = VpnService.prepare(MainActivity.this);
        if (intent != null) {
            startActivityForResult(intent,0);
        } else {
            onActivityResult(0, RESULT_OK, null);
        }
    }

    protected String readPipeInfo() {
        String read_pipe;
        if (!ip_flag) {
            read_pipe = ip_pipe;
        } else {
            read_pipe = flow_pipe;
        }

        int readLen = 0;
        try {
            File file = new File(_extDir, read_pipe);
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
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet6Address) {
                        String ipaddress = inetAddress.getHostAddress();
                        _status.setText("find ipv6 address: " + ipaddress);
                        _link.setEnabled(true);
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

    /**
     * A native method that is implemented by the 'native-lib' native library,
     * which is packaged with this application.
     */
    public native String stringFromJNI();
    public native String getInfoFromJNI(String s);
    public native void runBackendThread(String ipv6, String port, String ip_pipe, String flow_pipe);

    //only for click
    @Override
    public void onClick(View v) {
        Log.d("frontend", "click link");
        if (!_backend_run) {
            _backend = new BackendThread(this, _ipv6.getText().toString(), _port.getText().toString(), _extDir.toString() + "/" + ip_pipe, _extDir.toString() + "/" + flow_pipe);
            _backend.start();
            _backend_run = true;
        }
    }
}
