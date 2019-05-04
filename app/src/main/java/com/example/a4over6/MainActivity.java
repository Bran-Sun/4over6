package com.example.a4over6;

import android.content.Context;
import android.net.ConnectivityManager;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

public class MainActivity extends AppCompatActivity {

    private EditText _ipv6, _port;
    private Button _link, _unlink;
    private TextView _status; //when starting, checking the network status

    // Used to load the 'native-lib' library on application startup.
    static {
        System.loadLibrary("native-lib");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initView();

        checkNetwork();

    }

    protected void checkNetwork() { //check whether in ipv6 status, if it is than clickable
        ConnectivityManager cm = (ConnectivityManager)this.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    protected void initView() {
        _ipv6 = (EditText)findViewById(R.id.editText2);
        _port = (EditText)findViewById(R.id.editText3);
        _link = (Button)findViewById(R.id.button);
        _unlink = (Button)findViewById(R.id.button2);
        _status = (TextView)findViewById(R.id.textView_status);

        _link.setClickable(false); //first check the network status;

    }

    /**
     * A native method that is implemented by the 'native-lib' native library,
     * which is packaged with this application.
     */
    public native String stringFromJNI();
}
