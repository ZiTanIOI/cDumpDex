package com.zitan.cdumpdex.model;

import android.graphics.drawable.Drawable;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.List;

public class AppInfo implements Parcelable {
    private String packageName;
    private String appName;
    private Drawable appIcon;
    private String sourceDir;
    private String dataDir;
    private String versionName;
    private int versionCode;
    private int targetSdk;
    private int uid;
    private boolean systemApp;
    private List<String> permissions;

    public AppInfo() {}

    protected AppInfo(Parcel in) {
        packageName = in.readString();
        appName = in.readString();
        sourceDir = in.readString();
        dataDir = in.readString();
        versionName = in.readString();
        versionCode = in.readInt();
        targetSdk = in.readInt();
        uid = in.readInt();
        systemApp = in.readByte() != 0;
        permissions = in.createStringArrayList();
    }

    public static final Creator<AppInfo> CREATOR = new Creator<AppInfo>() {
        @Override
        public AppInfo createFromParcel(Parcel in) {
            return new AppInfo(in);
        }

        @Override
        public AppInfo[] newArray(int size) {
            return new AppInfo[size];
        }
    };

    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    public Drawable getAppIcon() {
        return appIcon;
    }

    public void setAppIcon(Drawable appIcon) {
        this.appIcon = appIcon;
    }

    public String getSourceDir() {
        return sourceDir;
    }

    public void setSourceDir(String sourceDir) {
        this.sourceDir = sourceDir;
    }

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    public String getVersionName() {
        return versionName;
    }

    public void setVersionName(String versionName) {
        this.versionName = versionName;
    }

    public int getVersionCode() {
        return versionCode;
    }

    public void setVersionCode(int versionCode) {
        this.versionCode = versionCode;
    }

    public int getTargetSdk() {
        return targetSdk;
    }

    public void setTargetSdk(int targetSdk) {
        this.targetSdk = targetSdk;
    }

    public int getUid() {
        return uid;
    }

    public void setUid(int uid) {
        this.uid = uid;
    }

    public boolean isSystemApp() {
        return systemApp;
    }

    public void setSystemApp(boolean systemApp) {
        this.systemApp = systemApp;
    }

    public List<String> getPermissions() {
        return permissions;
    }

    public void setPermissions(List<String> permissions) {
        this.permissions = permissions;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(packageName);
        dest.writeString(appName);
        dest.writeString(sourceDir);
        dest.writeString(dataDir);
        dest.writeString(versionName);
        dest.writeInt(versionCode);
        dest.writeInt(targetSdk);
        dest.writeInt(uid);
        dest.writeByte((byte) (systemApp ? 1 : 0));
        dest.writeStringList(permissions);
    }
}
