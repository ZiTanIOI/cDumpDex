package com.zitan.cdumpdex.ui;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.snackbar.Snackbar;
import com.zitan.cdumpdex.DirectoryPickerActivity;
import com.zitan.cdumpdex.util.ProcessListHelper;
import com.zitan.cdumpdex.util.RootMemoryScanner;
import com.zitan.cdumpdex.R;
import com.zitan.cdumpdex.adapter.AppListAdapter;
import com.zitan.cdumpdex.databinding.FragmentHomeBinding;
import com.zitan.cdumpdex.model.AppInfo;
import com.zitan.cdumpdex.util.ConfigManager;
import com.zitan.cdumpdex.util.RootHelper;
import com.zitan.cdumpdex.util.ShizukuHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class HomeFragment extends Fragment implements AppListAdapter.OnItemClickListener {

    private FragmentHomeBinding binding;
    private AppListAdapter adapter;
    private final List<AppInfo> appList = new ArrayList<>();
    private final List<AppInfo> filteredList = new ArrayList<>();
    private PackageManager packageManager;
    private View loadingLayout;
    private SharedPreferencesHelper prefsHelper;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentHomeBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        try {
            packageManager = requireContext().getPackageManager();
            prefsHelper = new SharedPreferencesHelper(requireContext());
            loadingLayout = binding.getRoot().findViewById(R.id.loading_layout);

            setupRecyclerView();
            setupSwipeRefresh();
            setupSearch();
            loadApps();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void setupRecyclerView() {
        adapter = new AppListAdapter(filteredList, this);
        binding.recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.recyclerView.setAdapter(adapter);
    }

    private void setupSearch() {
        binding.searchEdit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterApps(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
    }

    private void filterApps(String query) {
        filteredList.clear();

        if (query.isEmpty()) {
            filteredList.addAll(appList);
        } else {
            String lowerQuery = query.toLowerCase();
            for (AppInfo app : appList) {
                if (app.getAppName().toLowerCase().contains(lowerQuery) ||
                    app.getPackageName().toLowerCase().contains(lowerQuery)) {
                    filteredList.add(app);
                }
            }
        }

        adapter.notifyDataSetChanged();

        // 更新空视图
        binding.emptyView.setVisibility(filteredList.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void setupSwipeRefresh() {
        binding.swipeRefresh.setColorSchemeResources(R.color.md_theme_primary);
        binding.swipeRefresh.setOnRefreshListener(this::loadApps);
    }

    private void showLoading() {
        if (loadingLayout != null) {
            loadingLayout.setVisibility(View.VISIBLE);
        }
        if (binding.recyclerView != null) {
            binding.recyclerView.setVisibility(View.GONE);
        }
    }

    private void hideLoading() {
        if (loadingLayout != null) {
            loadingLayout.setVisibility(View.GONE);
        }
        if (binding.recyclerView != null) {
            binding.recyclerView.setVisibility(View.VISIBLE);
        }
    }

    private void loadApps() {
        showLoading();
        binding.emptyView.setVisibility(View.GONE);

        new Thread(() -> {
            List<AppInfo> apps = new ArrayList<>();
            try {
                List<PackageInfo> packages = packageManager.getInstalledPackages(0);

                for (PackageInfo packageInfo : packages) {
                    try {
                        ApplicationInfo appInfo = packageInfo.applicationInfo;
                        if (appInfo == null) continue;
                        if (appInfo.packageName.equals(requireContext().getPackageName())) continue;

                        AppInfo info = new AppInfo();
                        info.setPackageName(appInfo.packageName);
                        info.setAppName(packageManager.getApplicationLabel(appInfo).toString());
                        info.setAppIcon(packageManager.getApplicationIcon(appInfo));
                        info.setSourceDir(appInfo.sourceDir);
                        info.setDataDir(appInfo.dataDir);
                        info.setVersionName(packageInfo.versionName);
                        info.setVersionCode((int) packageInfo.getLongVersionCode());
                        info.setTargetSdk(appInfo.targetSdkVersion);
                        info.setUid(appInfo.uid);
                        info.setSystemApp((appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
                        apps.add(info);
                    } catch (Exception ignored) {}
                }

                Collections.sort(apps, (a, b) -> {
                    if (a.isSystemApp() != b.isSystemApp()) {
                        return a.isSystemApp() ? 1 : -1;
                    }
                    return a.getAppName().compareToIgnoreCase(b.getAppName());
                });
            } catch (Exception e) {
                e.printStackTrace();
            }

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    appList.clear();
                    appList.addAll(apps);
                    filteredList.clear();
                    filteredList.addAll(apps);
                    adapter.notifyDataSetChanged();
                    hideLoading();
                    binding.swipeRefresh.setRefreshing(false);
                    binding.emptyView.setVisibility(apps.isEmpty() ? View.VISIBLE : View.GONE);
                });
            }
        }).start();
    }

    @Override
    public void onItemClick(AppInfo appInfo) {
        showAppOptionsDialog(appInfo);
    }

    private void showAppOptionsDialog(AppInfo appInfo) {
        try {
            String[] options = {
                    getString(R.string.action_open),
                    getString(R.string.action_app_info),
                    getString(R.string.action_extract),
                    "写入配置",
                    "Root内存脱壳"
            };

            new AlertDialog.Builder(requireContext())
                    .setTitle(appInfo.getAppName())
                    .setItems(options, (dialog, which) -> {
                        switch (which) {
                            case 0:
                                openApp(appInfo.getPackageName());
                                break;
                            case 1:
                                showAppInfoDialog(appInfo);
                                break;
                            case 2:
                                extractApp(appInfo);
                                break;
                            case 3:
                                writeConfigToApp(appInfo);
                                break;
                            case 4:
                                performRootMemoryDump(appInfo);
                                break;
                        }
                    })
                    .show();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void openApp(String packageName) {
        try {
            Intent intent = packageManager.getLaunchIntentForPackage(packageName);
            if (intent != null) {
                startActivity(intent);
            } else {
                Snackbar.make(binding.getRoot(), "无法打开此应用", Snackbar.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Snackbar.make(binding.getRoot(), "打开失败: " + e.getMessage(), Snackbar.LENGTH_SHORT).show();
        }
    }

    private void showAppInfoDialog(AppInfo appInfo) {
        try {
            AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
            builder.setTitle(appInfo.getAppName());

            // 使用自定义Adapter
            List<InfoItem> infoItems = new ArrayList<>();
            infoItems.add(new InfoItem("包名", appInfo.getPackageName()));
            infoItems.add(new InfoItem("版本", appInfo.getVersionName() + " (" + appInfo.getVersionCode() + ")"));
            infoItems.add(new InfoItem("数据目录", appInfo.getDataDir()));
            infoItems.add(new InfoItem("安装路径", appInfo.getSourceDir()));
            infoItems.add(new InfoItem("UID", String.valueOf(appInfo.getUid())));
            infoItems.add(new InfoItem("目标SDK", String.valueOf(appInfo.getTargetSdk())));
            infoItems.add(new InfoItem("类型", appInfo.isSystemApp() ? "系统应用" : "用户应用"));

            ListView listView = new ListView(requireContext());
            InfoItemAdapter adapter = new InfoItemAdapter(requireContext(), infoItems);
            listView.setAdapter(adapter);

            // 长按复制
            listView.setOnItemLongClickListener((parent, view, position, id) -> {
                InfoItem item = infoItems.get(position);
                ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    ClipData clip = ClipData.newPlainText(item.label, item.value);
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(requireContext(), "已复制: " + item.value, Toast.LENGTH_SHORT).show();
                }
                return true;
            });

            builder.setView(listView);
            builder.setPositiveButton(R.string.close, null);
            builder.setNeutralButton("系统设置", (dialog, which) -> {
                Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:" + appInfo.getPackageName()));
                startActivity(intent);
            });
            builder.show();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void extractApp(AppInfo appInfo) {
        try {
            Intent intent = new Intent(requireContext(), DirectoryPickerActivity.class);
            intent.putExtra("app_info", appInfo);
            startActivity(intent);
        } catch (Exception e) {
            Snackbar.make(binding.getRoot(), "提取失败: " + e.getMessage(), Snackbar.LENGTH_SHORT).show();
        }
    }

    private void writeConfigToApp(AppInfo appInfo) {
        String permissionMethod = prefsHelper.getPermissionMethod();
        String unshellMode = prefsHelper.getUnshellMode();
        ConfigManager configManager = new ConfigManager(requireContext());
        // 主动调用模式：从统一设置读取，仅 Xposed 模式会写入到目标应用配置中
        boolean activeLoadClass = configManager.isXposedActiveCallEnabled();
        String activeCallEngine = configManager.getXposedActiveCallEngine();
        boolean deepUnpack = prefsHelper.getDeepUnpack();
        boolean methodTriggerEnabled = configManager.getMethodTriggerEnabled();

        if (permissionMethod.equals("none")) {
            new AlertDialog.Builder(requireContext())
                    .setTitle("提示")
                    .setMessage("请先在设置中选择授权方式")
                    .setPositiveButton("去设置", (dialog, which) -> {
                        // 跳转到设置页面
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }

        // 校验主动调用模式：未勾选任何模式时给出提示
        if (configManager.getActiveCallModes().isEmpty()) {
            new AlertDialog.Builder(requireContext())
                    .setTitle("提示")
                    .setMessage("当前未勾选任何主动调用模式，将仅使用脱壳模式而不主动加载类。\n是否继续？")
                    .setPositiveButton("继续", (d, w) -> doWriteConfig(appInfo, permissionMethod, unshellMode, activeLoadClass, activeCallEngine, deepUnpack, methodTriggerEnabled))
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }

        doWriteConfig(appInfo, permissionMethod, unshellMode, activeLoadClass, activeCallEngine, deepUnpack, methodTriggerEnabled);
    }

    private void doWriteConfig(AppInfo appInfo, String permissionMethod, String unshellMode,
                               boolean activeLoadClass, String activeCallEngine, boolean deepUnpack,
                               boolean methodTriggerEnabled) {
        // 显示写入进度
        AlertDialog progressDialog = new AlertDialog.Builder(requireContext())
                .setTitle("写入配置")
                .setMessage("正在写入配置到 " + appInfo.getAppName() + "...")
                .setCancelable(false)
                .create();
        progressDialog.show();

        new Thread(() -> {
            boolean success = false;
            String message = "";

            try {
                // 构建配置JSON，包含主动调用模式
                String configJson = "{\"unshell_mode\":\"" + unshellMode
                        + "\",\"active_load_class\":" + activeLoadClass
                        + ",\"active_call_engine\":\"" + activeCallEngine
                        + "\",\"deep_unpack\":" + deepUnpack
                        + ",\"method_trigger_enabled\":" + methodTriggerEnabled + "}";

                if (permissionMethod.equals("shizuku")) {
                    ShizukuHelper shizukuHelper = new ShizukuHelper(requireContext());
                    if (shizukuHelper.hasPermission()) {
                        success = shizukuHelper.writeJsonConfig(appInfo.getPackageName(), configJson);
                        message = success ? "配置写入成功" : "配置写入失败，请检查Shizuku权限";
                    } else {
                        message = "Shizuku权限未授予";
                    }
                } else if (permissionMethod.equals("root")) {
                    RootHelper rootHelper = new RootHelper();
                    if (rootHelper.checkRootAccess()) {
                        success = rootHelper.writeJsonConfig(appInfo.getPackageName(), configJson);
                        rootHelper.closeShell();
                        message = success ? "配置写入成功" : "配置写入失败，请检查Root权限";
                    } else {
                        rootHelper.closeShell();
                        message = "Root权限未授予";
                    }
                }
            } catch (Exception e) {
                message = "写入失败: " + e.getMessage();
            }

            final boolean finalSuccess = success;
            final String finalMessage = message;

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    progressDialog.dismiss();
                    new AlertDialog.Builder(requireContext())
                            .setTitle(finalSuccess ? "成功" : "失败")
                            .setMessage(finalMessage + "\n\n目标路径: /storage/emulated/0/Android/data/" + appInfo.getPackageName() + "/files/cdumpdex_config.json")
                            .setPositiveButton("确定", null)
                            .show();
                });
            }
        }).start();
    }

    /**
     * 直接对选中的应用执行 Root 内存脱壳
     */
    private void performRootMemoryDump(AppInfo appInfo) {
        Context context = requireContext();

        // 检查 Root 权限
        new Thread(() -> {
            if (!RootMemoryScanner.checkRootAvailable()) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        new AlertDialog.Builder(context)
                                .setTitle("Root权限不可用")
                                .setMessage("Root内存脱壳需要Root权限，请检查设备是否已Root。")
                                .setPositiveButton("确定", null)
                                .show();
                    });
                }
                return;
            }

            // 检查目标应用是否在运行
            int pid = ProcessListHelper.findPidByName(appInfo.getPackageName());
            if (pid <= 0) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        new AlertDialog.Builder(context)
                                .setTitle("应用未运行")
                                .setMessage("\"" + appInfo.getAppName() + "\" 当前未在运行。\n\n请先打开该应用，然后再执行Root内存脱壳。")
                                .setPositiveButton("打开应用", (d, w) -> openApp(appInfo.getPackageName()))
                                .setNegativeButton("取消", null)
                                .show();
                    });
                }
                return;
            }

            // 确认执行
            final int finalPid = pid;
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    new AlertDialog.Builder(context)
                            .setTitle("确认脱壳")
                            .setMessage("目标应用: " + appInfo.getAppName() + "\n"
                                    + "包名: " + appInfo.getPackageName() + "\n"
                                    + "PID: " + finalPid + "\n\n"
                                    + "将dump该进程内存中的所有DEX文件。")
                            .setPositiveButton("开始脱壳", (d, w) -> executeRootDump(appInfo, finalPid))
                            .setNegativeButton("取消", null)
                            .show();
                });
            }
        }).start();
    }

    /**
     * 执行 Root 内存脱壳
     */
    private void executeRootDump(AppInfo appInfo, int pid) {
        Context context = requireContext();

        // 显示进度提示
        AlertDialog progressDialog = new AlertDialog.Builder(context)
                .setTitle("正在脱壳...")
                .setMessage("正在dump进程内存中的DEX文件\nPID: " + pid + "\n\n请稍候...")
                .setCancelable(false)
                .create();
        progressDialog.show();

        new Thread(() -> {
            String message;
            boolean success = false;

            try {
                // 输出到 /sdcard/cDumpDex/dump/ 方便文件管理器访问
                java.io.File outputDir = new java.io.File(
                        "/storage/emulated/0/cDumpDex/dump",
                        "root_memory_" + System.currentTimeMillis());
                if (!outputDir.exists() && !outputDir.mkdirs()) {
                    message = "创建输出目录失败";
                } else {
                    RootMemoryScanner scanner = new RootMemoryScanner(context);
                    int count = scanner.dumpDexFromPid(pid, outputDir);

                    if (count >= 0) {
                        message = "脱壳完成！\n\n找到 " + count + " 个DEX文件\n\n保存到:\n" + outputDir.getAbsolutePath();
                        success = true;
                    } else {
                        message = "脱壳失败 (错误码: " + count + ")\n\n请确认:\n- 目标应用正在运行\n- Root权限正常\n- /proc/" + pid + "/mem 可读";
                    }
                }
            } catch (Exception e) {
                message = "脱壳异常: " + e.getMessage();
            }

            final boolean finalSuccess = success;
            final String finalMessage = message;

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    progressDialog.dismiss();
                    new AlertDialog.Builder(context)
                            .setTitle(finalSuccess ? "脱壳完成" : "脱壳失败")
                            .setMessage(finalMessage)
                            .setPositiveButton("确定", null)
                            .show();
                });
            }
        }).start();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    // 信息项类
    private static class InfoItem {
        String label;
        String value;

        InfoItem(String label, String value) {
            this.label = label;
            this.value = value;
        }
    }

    // 信息项适配器 - 继承BaseAdapter，完全自定义视图
    private static class InfoItemAdapter extends BaseAdapter {
        private final Context context;
        private final List<InfoItem> items;

        public InfoItemAdapter(Context context, List<InfoItem> items) {
            this.context = context;
            this.items = items;
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                // 使用两个 TextView 的线性布局
                LinearLayout layout = new LinearLayout(context);
                layout.setOrientation(LinearLayout.VERTICAL);
                layout.setPadding(16, 12, 16, 12);

                TextView text1 = new TextView(context);
                text1.setTextSize(12);
                text1.setTextColor(context.getColor(R.color.md_theme_onSurfaceVariant));

                TextView text2 = new TextView(context);
                text2.setTextSize(14);
                text2.setTextColor(context.getColor(R.color.md_theme_onSurface));

                layout.addView(text1);
                layout.addView(text2);

                convertView = layout;
                holder = new ViewHolder();
                holder.text1 = text1;
                holder.text2 = text2;
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            InfoItem item = items.get(position);
            holder.text1.setText(item.label);
            holder.text2.setText(item.value);

            return convertView;
        }

        static class ViewHolder {
            TextView text1;
            TextView text2;
        }
    }

    // SharedPreferences辅助类
    private static class SharedPreferencesHelper {
        private final android.content.SharedPreferences prefs;

        public SharedPreferencesHelper(Context context) {
            prefs = context.getSharedPreferences("cDumpDex_settings", Context.MODE_PRIVATE);
        }

        public String getPermissionMethod() {
            return prefs.getString("permission_method", "none");
        }

        public String getUnshellMode() {
            return prefs.getString("unshell_mode", "fixed");
        }

        public boolean getActiveLoadClass() {
            return prefs.getBoolean("active_load_class", true);
        }

        public boolean getDeepUnpack() {
            return prefs.getBoolean("deep_unpack", false);
        }
    }
}
