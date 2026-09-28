package com.fable2.recomp;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;

/**
 * Entry screen. Starts the game when the disc content is in place, otherwise
 * explains where to copy it (build_apk.ps1 pushes it over USB automatically).
 */
public class LauncherActivity extends Activity {

    static File contentDir(Activity activity) {
        File dir = activity.getExternalFilesDir(null);
        if (dir != null) {
            dir.mkdirs();
        }
        return dir;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (hasContent()) {
            launchGame();
        } else {
            showSetup();
        }
    }

    private boolean hasContent() {
        File dir = contentDir(this);
        return dir != null
                && new File(dir, "default.xex").isFile()
                && new File(dir, "data").isDirectory();
    }

    private void launchGame() {
        startActivity(new Intent(this, Fable2Activity.class));
        finish();
    }

    private void showSetup() {
        File dir = contentDir(this);
        String path = dir != null ? dir.getAbsolutePath() : "(storage unavailable)";

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        int pad = dp(24);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(18, 18, 20));

        TextView title = new TextView(this);
        title.setText("Game files needed");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        root.addView(title);

        TextView body = new TextView(this);
        body.setText("Copy these from your Fable 2 disc into this folder:\n\n"
                + "    default.xex\n    data/\n    $SystemUpdate/\n\n"
                + path + "\n\n"
                + "build_apk.cmd copies them automatically when the phone is "
                + "connected with USB debugging on. You can also copy them with a "
                + "PC file manager (Android/data/com.fable2.recomp/files).");
        body.setTextColor(Color.LTGRAY);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        body.setPadding(0, dp(12), 0, dp(20));
        root.addView(body);

        Button retry = new Button(this);
        retry.setText("Check again");
        retry.setOnClickListener(v -> {
            if (hasContent()) {
                launchGame();
            }
        });
        root.addView(retry);

        setContentView(root);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
