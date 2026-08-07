package com.viimk;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;
import androidx.core.content.FileProvider;

import java.io.File;

public class MainActivity extends Activity {

    private static final String TAG = "VIIMK_Main";
    private static final int REQUEST_UNKNOWN_APK_SOURCES = 1001;
    private static final int REQUEST_INSTALL_PERM = 1002;

    private WebView webView;
    private String pendingApkPath;   // 安装未知来源权限申请后，待安装的 APK 路径
    private boolean mSystemUiForcedImmersive = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        // 默认竖屏
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        webView = new WebView(this);
        webView.setBackgroundColor(0x00000000);
        webView.setLayerType(WebView.LAYER_TYPE_HARDWARE, null);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setSupportMultipleWindows(false);
        // 在 UA 末尾追加 VIIMK-App/<version> 标识，H5 无需 plus.runtime 也能识别自己是在 App 内
        settings.setUserAgentString(settings.getUserAgentString() + " VIIMK-App/" + getVersionName());

        // 注入 JS Bridge: window.VIIMKAppBridge
        webView.addJavascriptInterface(new VIIMKAppBridge(this), "VIIMKAppBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (uri == null) return false;
                String scheme = uri.getScheme();
                // 跳转外部: http/https 继续在本 WebView 内加载；其它协议（market/intent 等）交给系统处理
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme) || "file".equalsIgnoreCase(scheme)) {
                    return false;
                }
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    return true;
                } catch (Exception e) {
                    Log.w(TAG, "scheme jump failed: " + uri, e);
                    return false;
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // 页面 ready 后把本地版本信息挂到 window.VIIMK_APP_INFO 上
                // （JS 读不到 Android PackageManager，所以这里主动暴露）
                String infoJson = getAppInfoJson();
                String js = "(function(){try{window.VIIMK_APP_INFO=" + infoJson + ";}catch(e){}})();";
                view.evaluateJavascript(js, null);
                webView.setBackgroundColor(0x00000000);
            }
        });

        webView.setWebChromeClient(new WebChromeClient());
        webView.loadUrl("file:///android_asset/app/index.html");
    }

    private String getVersionName() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName == null ? "1.0.0" : pi.versionName;
        } catch (Throwable t) {
            return "1.0.0";
        }
    }

    private int getVersionCodeInt() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return (int) pi.getLongVersionCode();
            } else {
                return pi.versionCode;
            }
        } catch (Throwable t) {
            return 0;
        }
    }

    private String getAppInfoJson() {
        // 与 src/config/app.js / build.gradle / mock 保持一致的语义
        // H5 获取后作为本地版本与 /api/version 返回的 server 版本对比
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            boolean isDebug = (0 != (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE));
            int vc;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                vc = (int) pi.getLongVersionCode();
            } else {
                vc = pi.versionCode;
            }
            StringBuilder sb = new StringBuilder(256);
            sb.append('{')
                .append("\"platform\":\"android\",")
                .append("\"inWebView\":true,")
                .append("\"packageName\":\"").append(getPackageName()).append("\",")
                .append("\"versionName\":\"").append(pi.versionName == null ? "" : pi.versionName).append("\",")
                .append("\"versionCode\":").append(vc).append(',')
                .append("\"build\":\"20260807\",")
                .append("\"sdkInt\":").append(Build.VERSION.SDK_INT).append(',')
                .append("\"debuggable\":").append(isDebug ? "true" : "false").append(',')
                .append("\"deviceModel\":\"").append(escapeJson(Build.MODEL)).append("\"")
                .append('}');
            return sb.toString();
        } catch (Throwable t) {
            return "{\"platform\":\"android\",\"inWebView\":true,\"packageName\":\"com.viimk\","
                 + "\"versionName\":\"1.0.0\",\"versionCode\":100,\"build\":\"20260807\"}";
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"':  sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    /** JS -> Android 调用入口 */
    public class VIIMKAppBridge {
        private final Context ctx;

        VIIMKAppBridge(Context ctx) { this.ctx = ctx; }

        @JavascriptInterface
        public String getAppInfo() { return getAppInfoJson(); }

        @JavascriptInterface
        public boolean hasUpdate(int serverVersionCode) {
            return serverVersionCode > getVersionCodeInt();
        }

        /** 用系统浏览器打开 URL（兜底，用于蓝奏云分享页等需在浏览器里下载 APK 的场景） */
        @JavascriptInterface
        public void openUrl(String url) {
            if (url == null || url.isEmpty()) return;
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(intent);
            } catch (Throwable t) {
                Log.e(TAG, "openUrl failed: " + url, t);
                toast("无法打开链接");
            }
        }

        /**
         * 请求屏幕方向：H5 调用
         *  orientation: "portrait" 竖屏 | "landscape" 横屏 | "unspecified" 跟随系统
         */
        @JavascriptInterface
        public void setOrientation(final String orientation) {
            final String o = orientation == null ? "portrait" : orientation;
            runOnUiThread(() -> {
                try {
                    if ("landscape".equalsIgnoreCase(o)) {
                        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                        applyImmersiveSystemUi(true);
                    } else if ("unspecified".equalsIgnoreCase(o)) {
                        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                        applyImmersiveSystemUi(false);
                    } else {
                        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                        applyImmersiveSystemUi(false);
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "setOrientation failed: " + o, t);
                }
            });
        }

        /** 全屏沉浸模式（横屏时隐藏状态栏/导航栏，视频真正铺满） */
        private void applyImmersiveSystemUi(final boolean immersive) {
            mSystemUiForcedImmersive = immersive;
            try {
                View decor = getWindow().getDecorView();
                int flags = decor.getSystemUiVisibility();
                int immersiveFlags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
                if (immersive) {
                    flags |= immersiveFlags;
                } else {
                    flags &= ~(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
                }
                decor.setSystemUiVisibility(flags);
            } catch (Throwable t) {
                Log.w(TAG, "applyImmersiveSystemUi failed", t);
            }
        }

        /** 调 Android DownloadManager / 系统安装器 安装 APK。
         *  当前版本实现：先请求必要权限，然后用内置线程直接下载到外部公共目录，最后触发安装。
         *  简化起见：统一用 openUrl 走浏览器下载（蓝奏云分享页更稳）。该方法保留给直链场景。
         */
        @JavascriptInterface
        public void installApk(String localPath) {
            pendingApkPath = localPath;
            if (localPath == null || localPath.isEmpty()) {
                toast("APK 路径为空");
                return;
            }
            File apk = new File(localPath);
            if (!apk.exists()) {
                toast("APK 文件不存在");
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (getPackageManager().canRequestPackageInstalls()) {
                    startInstall(apk);
                } else {
                    // 跳设置让用户打开「允许此来源安装应用」
                    Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName()));
                    startActivityForResult(intent, REQUEST_INSTALL_PERM);
                }
            } else {
                startInstall(apk);
            }
        }

        private void startInstall(File apk) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                Uri apkUri;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    apkUri = FileProvider.getUriForFile(
                        ctx, getPackageName() + ".fileprovider", apk);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } else {
                    apkUri = Uri.fromFile(apk);
                }
                intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(intent);
            } catch (Throwable t) {
                Log.e(TAG, "install failed", t);
                toast("启动安装失败，请使用浏览器下载后手动安装");
            }
        }

        private void toast(final String msg) {
            runOnUiThread(() -> Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show());
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 返回键：若处于横屏/沉浸模式，先恢复竖屏再判断是否 goBack
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (mSystemUiForcedImmersive) {
                // 回到竖屏
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                runOnUiThread(() -> {
                    try {
                        View decor = getWindow().getDecorView();
                        int flags = decor.getSystemUiVisibility();
                        flags &= ~(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
                        decor.setSystemUiVisibility(flags);
                    } catch (Throwable ignore) {}
                });
                mSystemUiForcedImmersive = false;
                // 通知 H5 退出全屏（player 会清除 isFullscreen）
                notifyExitFullscreen();
                return true;
            }
            if (webView.canGoBack()) {
                webView.goBack();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    /** 通知 H5 退出全屏（返回键触发） */
    private void notifyExitFullscreen() {
        if (webView == null) return;
        String js = "(function(){try{"
            + "var evt=new CustomEvent('viimkExitFullscreen');window.dispatchEvent(evt);"
            + "if(window.__vmkExitFullscreen) window.__vmkExitFullscreen();"
            + "}catch(e){}})();";
        runOnUiThread(() -> webView.evaluateJavascript(js, null));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) webView.onPause();
    }

    @Override
    protected void onDestroy() {
        // 退出前恢复竖屏，避免旋转泄漏
        try { setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT); } catch (Throwable ignore) {}
        if (webView != null) {
            webView.stopLoading();
            webView.clearHistory();
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
