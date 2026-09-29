package com.fable2.recomp;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;

/**
 * Entry screen. Starts the game when the disc content is in place, otherwise
 * explains where to copy it.
 *
 * The game files can live in either place:
 *   Internal storage/Fable2/   - easy to copy from a PC; needs "All files access"
 *   Android/data/com.fable2.recomp/files/ - no permission; build_apk.cmd's
 *                                USB copy uses it
 */
public class LauncherActivity extends Activity {
    private static final String FOLDER_NAME = "Fable2";
    private static final int REQUEST_LEGACY_STORAGE = 1;

    /** Internal storage/Fable2 - where a user copies the game by hand. */
    static File publicDir() {
        return new File(Environment.getExternalStorageDirectory(), FOLDER_NAME);
    }

    static boolean hasFileAccess(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean hasGame(File dir) {
        return dir != null
                && new File(dir, "default.xex").isFile()
                && new File(dir, "data").isDirectory();
    }

    /** Folder holding the game (and where saves, config and logs go). */
    static File contentDir(Activity activity) {
        File pub = publicDir();
        if (hasFileAccess(activity) && hasGame(pub)) {
            return pub;
        }
        File dir = activity.getExternalFilesDir(null);
        if (dir != null) {
            dir.mkdirs();
        }
        return dir;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (hasGame(contentDir(this))) {
            launchGame();
        } else {
            showSetup();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Coming back from the "All files access" settings screen.
        if (!isFinishing() && hasGame(contentDir(this))) {
            launchGame();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (hasGame(contentDir(this))) {
            launchGame();
        } else {
            showSetup();
        }
    }

    private void launchGame() {
        startActivity(new Intent(this, Fable2Activity.class));
        finish();
    }

    private void requestFileAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            try {
                startActivity(intent);
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            requestPermissions(new String[] {Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_LEGACY_STORAGE);
        }
    }

    private void showSetup() {
        boolean access = hasFileAccess(this);
        boolean folderHasGame = hasGame(publicDir());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        int pad = dp(24);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(18, 18, 20));

        TextView title = new TextView(this);
        title.setText(folderHasGame || access ? "Almost there" : "Game files needed");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        root.addView(title);

        StringBuilder text = new StringBuilder();
        if (!access) {
            text.append("1. Tap \"Allow file access\" below and switch it on, so the game ")
                .append("can read its files from your storage.\n\n");
        }
        text.append(access ? "Copy" : "2. Copy")
            .append(" the game folder from your PC to your phone's internal storage ")
            .append("and name it \"").append(FOLDER_NAME).append("\":\n\n")
            .append("    Internal storage/").append(FOLDER_NAME).append("/default.xex\n")
            .append("    Internal storage/").append(FOLDER_NAME).append("/data/\n")
            .append("    Internal storage/").append(FOLDER_NAME).append("/$SystemUpdate/\n\n")
            .append("On the PC these are in C:\\f2build\\game. Copy them over USB ")
            .append("(\"File transfer\" mode) with File Explorer.\n\n");
        if (access && !folderHasGame) {
            text.append("Not found yet: ").append(publicDir().getAbsolutePath());
        }

        TextView body = new TextView(this);
        body.setText(text.toString());
        body.setTextColor(Color.LTGRAY);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        body.setPadding(0, dp(12), 0, dp(20));
        root.addView(body);

        if (!access) {
            Button allow = new Button(this);
            allow.setText("Allow file access");
            allow.setOnClickListener(v -> requestFileAccess());
            root.addView(allow);
        }

        Button retry = new Button(this);
        retry.setText("Check again");
        retry.setOnClickListener(v -> {
            if (hasGame(contentDir(this))) {
                launchGame();
            } else {
                showSetup();
            }
        });
        root.addView(retry);

        setContentView(root);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
