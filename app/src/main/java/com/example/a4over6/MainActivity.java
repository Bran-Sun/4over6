package com.example.a4over6;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.LocalBroadcastManager;
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
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {

    private EditText _ipv6, _port;
    private Button _link, _unlink;
    private TextView _status; //when starting, checking the network status

    private TimerTask _task_ip, _task_flow;
    private Timer _timer_ip, _timer_flow;
    private static Handler _handle = new Handler(Looper.getMainLooper());
    File _extDir;
    private final String ip_pipe = "ip_pipe";
    private final String flow_pipe = "flow_pipe2";
    private final int BUF_SIZE = 1024;
    byte[] _readBuf = new byte[BUF_SIZE];

    private boolean _backend_run;
    private BackendThread _backend;
    private Intent _vpnIntent;
    private Integer _protect_socket;
    private int _mtu;
    private String _ip_vir, _route, _session;
    private String[] _dns;
    boolean ip_flag;

    //Ask for permissions
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

    //initialize
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        checkPermissions();

        init_arguments();

        initView();
        checkNetwork();

        //startTimer();
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

    protected void init_arguments() {
        _extDir = Environment.getExternalStorageDirectory();
        ip_flag = false;
        _backend_run = false;
        _dns = new String[3];
        _mtu = 1500;
        _session = "android VPN";
        _clean_pipes();

        _timer_ip = new Timer();
        _timer_flow = new Timer();
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
                if (_backend_run) {
                    Log.d("frontend", "unlink");
                    _send_unlink();
                }
            }
        });
    }

    //only for click
    @Override
    public void onClick(View v) {
        Log.d("frontend", "click link");
        if (!_backend_run) {
            _task_ip = new TimerTask() {
                @Override
                public void run() {
                    if (!_backend_run) cancel();
                    final String ans = readPipeInfo(ip_pipe);
                    if (ans != null) {
                        processMsgFromIp(ans);
                    }
                }
            };

            _task_flow = new TimerTask() {
                @Override
                public void run() {
                    if (!_backend_run) cancel();
                    final String ans = readPipeInfo(flow_pipe);
                    if (ans != null) {
                        processMsgFromFlow(ans);
                    }
                }
            };
            _backend = new BackendThread(this,_extDir.toString() + "/" + ip_pipe, _extDir.toString() + "/" + flow_pipe);
            _backend.set_ip_port(_ipv6.getText().toString(), _port.getText().toString());
            ip_flag = false;
            _vpnIntent = null;
            _backend_run = true;

            startTimer();
            _backend.start();
        }
    }

    //Link button send ipv6 and port to backend
    protected void initLinkBtn() {
        _link.setOnClickListener(this);
        _link.setEnabled(false); //first check the network status;
    }

    protected void _send_unlink() {
        backend_unlink();
    }

