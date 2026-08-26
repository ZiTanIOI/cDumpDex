package com.zitan.cdumpdex.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.zitan.cdumpdex.R;
import com.zitan.cdumpdex.model.AppInfo;

import java.util.List;

public class AppListAdapter extends RecyclerView.Adapter<AppListAdapter.ViewHolder> {

    private final List<AppInfo> appList;
    private final OnItemClickListener listener;

    public interface OnItemClickListener {
        void onItemClick(AppInfo appInfo);
    }

    public AppListAdapter(List<AppInfo> appList, OnItemClickListener listener) {
        this.appList = appList;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_app, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AppInfo appInfo = appList.get(position);
        holder.bind(appInfo, listener);
    }

    @Override
    public int getItemCount() {
        return appList.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        private final ImageView ivAppIcon;
        private final TextView tvAppName;
        private final TextView tvPackageName;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            ivAppIcon = itemView.findViewById(R.id.iv_app_icon);
            tvAppName = itemView.findViewById(R.id.tv_app_name);
            tvPackageName = itemView.findViewById(R.id.tv_package_name);
        }

        public void bind(AppInfo appInfo, OnItemClickListener listener) {
            if (ivAppIcon != null) {
                ivAppIcon.setImageDrawable(appInfo.getAppIcon());
            }
            if (tvAppName != null) {
                String suffix = appInfo.isSystemApp() ? " (系统)" : "";
                tvAppName.setText(appInfo.getAppName() + suffix);
            }
            if (tvPackageName != null) {
                tvPackageName.setText(appInfo.getPackageName());
            }

            itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onItemClick(appInfo);
                }
            });
        }
    }
}
