package com.zitan.cdumpdex.ui;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.zitan.cdumpdex.R;

public class AboutFragment extends Fragment {

    private static final String QQ_NUMBER = "732275573";
    private static final String AVATAR_URL = "https://q1.qlogo.cn/g?b=qq&nk=" + QQ_NUMBER + "&s=640";

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_about, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        setupAvatar(view);
        setupVersion(view);
        setupClickListeners(view);
    }

    private void setupAvatar(View view) {
        ImageView ivAvatar = view.findViewById(R.id.iv_avatar);
        if (ivAvatar != null) {
            try {
                Glide.with(this)
                        .load(AVATAR_URL)
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .placeholder(R.mipmap.ic_launcher_round)
                        .error(R.mipmap.ic_launcher_round)
                        .circleCrop()
                        .into(ivAvatar);
            } catch (Exception e) {
                ivAvatar.setImageResource(R.mipmap.ic_launcher_round);
            }
        }
    }

    private void setupVersion(View view) {
        TextView tvVersion = view.findViewById(R.id.tv_version);
        if (tvVersion != null) {
            try {
                PackageInfo pInfo = requireContext().getPackageManager()
                        .getPackageInfo(requireContext().getPackageName(), 0);
                String versionName = pInfo.versionName;
                long versionCode;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    versionCode = pInfo.getLongVersionCode();
                } else {
                    versionCode = pInfo.versionCode;
                }
                tvVersion.setText(versionName + " (" + versionCode + ")");
            } catch (PackageManager.NameNotFoundException e) {
                tvVersion.setText("Unknown");
            }
        }
    }

    private void setupClickListeners(View view) {
        View cardAuthor = view.findViewById(R.id.card_author);
        View cardQq = view.findViewById(R.id.card_qq);

        View.OnClickListener qqClickListener = v -> openQQChat();

        if (cardAuthor != null) {
            cardAuthor.setOnClickListener(qqClickListener);
        }
        if (cardQq != null) {
            cardQq.setOnClickListener(qqClickListener);
        }
    }

    private void openQQChat() {
        try {
            String url = "mqqwpa://im/chat?chat_type=wpa&uin=" + QQ_NUMBER;
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://qm.qq.com/cgi-bin/qm/qr?k=&noverify=0&personal_qrcode_source=3"));
                startActivity(intent);
            } catch (Exception e2) {
                Toast.makeText(requireContext(), "无法打开QQ", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
