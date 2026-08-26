package com.zitan.cdumpdex;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigation.NavigationBarView;
import com.zitan.cdumpdex.ui.HomeFragment;
import com.zitan.cdumpdex.ui.SettingsFragment;
import com.zitan.cdumpdex.ui.AboutFragment;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_STORAGE_PERMISSION = 1001;
    private static final int REQUEST_MANAGE_STORAGE = 1002;
    private static final int REQUEST_OVERLAY_PERMISSION = 1003;

    // 当前显示的 SettingsFragment 引用，用于悬浮窗权限回调
    private SettingsFragment settingsFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        setupBottomNavigation();

        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.nav_host_fragment, new HomeFragment())
                    .commit();
        }

        // 检查并申请存储权限
        checkAndRequestStoragePermission();
    }

    /**
     * 检查并申请存储权限
     */
    private void checkAndRequestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ 需要所有文件访问权限
            if (!Environment.isExternalStorageManager()) {
                showStoragePermissionDialog();
            }
        } else {
            // Android 10 及以下
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{
                                Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                        }, REQUEST_STORAGE_PERMISSION);
            }
        }
    }

    /**
     * 显示存储权限申请对话框
     */
    private void showStoragePermissionDialog() {
        new AlertDialog.Builder(this)
                .setTitle("需要存储权限")
                .setMessage("本应用需要\"所有文件访问\"权限才能正常使用提取应用和写入配置功能")
                .setPositiveButton("去授权", (dialog, which) -> {
                    try {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        startActivityForResult(intent, REQUEST_MANAGE_STORAGE);
                    } catch (Exception e) {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                        startActivityForResult(intent, REQUEST_MANAGE_STORAGE);
                    }
                })
                .setNegativeButton("稍后再说", null)
                .setCancelable(false)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_STORAGE_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "存储权限已获取", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "存储权限被拒绝，部分功能可能无法使用", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_MANAGE_STORAGE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    Toast.makeText(this, "存储权限已获取", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "存储权限被拒绝，部分功能可能无法使用", Toast.LENGTH_LONG).show();
                }
            }
        } else if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            // 悬浮窗权限返回
            boolean granted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && Settings.canDrawOverlays(this);
            if (settingsFragment != null) {
                settingsFragment.onOverlayPermissionResult(granted);
            }
        }
    }

    private void setupBottomNavigation() {
        BottomNavigationView bottomNav = findViewById(R.id.bottom_navigation);
        if (bottomNav != null) {
            bottomNav.setOnItemSelectedListener(new NavigationBarView.OnItemSelectedListener() {
                @Override
                public boolean onNavigationItemSelected(@NonNull MenuItem item) {
                    Fragment selectedFragment = null;
                    int itemId = item.getItemId();

                    if (itemId == R.id.navigation_home) {
                        selectedFragment = new HomeFragment();
                    } else if (itemId == R.id.navigation_settings) {
                        settingsFragment = new SettingsFragment();
                        selectedFragment = settingsFragment;
                    } else if (itemId == R.id.navigation_about) {
                        selectedFragment = new AboutFragment();
                    }

                    if (selectedFragment != null) {
                        getSupportFragmentManager().beginTransaction()
                                .replace(R.id.nav_host_fragment, selectedFragment)
                                .commit();
                        return true;
                    }
                    return false;
                }
            });
        }
    }
}
