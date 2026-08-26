package com.zitan.cdumpdex;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.snackbar.Snackbar;
import com.zitan.cdumpdex.model.AppInfo;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class DirectoryPickerActivity extends AppCompatActivity {

    private File currentDir;
    private DirectoryAdapter adapter;
    private AppInfo appInfo;
    private TextView tvCurrentPath;
    private RecyclerView recyclerView;
    private TextView btnSelect;
    private SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_directory_picker);

        try {
            appInfo = getIntent().getParcelableExtra("app_info");
        } catch (Exception e) {
            e.printStackTrace();
        }

        tvCurrentPath = findViewById(R.id.tv_current_path);
        recyclerView = findViewById(R.id.recycler_view);
        btnSelect = findViewById(R.id.btn_select);

        setupToolbar();
        setupRecyclerView();
        setupSelectButton();

        currentDir = Environment.getExternalStorageDirectory();
        navigateTo(currentDir);
    }

    private void setupToolbar() {
        View toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            toolbar.setOnClickListener(v -> finish());
        }
    }

    private void setupRecyclerView() {
        adapter = new DirectoryAdapter(this::onDirectoryClick);
        if (recyclerView != null) {
            recyclerView.setLayoutManager(new LinearLayoutManager(this));
            recyclerView.setAdapter(adapter);
        }
    }

    private void setupSelectButton() {
        if (btnSelect != null) {
            btnSelect.setOnClickListener(v -> extractAppToCurrentDir());
        }
    }

    private void navigateTo(File dir) {
        currentDir = dir;
        if (tvCurrentPath != null) {
            tvCurrentPath.setText(dir.getAbsolutePath());
        }

        List<File> directories = new ArrayList<>();

        if (dir.getParentFile() != null) {
            directories.add(dir.getParentFile());
        }

        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory() && !file.isHidden()) {
                    directories.add(file);
                }
            }
            Collections.sort(directories, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        }

        if (adapter != null) {
            adapter.setData(directories, dir.getParentFile() != null);
        }
    }

    private void onDirectoryClick(File dir, boolean isParent) {
        if (isParent) {
            if (currentDir.getParentFile() != null) {
                navigateTo(currentDir.getParentFile());
            }
        } else {
            navigateTo(dir);
        }
    }

    private void extractAppToCurrentDir() {
        if (appInfo == null) {
            Snackbar.make(findViewById(android.R.id.content), "应用信息无效", Snackbar.LENGTH_SHORT).show();
            return;
        }

        if (btnSelect != null) {
            btnSelect.setEnabled(false);
            btnSelect.setText("正在提取...");
        }

        new Thread(() -> {
            try {
                String timestamp = dateFormat.format(new Date());
                String outputName = appInfo.getAppName() + "_" + timestamp + ".apk";
                File outputFile = new File(currentDir, outputName);

                File sourceFile = new File(appInfo.getSourceDir());
                copyFile(sourceFile, outputFile);

                runOnUiThread(() -> {
                    if (btnSelect != null) {
                        btnSelect.setEnabled(true);
                        btnSelect.setText(R.string.dir_picker_select);
                    }

                    ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        ClipData clip = ClipData.newPlainText("extract_path", outputFile.getAbsolutePath());
                        clipboard.setPrimaryClip(clip);
                    }

                    Snackbar.make(findViewById(android.R.id.content),
                            getString(R.string.extract_success) + ": " + outputFile.getAbsolutePath(),
                            Snackbar.LENGTH_LONG).show();
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (btnSelect != null) {
                        btnSelect.setEnabled(true);
                        btnSelect.setText(R.string.dir_picker_select);
                    }
                    Snackbar.make(findViewById(android.R.id.content),
                            getString(R.string.extract_failed) + ": " + e.getMessage(),
                            Snackbar.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void copyFile(File source, File dest) throws Exception {
        try (FileInputStream fis = new FileInputStream(source);
             FileOutputStream fos = new FileOutputStream(dest)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = fis.read(buffer)) != -1) {
                fos.write(buffer, 0, len);
            }
        }
    }

    private static class DirectoryAdapter extends RecyclerView.Adapter<DirectoryAdapter.ViewHolder> {
        private List<File> directories = new ArrayList<>();
        private boolean hasParent = false;
        private final OnDirectoryClickListener listener;

        interface OnDirectoryClickListener {
            void onDirectoryClick(File dir, boolean isParent);
        }

        public DirectoryAdapter(OnDirectoryClickListener listener) {
            this.listener = listener;
        }

        public void setData(List<File> directories, boolean hasParent) {
            this.directories = directories;
            this.hasParent = hasParent;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_directory, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            File dir = directories.get(position);
            boolean isParent = hasParent && position == 0;

            if (holder.name != null) {
                holder.name.setText(isParent ? ".." : dir.getName());
            }

            holder.itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onDirectoryClick(dir, isParent);
                }
            });
        }

        @Override
        public int getItemCount() {
            return directories.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView name;

            public ViewHolder(@NonNull View itemView) {
                super(itemView);
                name = itemView.findViewById(R.id.tv_name);
            }
        }
    }
}
