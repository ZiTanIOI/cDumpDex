package com.zitan.cdumpdex.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.snackbar.Snackbar;
import com.zitan.cdumpdex.R;
import com.zitan.cdumpdex.service.FloatingWindowService;
import com.zitan.cdumpdex.util.ConfigManager;
import com.zitan.cdumpdex.util.RootHelper;
import com.zitan.cdumpdex.util.ShizukuHelper;

import java.util.HashSet;
import java.util.Set;

public class SettingsFragment extends Fragment {

    private View rootView;
    private SharedPreferences sharedPreferences;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        rootView = inflater.inflate(R.layout.fragment_settings, container, false);
        return rootView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        sharedPreferences = requireContext().getSharedPreferences("cDumpDex_settings", Context.MODE_PRIVATE);

        setupPermissionCard();
        setupUnshellModeCard();
        setupActiveLoadCard();
        setupDeepUnpackCard();
        setupMethodTriggerCard();
        updateCurrentSettings();
    }

    private void setupPermissionCard() {
        View cardPermission = rootView.findViewById(R.id.card_permission);
        if (cardPermission != null) {
            cardPermission.setOnClickListener(v -> showPermissionMethodDialog());
        }
    }

    private void showPermissionMethodDialog() {
        String[] methods = {
                getString(R.string.permission_shizuku),
                getString(R.string.permission_root)
        };

        String currentMethod = sharedPreferences.getString("permission_method", "none");
        int selectedIndex = currentMethod.equals("shizuku") ? 0 :
                (currentMethod.equals("root") ? 1 : -1);

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.settings_permission)
                .setSingleChoiceItems(methods, selectedIndex, null)
                .setPositiveButton(R.string.confirm, (dialog, which) -> {
                    int pos = ((AlertDialog) dialog).getListView().getCheckedItemPosition();
                    handlePermissionMethodSelected(pos);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void handlePermissionMethodSelected(int position) {
        switch (position) {
            case 0:
                handleShizukuSelected();
                break;
            case 1:
                handleRootSelected();
                break;
        }
    }

    private void handleShizukuSelected() {
        ShizukuHelper shizukuHelper = new ShizukuHelper(requireContext());

        if (!shizukuHelper.isShizukuAvailable()) {
            Snackbar.make(rootView, R.string.shizuku_not_running, Snackbar.LENGTH_LONG).show();
            return;
        }

        if (!shizukuHelper.hasPermission()) {
            shizukuHelper.requestPermission(new ShizukuHelper.PermissionCallback() {
                @Override
                public void onGranted() {
                    savePermissionMethod("shizuku");
                    Snackbar.make(rootView, "Shizuku权限已获取", Snackbar.LENGTH_SHORT).show();
                }

                @Override
                public void onDenied() {
                    Snackbar.make(rootView, R.string.shizuku_permission_denied, Snackbar.LENGTH_SHORT).show();
                }
            });
        } else {
            savePermissionMethod("shizuku");
            Snackbar.make(rootView, "Shizuku权限已获取", Snackbar.LENGTH_SHORT).show();
        }
    }

    private void handleRootSelected() {
        new Thread(() -> {
            RootHelper rootHelper = new RootHelper();
            boolean hasRoot = rootHelper.checkRootAccess();
            rootHelper.closeShell();

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (hasRoot) {
                        savePermissionMethod("root");
                        Snackbar.make(rootView, "Root权限已获取", Snackbar.LENGTH_SHORT).show();
                    } else {
                        Snackbar.make(rootView, R.string.root_not_available, Snackbar.LENGTH_SHORT).show();
                    }
                });
            }
        }).start();
    }

    private void savePermissionMethod(String method) {
        sharedPreferences.edit().putString("permission_method", method).apply();
        updateCurrentSettings();
    }

    private void setupUnshellModeCard() {
        View cardUnshellMode = rootView.findViewById(R.id.card_unshell_mode);
        if (cardUnshellMode != null) {
            cardUnshellMode.setOnClickListener(v -> showUnshellModeDialog());
        }
    }

    private void showUnshellModeDialog() {
        String[] modes = {
                getString(R.string.unshell_mode_fixed),
                getString(R.string.unshell_mode_memory_scan),
                getString(R.string.unshell_mode_loadclass_hook),
                getString(R.string.unshell_mode_root_memory)
        };

        String currentMode = sharedPreferences.getString("unshell_mode", "fixed");
        int selectedIndex = 0;
        switch (currentMode) {
            case "memory_scan":
                selectedIndex = 1;
                break;
            case "loadclass_hook":
                selectedIndex = 2;
                break;
            case "root_memory":
                selectedIndex = 3;
                break;
        }

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.settings_unshell_mode)
                .setSingleChoiceItems(modes, selectedIndex, null)
                .setPositiveButton(R.string.confirm, (dialog, which) -> {
                    int pos = ((AlertDialog) dialog).getListView().getCheckedItemPosition();
                    saveUnshellMode(pos);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void saveUnshellMode(int modeIndex) {
        String mode;
        switch (modeIndex) {
            case 1:
                mode = "memory_scan";
                break;
            case 2:
                mode = "loadclass_hook";
                break;
            case 3:
                mode = "root_memory";
                break;
            default:
                mode = "fixed";
                break;
        }

        sharedPreferences.edit().putString("unshell_mode", mode).apply();
        updateCurrentSettings();

        // Root 内存扫描只负责扫描目标进程内存，不再注入主动调用触发器。
        if ("root_memory".equals(mode)) {
            showFloatingWindowPrompt();
        } else {
            // 切换到其他模式时，自动关闭悬浮窗。
            disableFloatingWindow();
        }
    }

    /**
     * 显示悬浮窗开启提示
     */
    private void showFloatingWindowPrompt() {
        boolean floatingEnabled = sharedPreferences.getBoolean("floating_window_enabled", false);

        String[] options = {"启用悬浮窗", "暂不启用"};
        new AlertDialog.Builder(requireContext())
                .setTitle("Root内存脱壳悬浮窗")
                .setMessage("悬浮窗将显示在屏幕上，点击后可选择运行中的进程进行脱壳。\n(类似GG修改器的悬浮图标)\n\n需要授权\"显示在其他应用上层\"权限。")
                .setPositiveButton(options[0], (dialog, which) -> {
                    enableFloatingWindow();
                })
                .setNegativeButton(options[1], (dialog, which) -> {
                    disableFloatingWindow();
                })
                .show();
    }

    /**
     * 启用悬浮窗
     */
    private void enableFloatingWindow() {
        // 检查悬浮窗权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(requireContext())) {
                // 需要授权悬浮窗权限，跳转到系统设置
                Snackbar.make(rootView, R.string.overlay_permission_required, Snackbar.LENGTH_LONG)
                        .setAction("去授权", v -> {
                            try {
                                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:" + requireContext().getPackageName()));
                                requireActivity().startActivityForResult(intent, 1003);
                            } catch (Exception e) {
                                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                                requireActivity().startActivityForResult(intent, 1003);
                            }
                        }).show();
                return;
            }
        }

        // 权限已授权，启动服务
        startFloatingService();
    }

    /**
     * 启动悬浮窗服务
     */
    private void startFloatingService() {
        sharedPreferences.edit().putBoolean("floating_window_enabled", true).apply();
        try {
            FloatingWindowService.start(requireContext());
            Snackbar.make(rootView, "悬浮窗已启用", Snackbar.LENGTH_SHORT).show();
        } catch (Exception e) {
            Snackbar.make(rootView, "启动悬浮窗失败: " + e.getMessage(), Snackbar.LENGTH_LONG).show();
        }
    }

    /**
     * 禁用悬浮窗
     */
    private void disableFloatingWindow() {
        sharedPreferences.edit().putBoolean("floating_window_enabled", false).apply();
        try {
            FloatingWindowService.stop(requireContext());
        } catch (Exception ignored) {}
    }

    /**
     * 由 MainActivity 调用，处理悬浮窗权限授权结果
     */
    public void onOverlayPermissionResult(boolean granted) {
        if (granted) {
            startFloatingService();
        } else {
            Snackbar.make(rootView, "悬浮窗权限被拒绝", Snackbar.LENGTH_SHORT).show();
        }
    }

    private void setupActiveLoadCard() {
        View cardActiveLoad = rootView.findViewById(R.id.card_active_load);
        if (cardActiveLoad != null) {
            cardActiveLoad.setOnClickListener(v -> showActiveLoadDialog());
        }
    }

    private void setupDeepUnpackCard() {
        View cardDeepUnpack = rootView.findViewById(R.id.card_deep_unpack);
        if (cardDeepUnpack != null) {
            cardDeepUnpack.setOnClickListener(v -> showDeepUnpackDialog());
        }
    }

    private void setupMethodTriggerCard() {
        View card = rootView.findViewById(R.id.card_method_trigger);
        if (card != null) card.setOnClickListener(v -> showMethodTriggerDialog());
    }

    private void showMethodTriggerDialog() {
        ConfigManager configManager = new ConfigManager(requireContext());
        boolean enabled = configManager.getMethodTriggerEnabled();
        String message = "仅在深度脱壳时，对筛选出的安全候选方法进行受限触发，"
                + "触发后重新解析 code-item。默认关闭。\n\n"
                + "保护：方法/类数量上限、单方法超时、总时限、失败熔断和危险名称过滤。\n"
                + "注意：目标进程内无法可靠终止不响应中断的死循环方法，超时后会停止本轮调度。";
        new AlertDialog.Builder(requireContext())
                .setTitle(enabled ? "禁用方法级触发" : "启用方法级触发")
                .setMessage(message)
                .setPositiveButton(enabled ? "禁用" : "启用", (d, w) -> {
                    configManager.setMethodTriggerEnabled(!enabled);
                    sharedPreferences.edit().putBoolean("method_trigger_enabled", !enabled).apply();
                    updateCurrentSettings();
                    Snackbar.make(rootView, !enabled ? "方法级触发已启用" : "方法级触发已禁用", Snackbar.LENGTH_LONG).show();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showDeepUnpackDialog() {
        ConfigManager configManager = new ConfigManager(requireContext());
        boolean current = configManager.getDeepUnpack();

        String message = "在执行固定结构脱壳时解析每个 DEX 的方法 code-item，\n"
                + "并将方法签名、method_idx 和指令内容写入记录文件。\n\n"
                + "注意：\n"
                + "- 不主动调用任何类，不触发陷阱类检测\n"
                + "- 不依赖类是否已经加载\n"
                + "- 仅在固定结构 dump 时执行";

        if (!current) {
            // 当前禁用 → 确认即启用
            new AlertDialog.Builder(requireContext())
                    .setTitle("启用深度脱壳")
                    .setMessage(message)
                    .setPositiveButton("启用", (dialog, which) -> {
                        configManager.setDeepUnpack(true);
                        sharedPreferences.edit().putBoolean("deep_unpack", true).apply();
                        updateCurrentSettings();
                        Snackbar.make(rootView, "深度脱壳已启用", Snackbar.LENGTH_SHORT).show();
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        } else {
            // 当前启用 → 确认即禁用
            new AlertDialog.Builder(requireContext())
                    .setTitle("禁用深度脱壳")
                    .setMessage("深度脱壳当前已启用，禁用后将不再采集方法字节码。\n（已采集的数据不受影响）")
                    .setPositiveButton("禁用", (dialog, which) -> {
                        configManager.setDeepUnpack(false);
                        sharedPreferences.edit().putBoolean("deep_unpack", false).apply();
                        updateCurrentSettings();
                        Snackbar.make(rootView, "深度脱壳已禁用", Snackbar.LENGTH_SHORT).show();
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        }
    }

    private void showActiveLoadDialog() {
        ConfigManager configManager = new ConfigManager(requireContext());
        Set<String> currentModes = configManager.getActiveCallModes();

        final String[] modeKeys = {
                "",
                ConfigManager.ACTIVE_CALL_MODE_XPOSED_JAVA,
                ConfigManager.ACTIVE_CALL_MODE_XPOSED_C
        };
        final String[] options = {
                getString(R.string.active_load_mode_off),
                getString(R.string.active_load_mode_java),
                getString(R.string.active_load_mode_c)
        };

        // 显示加载状态：禁用入口卡片避免连点
        final View cardActiveLoad = rootView.findViewById(R.id.card_active_load);
        if (cardActiveLoad != null) cardActiveLoad.setEnabled(false);

        final int[] selected = new int[]{
                currentModes.contains(ConfigManager.ACTIVE_CALL_MODE_XPOSED_C) ? 2 :
                        (currentModes.contains(ConfigManager.ACTIVE_CALL_MODE_XPOSED_JAVA) ? 1 : 0)
        };
        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.settings_active_load)
                .setSingleChoiceItems(options, selected[0], (d, which) -> {
                    selected[0] = which;
                })
                .setPositiveButton(R.string.confirm, (d, which) -> {
                    Set<String> selectedModes = new HashSet<>();
                    if (selected[0] > 0) selectedModes.add(modeKeys[selected[0]]);
                    saveActiveCallModes(selectedModes);
                })
                .setNegativeButton(R.string.cancel, null)
                .setOnDismissListener(d -> {
                    if (cardActiveLoad != null) cardActiveLoad.setEnabled(true);
                })
                .create();
        dialog.show();
    }

    private void saveActiveCallModes(Set<String> modes) {
        ConfigManager configManager = new ConfigManager(requireContext());
        boolean ok = configManager.setActiveCallModes(modes);
        if (!ok) {
            Snackbar.make(rootView, R.string.active_load_invalid_combo, Snackbar.LENGTH_SHORT).show();
            return;
        }
        updateCurrentSettings();
        if (modes.isEmpty()) {
            Snackbar.make(rootView, R.string.active_load_none_selected, Snackbar.LENGTH_LONG).show();
        } else {
            Snackbar.make(rootView, R.string.active_load_saved, Snackbar.LENGTH_SHORT).show();
        }
    }

    private void updateCurrentSettings() {
        TextView tvCurrentPermission = rootView.findViewById(R.id.tv_current_permission);
        TextView tvCurrentMode = rootView.findViewById(R.id.tv_current_mode);
        TextView tvCurrentActiveLoad = rootView.findViewById(R.id.tv_current_active_load);
        TextView tvCurrentMethodTrigger = rootView.findViewById(R.id.tv_current_method_trigger);

        // 更新权限方式显示
        String permissionMethod = sharedPreferences.getString("permission_method", "none");
        String permissionText;
        switch (permissionMethod) {
            case "shizuku":
                permissionText = getString(R.string.permission_shizuku);
                break;
            case "root":
                permissionText = getString(R.string.permission_root);
                break;
            default:
                permissionText = "未设置";
                break;
        }
        if (tvCurrentPermission != null) {
            tvCurrentPermission.setText("当前: " + permissionText);
        }

        // 更新脱壳模式显示
        String unshellMode = sharedPreferences.getString("unshell_mode", "fixed");
        String modeText;
        switch (unshellMode) {
            case "memory_scan":
                modeText = getString(R.string.unshell_mode_memory_scan);
                break;
            case "loadclass_hook":
                modeText = getString(R.string.unshell_mode_loadclass_hook);
                break;
            case "root_memory":
                modeText = getString(R.string.unshell_mode_root_memory);
                break;
            default:
                modeText = getString(R.string.unshell_mode_fixed);
                break;
        }
        if (tvCurrentMode != null) {
            tvCurrentMode.setText("当前: " + modeText);
        }

        // 更新主动调用模式显示
        if (tvCurrentActiveLoad != null) {
            ConfigManager configManager = new ConfigManager(requireContext());
            Set<String> modes = configManager.getActiveCallModes();
            StringBuilder sb = new StringBuilder("当前: ");
            if (modes.isEmpty()) {
                sb.append(getString(R.string.active_load_disabled));
            } else {
                if (modes.contains(ConfigManager.ACTIVE_CALL_MODE_XPOSED_C)) {
                    sb.append(getString(R.string.active_load_mode_c));
                } else {
                    sb.append(getString(R.string.active_load_mode_java));
                }
            }
            tvCurrentActiveLoad.setText(sb.toString());
        }

        // 更新深度脱壳显示
        TextView tvCurrentDeepUnpack = rootView.findViewById(R.id.tv_current_deep_unpack);
        if (tvCurrentDeepUnpack != null) {
            ConfigManager configManager = new ConfigManager(requireContext());
            boolean deep = configManager.getDeepUnpack();
            tvCurrentDeepUnpack.setText("当前: " + (deep ? "启用" : "禁用"));
        }
        if (tvCurrentMethodTrigger != null) {
            ConfigManager configManager = new ConfigManager(requireContext());
            tvCurrentMethodTrigger.setText("当前: " + (configManager.getMethodTriggerEnabled() ? "启用（安全模式）" : "禁用"));
        }
    }
}
