package com.androidvirtualfree.probe;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

public class ProbeActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        TextView t = new TextView(this);
        t.setText("AVF PROBE OK");
        t.setTextSize(48);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(Color.BLACK);
        t.setBackgroundColor(Color.rgb(212, 175, 55));
        setContentView(t);
    }
}