//    protected void _write_to_pipe(String pipe, String msg) {
//        try {
//            File file = new File(pipe);
//            FileOutputStream fileOutputStream = new FileOutputStream(file);
//            BufferedOutputStream out = new BufferedOutputStream(fileOutputStream);
//            out.write(msg.getBytes(), 0, msg.length());
//            out.flush();
//            out.close();
//        } catch (Exception e) {
//            Log.d("Frontend write error:", e.getMessage());
//        }
//    }

    protected void startTimer() {
        _timer_ip.schedule(_task_ip, 0, 1000);
        _timer_flow.schedule(_task_flow, 0, 1000);
    }

    protected void processMsgFromIp(final String msg) {
        String[] infos = msg.split(" ");
        int flag = Integer.parseInt(infos[0]);
        if (flag == 0) {
            _protect_socket = Integer.parseInt(infos[1]);
            _ip_vir = infos[2];
            _route = infos[3];
            _dns[0] = infos[4];
            _dns[1] = infos[5];
            _dns[2] = infos[6];
            ip_flag = true;

            if (_vpnIntent == null) {
                Runnable run = new Runnable() {
                    @Override
                    public void run() {
                        _status.setText("establish socket!");
                    }
                };
                _handle.post(run);
                Log.d("frontend", "establish frontend");
                openVpnService();
            }
        } else if (flag == 2) { //close
            if (_backend_run) {
                Log.d("frontend", "close backend");
                final String errorMsg = infos[1];
                Runnable run = new Runnable() {
                    @Override
                    public void run() {
                        Log.d("frontend", "backend " + errorMsg);
                        _status.setText(errorMsg);
                    }
                };
                _handle.post(run);
                try {
                    _backend.interrupt();
                }catch (Exception e) {
                    e.printStackTrace();
                }
                _backend_run = false;
                if (_vpnIntent != null) {
                    Log.d("frontend", "stop vpn service");
                    Intent intent = new Intent("stop_kill");
                    LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
                }
                _clean_pipes();
                Log.d("frontend", "close backend over");
            }
        }
    }

    protected void processMsgFromFlow(final String msg) {
        Runnable run = new Runnable() {
            @Override
            public void run() {
                String[] infos = msg.split(" ");
                if (infos.length < 5) return;
                String newMsg = "recv speed: " + getFlow(infos[0]) + "/s, recv data: " + getFlow(infos[1]) + "\nsend: " + getFlow(infos[2]) + "/s, send data: " + getFlow(infos[3]) + "\n link time: " +  getTime(infos[4]);
                _status.setText(newMsg);
            }
        };
        _handle.post(run);
    }

    protected String getFlow(String s) {
        if (s.isEmpty()) return "0B";
        Double d = Double.parseDouble(s);
        int cnt = 0;
        while (d > 1024) {
            d = d / 1024;
            cnt++;
        }
        String format = String.format("%.2f", d);
        if (cnt == 0) return format + "B";
        else if (cnt == 1) return format + "KB";
        else if (cnt == 2) return format + "MB";
        else return format + "GB";
    }

    protected String getTime(String s) {
        Integer wholeTime = Integer.parseInt(s);
        Integer hour, min, sec;
        sec = wholeTime % 60;
        hour = wholeTime / 3600;
        min = (wholeTime / 60) % 60;
        return hour.toString() + ":" + min.toString() + ":" + sec.toString();
    }

    protected String readPipeInfo(String read_pipe) {
        int readLen = 0;
        try {
            File file = new File(_extDir, read_pipe);
            file.setWritable(true);
            file.setReadable(true);
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

    protected void openVpnService() {
        Intent intent = VpnService.prepare(MainActivity.this);
        if (intent != null) {
            startActivityForResult(intent,0);
        } else {
            onActivityResult(0, RESULT_OK, null);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (resultCode == RESULT_OK) {
            _vpnIntent = new Intent( this, MyVpnService.class);
            _vpnIntent.putExtra("pipe", _extDir.getAbsoluteFile() + "/" + ip_pipe);
            _vpnIntent.putExtra("socket", _protect_socket);
            _vpnIntent.putExtra("ip_vir", _ip_vir);
            _vpnIntent.putExtra("MTU", _mtu);
            _vpnIntent.putExtra("session", _session);
            _vpnIntent.putExtra("route", _route);
            _vpnIntent.putExtra("dns0", _dns[0]);
            _vpnIntent.putExtra("dns1", _dns[1]);
            _vpnIntent.putExtra("dns2", _dns[2]);
            Log.d("frontend", "start vpn service");
            startService(_vpnIntent);
            Log.d("frontend", "start over");
        }
    }


    protected void _clean_pipes() {
        File f = new File(_extDir.toString() + "/" + ip_pipe);
        if (f.exists()) {
            Log.d("frontend", "delete ip pipe");
            f.delete();
        }
        File f2 = new File(_extDir.toString() + "/" + flow_pipe);
        if (f2.exists()) {
            Log.d("frontend", "delete flow pipe");
            f2.delete();
        }
    }

    /**
     * A native method that is implemented by the 'native-lib' native library,
     * which is packaged with this application.
     */
    public native String stringFromJNI();
    public native String getInfoFromJNI(String s);
    public native void runBackendThread(String ipv6, String port, String ip_pipe, String flow_pipe);
    public native void backend_unlink();
}
