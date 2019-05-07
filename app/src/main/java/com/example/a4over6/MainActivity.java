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
import java.io.File;
import java.io.FileInputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
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

    // Used to load the 'native-lib' library on application startup.
    static {
        System.loadLibrary("native-lib");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        _extDir = Environment.getExternalStorageDirectory();

        ip_flag = false;
        _backend_run = false;
        _vpnService = null;
        _dns = new String[3];

        _mtu = 4096;
        _session = "android VPN";

        File f = new File(_extDir.toString() + "/" + ip_pipe);
        f.delete();
        f = new File(_extDir.toString() + "/" + flow_pipe);
        f.delete();

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
            Log.d("front end", "establish vpnservice");
            _protect_socket = Integer.parseInt(infos[0]);
            _ip_vir = infos[1];
            _route = infos[2];
            _dns[0] = infos[3];
            _dns[1] = infos[4];
            _dns[2] = infos[5];
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
            _status.setText(msg);
        }

    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (resultCode == RESULT_OK) {
            Intent intent = new Intent(this, MyVpnService.class);
            intent.putExtra("pipe", _extDir.getAbsoluteFile() + "/" + ip_pipe);
            intent.putExtra("socket", _protect_socket);
            intent.putExtra("ip", _ip_vir);
            intent.putExtra("MTU", _mtu);
            intent.putExtra("session", _session);
            intent.putExtra("route", _route);
            intent.putExtra("dns0", _dns[0]);
            intent.putExtra("dns1", _dns[1]);
            intent.putExtra("dns2", _dns[2]);
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
        if (!_backend_run) {
            _backend = new BackendThread(this, _ipv6.getText().toString(), _port.getText().toString(), _extDir.toString() + "/" + ip_pipe, _extDir.toString() + "/" + flow_pipe);
            _backend.start();
            _backend_run = true;
        }
    }
}
