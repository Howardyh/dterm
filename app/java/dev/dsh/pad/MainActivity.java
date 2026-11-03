package dev.dsh.pad;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A chrome-less Android shell around the DeepSeek Harness web UI served on
 * loopback by the Termux/PRoot runtime.
 *
 * The app itself never runs the harness: it only opens a window on
 * http://127.0.0.1:3080, keeps the browser session cookie, and offers native
 * screens for the two failure modes a loopback client can hit: the service is
 * not running yet, or the one-time launch token has not been exchanged.
 */
public class MainActivity extends Activity {

    private static final String PREFS = "dsh_shell";
    private static final String KEY_ORIGIN = "origin";
    private static final String DEFAULT_ORIGIN = "http://127.0.0.1:3080";
    private static final String TERMUX_PACKAGE = "com.termux";
    private static final String RUN_COMMAND = "dsh-runner";

    private static final int STATE_SPLASH = 0;
    private static final int STATE_WEB = 1;
    private static final int STATE_AUTH = 2;
    private static final int STATE_OFFLINE = 3;

    private static final int REQ_FILE = 1001;
    private static final int REQ_STORAGE = 1002;
    private static final int REQ_MIC = 1003;
    private static final int REQ_TERMUX_RUN = 1004;

    /** Termux RUN_COMMAND bridge (Termux must set allow-external-apps=true). */
    private static final String TERMUX_RUN_PERMISSION = "com.termux.permission.RUN_COMMAND";
    private static final String TERMUX_BIN = "/data/data/com.termux/files/usr/bin";
    private static final String TERMUX_HOME = "/data/data/com.termux/files/home";

    /** Launcher shortcuts (long-press the app icon). */
    private static final String ACTION_RELOAD = "dev.dsh.pad.action.RELOAD";
    private static final String ACTION_BROWSER = "dev.dsh.pad.action.BROWSER";
    private static final String ACTION_TERMUX = "dev.dsh.pad.action.TERMUX";

    private static final Pattern TOKEN_PATTERN =
            Pattern.compile("(?:^|[?&#])token=([A-Za-z0-9_-]{20,256})");
    private static final Pattern BARE_TOKEN =
            Pattern.compile("^[A-Za-z0-9_-]{20,256}$");

    private final Handler main = new Handler(Looper.getMainLooper());

    private SharedPreferences prefs;
    private String origin = DEFAULT_ORIGIN;

    private FrameLayout root;
    private WebView web;
    private ProgressBar progress;
    private View splash;
    private TextView splashNote;
    private View authView;
    private TextView authError;
    private View offlineView;
    private TextView offlineNote;

    private int state = STATE_SPLASH;
    private boolean probing = false;
    private boolean pageError = false;

    private ValueCallback<Uri[]> fileCallback;
    private Uri pendingDownload;
    private PermissionRequest pendingMicRequest;
    private boolean waitingForService = false;
    private boolean foreground = false;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        origin = prefs.getString(KEY_ORIGIN, DEFAULT_ORIGIN);
        if (!origin.startsWith("http://") && !origin.startsWith("https://")) origin = DEFAULT_ORIGIN;

        applySystemBars();
        buildUi();
        registerBackCallback();
        boolean launchedWithToken = handleIntent(getIntent());
        if (!launchedWithToken) probeAndEnter();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        foreground = true;
        if (web != null) web.onResume();
        if (state != STATE_WEB && !probing) probeAndEnter();
    }

    @Override
    protected void onPause() {
        foreground = false;
        if (web != null) web.onPause();
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.setWebChromeClient(null);
            web.destroy();
            web = null;
        }
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        handleBack();
    }

    /** API 33+ routes back gestures to the platform callback instead. */
    private void registerBackCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        try {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    new android.window.OnBackInvokedCallback() {
                        @Override
                        public void onBackInvoked() {
                            handleBack();
                        }
                    });
        } catch (Exception ignored) {
            // Fall back to onBackPressed on builds without the dispatcher.
        }
    }

    private void handleBack() {
        if (state == STATE_WEB && web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        if (state == STATE_WEB) {
            moveTaskToBack(true);
            return;
        }
        finish();
    }

    // --------------------------------------------------------------------- ui

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int color(int res) {
        return getColor(res);
    }

    private android.graphics.drawable.GradientDrawable rounded(int colorRes, float radiusDp) {
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(color(colorRes));
        bg.setCornerRadius(dp(radiusDp));
        return bg;
    }

    private void applySystemBars() {
        Window window = getWindow();
        window.setStatusBarColor(color(R.color.window_bg));
        window.setNavigationBarColor(color(R.color.window_bg));
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        boolean dark = isNight();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            View decor = window.getDecorView();
            int flags = decor.getSystemUiVisibility();
            if (dark) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (dark) {
                    flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                } else {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
            }
            decor.setSystemUiVisibility(flags);
        }
    }

    private boolean isNight() {
        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(color(R.color.window_bg));
        setContentView(root);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            progress.setProgressTintList(android.content.res.ColorStateList.valueOf(color(R.color.accent)));
            progress.setProgressBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(color(R.color.divider)));
        }
        FrameLayout.LayoutParams progressLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3));
        progressLp.gravity = Gravity.TOP;
        root.addView(progress, progressLp);

        splash = buildSplash();
        root.addView(splash, matchFrame());
        authView = buildAuth();
        root.addView(authView, matchFrame());
        offlineView = buildOffline();
        root.addView(offlineView, matchFrame());

        showOnly(splash);
    }

    private FrameLayout.LayoutParams matchFrame() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private View buildSplash() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setPadding(dp(32), dp(32), dp(32), dp(32));

        TextView mark = new TextView(this);
        mark.setText(">_");
        mark.setTextSize(TypedValue.COMPLEX_UNIT_SP, 56);
        mark.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        mark.setTextColor(color(R.color.accent));
        mark.setGravity(Gravity.CENTER);
        column.addView(mark);

        TextView title = new TextView(this);
        title.setText("Dterm");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(color(R.color.text_primary));
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = dp(10);
        column.addView(title, titleLp);

        splashNote = new TextView(this);
        splashNote.setText("正在连接本机服务…");
        splashNote.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        splashNote.setTextColor(color(R.color.text_secondary));
        splashNote.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        noteLp.topMargin = dp(14);
        column.addView(splashNote, noteLp);

        ProgressBar spinner = new ProgressBar(this);
        LinearLayout.LayoutParams spinLp = new LinearLayout.LayoutParams(dp(28), dp(28));
        spinLp.topMargin = dp(22);
        column.addView(spinner, spinLp);

        FrameLayout wrap = new FrameLayout(this);
        wrap.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        return wrap;
    }

    private View buildAuth() {
        LinearLayout card = card();

        TextView title = heading("需要登录一次");
        card.addView(title);

        TextView body = paragraph(
                "DSH 每个进程只接受带一次性令牌的访问。"
                        + "\n\n请从浏览器地址栏复制当前 DSH 的完整链接（包含 token=… 的那一段），"
                        + "粘贴到下面。只需一次，之后本应用会自动保持登录。");
        card.addView(body);

        final EditText input = new EditText(this);
        input.setHint("粘贴 http://127.0.0.1:3080/?token=…");
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        input.setTextColor(color(R.color.text_primary));
        input.setHintTextColor(color(R.color.text_secondary));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setSingleLine(false);
        input.setMaxLines(4);
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inputLp.topMargin = dp(18);
        card.addView(input, inputLp);

        authError = new TextView(this);
        authError.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        authError.setTextColor(0xFFE5484D);
        authError.setVisibility(View.GONE);
        LinearLayout.LayoutParams errLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        errLp.topMargin = dp(10);
        card.addView(authError, errLp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(18);

        TextView paste = button("从剪贴板粘贴", false);
        paste.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String clip = readClipboard();
                if (TextUtils.isEmpty(clip)) {
                    toast("剪贴板是空的");
                    return;
                }
                input.setText(clip);
                input.setSelection(input.getText().length());
                if (extractToken(clip) != null) submitToken(clip);
            }
        });

        TextView go = button("进入", true);
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                submitToken(input.getText().toString());
            }
        });
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                    submitToken(v.getText().toString());
                    return true;
                }
                return false;
            }
        });

        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(paste, btnLp);
        LinearLayout.LayoutParams goLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        goLp.leftMargin = dp(10);
        row.addView(go, goLp);
        card.addView(row, rowLp);

        TextView retryLink = new TextView(this);
        retryLink.setText("自动获取失败？点此重试");
        retryLink.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        retryLink.setTextColor(color(R.color.accent));
        retryLink.setPadding(0, dp(14), 0, 0);
        retryLink.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                authError.setVisibility(View.GONE);
                probeAndEnter();
            }
        });
        card.addView(retryLink);

        TextView help = paragraph("提示：在浏览器里打开 DSH 后，长按地址栏即可复制完整链接。");
        LinearLayout.LayoutParams helpLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        helpLp.topMargin = dp(16);
        card.addView(help, helpLp);

        return scrim(card);
    }

    private View buildOffline() {
        LinearLayout card = card();

        card.addView(heading("DSH 后台服务未运行"));

        card.addView(paragraph(
                "这个应用只是 DSH 的一个窗口，真正的服务跑在 Termux 里。"
                        + "\n\n① 点“启动 DSH”，允许后会在 Termux 里自动执行 dsh-start"
                        + "\n② 也可以打开 Termux 手动运行 dsh-runner"
                        + "\n③ 如果 Termux 只在后台闪了一下没执行，应用会自动切到 Termux 触发一次"
                        + "\n④ 服务起来后会自动进入，不用手动刷新"));

        offlineNote = new TextView(this);
        offlineNote.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        offlineNote.setTextColor(color(R.color.text_secondary));
        offlineNote.setVisibility(View.GONE);
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        noteLp.topMargin = dp(10);
        card.addView(offlineNote, noteLp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(18);

        TextView start = button("启动 DSH", true);
        start.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestTermuxStart();
            }
        });
        TextView retry = button("重试", false);
        retry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                probeAndEnter();
            }
        });

        row.addView(start, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams retryLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        retryLp.leftMargin = dp(10);
        row.addView(retry, retryLp);
        card.addView(row, rowLp);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams row2Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row2Lp.topMargin = dp(10);

        TextView termux = button("打开 Termux", false);
        termux.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openTermux();
            }
        });
        TextView copy = button("复制命令", false);
        copy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("dsh", RUN_COMMAND));
                toast("已复制：" + RUN_COMMAND + "（粘贴到 Termux 回车）");
            }
        });
        row2.addView(termux, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        copyLp.leftMargin = dp(10);
        row2.addView(copy, copyLp);
        card.addView(row2, row2Lp);

        LinearLayout tips = new LinearLayout(this);
        tips.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tipsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tipsLp.topMargin = dp(16);
        TextView tipTermux = link("给 Termux 开「显示在其它应用上层」→ 后台直接启动", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openOverlaySettings(TERMUX_PACKAGE);
            }
        });
        TextView tipDterm = link("给 Dterm 开「显示在其它应用上层」→ 启动后自动回来", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openOverlaySettings(getPackageName());
            }
        });
        tips.addView(tipTermux);
        tips.addView(tipDterm);
        card.addView(tips, tipsLp);

        TextView addr = paragraph("服务地址：" + origin
                + "\n长按桌面图标可用快捷菜单：重新加载 / 在浏览器打开 / 打开 Termux。");
        LinearLayout.LayoutParams addrLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addrLp.topMargin = dp(16);
        card.addView(addr, addrLp);

        return scrim(card);
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(22), dp(22), dp(22), dp(22));
        card.setBackground(rounded(R.color.card_bg, 20));
        card.setElevation(dp(2));
        return card;
    }

    private View scrim(LinearLayout card) {
        int width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(36), dp(560));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout holder = new LinearLayout(this);
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.setGravity(Gravity.CENTER_VERTICAL);
        holder.setPadding(dp(18), dp(24), dp(18), dp(24));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                width, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.CENTER_HORIZONTAL;
        holder.addView(card, cardLp);
        scroll.addView(holder, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return scroll;
    }

    private TextView heading(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        view.setTextColor(color(R.color.text_primary));
        return view;
    }

    private TextView paragraph(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        view.setLineSpacing(dp(4), 1f);
        view.setTextColor(color(R.color.text_secondary));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        view.setLayoutParams(lp);
        return view;
    }

    private TextView link(String label, View.OnClickListener listener) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        view.setTextColor(color(R.color.accent));
        view.setPadding(0, dp(10), 0, 0);
        view.setOnClickListener(listener);
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }

    private TextView button(String label, boolean primary) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        view.setGravity(Gravity.CENTER);
        view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        view.setPadding(dp(14), dp(13), dp(14), dp(13));
        view.setTextColor(primary ? color(R.color.accent_text) : color(R.color.text_primary));
        view.setBackground(rounded(primary ? R.color.accent : R.color.button_bg, 12));
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }

    // ------------------------------------------------------------ state machine

    private void showOnly(View overlay) {
        splash.setVisibility(overlay == splash ? View.VISIBLE : View.GONE);
        authView.setVisibility(overlay == authView ? View.VISIBLE : View.GONE);
        offlineView.setVisibility(overlay == offlineView ? View.VISIBLE : View.GONE);
        if (overlay != null) overlay.bringToFront();
    }

    private void showSplash(String note) {
        state = STATE_SPLASH;
        splashNote.setText(note);
        showOnly(splash);
        if (web != null) web.setVisibility(View.VISIBLE);
        progress.setVisibility(View.GONE);
    }

    private void showWeb() {
        state = STATE_WEB;
        splash.setVisibility(View.GONE);
        authView.setVisibility(View.GONE);
        offlineView.setVisibility(View.GONE);
        if (web != null) web.setVisibility(View.VISIBLE);
        progress.setVisibility(View.GONE);
    }

    private void showAuth(String error) {
        state = STATE_AUTH;
        authError.setVisibility(error == null ? View.GONE : View.VISIBLE);
        if (error != null) authError.setText(error);
        showOnly(authView);
        if (web != null) web.setVisibility(View.INVISIBLE);
        progress.setVisibility(View.GONE);
    }

    private void showOffline(String detail) {
        state = STATE_OFFLINE;
        offlineNote.setVisibility(detail == null ? View.GONE : View.VISIBLE);
        if (detail != null) offlineNote.setText(detail);
        showOnly(offlineView);
        if (web != null) web.setVisibility(View.INVISIBLE);
        progress.setVisibility(View.GONE);
    }

    /**
     * Ask the localhost service whether this app is already authenticated.
     * A 401 is not an error: it just means the launch token has not been
     * exchanged yet, and the token bridge can usually supply it.
     */
    private void probeAndEnter() {
        if (probing) return;
        probing = true;
        showSplash("正在连接 " + origin + " …");
        final String base = origin;
        final String cookie = CookieManager.getInstance().getCookie(base + "/");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final int status = probe(base, cookie);
                final String bridged = status == 401 ? fetchBridgeToken() : null;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        probing = false;
                        if (isFinishing()) return;
                        if (status == 200) {
                            enterWeb(base);
                        } else if (status == 401 && bridged != null) {
                            submitToken(bridged);
                        } else if (status == 401) {
                            showAuth(null);
                        } else if (status == -1) {
                            showOffline("无法连接 " + base + "（连接被拒绝或超时）");
                        } else {
                            showOffline("服务返回 HTTP " + status + "，请检查 Termux 里的 dsh 进程");
                        }
                    }
                });
            }
        }, "dsh-probe").start();
    }

    /** 200 = authenticated, 401 = needs the launch token, -1 = unreachable. */
    private int probe(String base, String cookie) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(base + "/").openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(5000);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("Accept", "text/html");
            if (!TextUtils.isEmpty(cookie)) conn.setRequestProperty("Cookie", cookie);
            return conn.getResponseCode();
        } catch (IOException e) {
            return -1;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Fetch the launch token from the loopback token bridge that
     * dsh-web-service starts next to the web server. Returns null when the
     * bridge is absent or refuses the shared secret, which simply means the
     * user has to paste the URL by hand.
     */
    private static String fetchBridgeToken() {
        if (TextUtils.isEmpty(BuildSecrets.BRIDGE_SECRET)) return null;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(BuildSecrets.BRIDGE_URL).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(1200);
            conn.setReadTimeout(1500);
            conn.setRequestProperty("Authorization", "Bearer " + BuildSecrets.BRIDGE_SECRET);
            if (conn.getResponseCode() != 200) return null;
            java.io.InputStream in = conn.getInputStream();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[512];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            in.close();
            String body = out.toString("UTF-8");
            String marker = "\"token\":\"";
            int at = body.indexOf(marker);
            if (at < 0) return null;
            int from = at + marker.length();
            int to = body.indexOf('"', from);
            if (to <= from) return null;
            String token = body.substring(from, to);
            return token.matches("[A-Za-z0-9_-]{20,256}") ? token : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void enterWeb(String base) {
        ensureWeb();
        web.setVisibility(View.VISIBLE);
        state = STATE_WEB;
        web.loadUrl(base + "/");
    }

    private void submitToken(String raw) {
        String token = extractToken(raw);
        if (token == null) {
            showAuth("没有识别到令牌。请复制浏览器地址栏里的完整链接（通常以 ?token= 结尾）。");
            return;
        }
        String newOrigin = extractOrigin(raw);
        if (newOrigin != null && !newOrigin.equals(origin)) {
            origin = newOrigin;
            prefs.edit().putString(KEY_ORIGIN, origin).apply();
        }
        ensureWeb();
        state = STATE_SPLASH;
        splashNote.setText("正在验证登录…");
        showOnly(splash);
        web.setVisibility(View.VISIBLE);
        progress.setVisibility(View.GONE);
        web.loadUrl(origin + "/?token=" + Uri.encode(token));
    }

    private static String extractToken(String raw) {
        if (raw == null) return null;
        String text = raw.trim();
        if (text.isEmpty()) return null;
        Matcher query = TOKEN_PATTERN.matcher(text);
        if (query.find()) return query.group(1);
        if (BARE_TOKEN.matcher(text).matches()) return text;
        return null;
    }

    private static String extractOrigin(String raw) {
        if (raw == null) return null;
        String text = raw.trim();
        if (!text.startsWith("http://") && !text.startsWith("https://")) return null;
        try {
            Uri uri = Uri.parse(text);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) return null;
            int port = uri.getPort();
            return scheme + "://" + host + (port > 0 ? ":" + port : "");
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isSameOrigin(String url) {
        if (url == null) return false;
        return url.startsWith(origin + "/") || url.equals(origin);
    }

    // ------------------------------------------------------------------ webview

    private void ensureWeb() {
        if (web != null) return;

        web = new WebView(this);
        web.setBackgroundColor(color(R.color.window_bg));
        web.setVerticalScrollBarEnabled(false);

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setTextZoom(100);
        settings.setUserAgentString(settings.getUserAgentString() + " DSHShell/1.0");

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookies.setAcceptThirdPartyCookies(web, false);
            cookies.flush();
        }

        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        web.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimeType, long contentLength) {
                startDownload(url, userAgent, contentDisposition, mimeType);
            }
        });
        web.setVisibility(View.INVISIBLE);

        root.addView(web, 0, matchFrame());
    }

    private final class Client extends WebViewClient {

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (uri == null) return false;
            String url = uri.toString();
            if (isSameOrigin(url)) return false;
            // Anything that is not plain http(s) belongs to another app
            // (mailto:, tel:, intent:, ...). Never navigate the shell into it.
            openExternal(uri);
            return true;
        }

        @Override
        @SuppressWarnings("deprecation")
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            if (isSameOrigin(url)) return false;
            openExternal(Uri.parse(url));
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            pageError = false;
            progress.setVisibility(View.VISIBLE);
            progress.setProgress(5);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            progress.setVisibility(View.GONE);
            if (!isSameOrigin(url) || pageError) return;
            // A failure page also finishes its load; never let it hide the
            // native screens that explain what to do next.
            if (state == STATE_AUTH || state == STATE_OFFLINE) return;
            showWeb();
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                        WebResourceResponse response) {
            if (request.isForMainFrame() && response.getStatusCode() == 401) {
                view.stopLoading();
                if (state == STATE_SPLASH) {
                    showAuth("登录失败：链接里的令牌已失效（DSH 重启后令牌会换新）。请重新复制浏览器地址栏的链接。");
                } else {
                    showAuth("登录已过期，请重新粘贴浏览器地址栏的完整链接。");
                }
            }
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            if (view != web) return true;
            root.removeView(view);
            view.destroy();
            web = null;
            String why = detail != null && detail.didCrash() ? "异常崩溃" : "被系统回收";
            showOffline("DSH 界面进程" + why + "。点“重试”重新加载。");
            return true;
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (!request.isForMainFrame()) return;
            pageError = true;
            showOffline("加载失败：" + error.getDescription() + "。请确认 Termux 里的 DSH 服务仍在运行。");
        }
    }

    private final class Chrome extends WebChromeClient {

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            if (progress.getVisibility() == View.VISIBLE) progress.setProgress(newProgress);
        }

        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                      Message resultMsg) {
            final WebView popup = new WebView(MainActivity.this);
            popup.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                    Uri uri = request.getUrl();
                    popup.destroy();
                    if (uri == null) return true;
                    if (isSameOrigin(uri.toString())) {
                        web.loadUrl(uri.toString());
                    } else {
                        openExternal(uri);
                    }
                    return true;
                }
            });
            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(popup);
            resultMsg.sendToTarget();
            return true;
        }

        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            boolean wantsAudio = false;
            for (String resource : request.getResources()) {
                if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                    wantsAudio = true;
                }
            }
            if (!wantsAudio) {
                request.deny();
                return;
            }
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                request.grant(request.getResources());
                return;
            }
            pendingMicRequest = request;
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                         FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = callback;
            try {
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                if (params != null && params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                startActivityForResult(Intent.createChooser(intent, "选择文件"), REQ_FILE);
                return true;
            } catch (ActivityNotFoundException e) {
                fileCallback = null;
                toast("没有可用的文件选择器");
                return false;
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE) return;
        if (fileCallback == null) return;
        Uri[] results = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int count = data.getClipData().getItemCount();
                ArrayList<Uri> uris = new ArrayList<>();
                for (int i = 0; i < count; i++) uris.add(data.getClipData().getItemAt(i).getUri());
                results = uris.toArray(new Uri[0]);
            } else if (data.getData() != null) {
                results = new Uri[]{data.getData()};
            }
        }
        fileCallback.onReceiveValue(results);
        fileCallback = null;
    }

    // ----------------------------------------------------------------- downloads

    private void startDownload(String url, String userAgent, String contentDisposition, String mimeType) {
        if (!isSameOrigin(url)) {
            openExternal(Uri.parse(url));
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            pendingDownload = Uri.parse(url);
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            return;
        }
        enqueueDownload(url, userAgent, contentDisposition, mimeType);
    }

    private void enqueueDownload(String url, String userAgent, String contentDisposition, String mimeType) {
        try {
            String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            String cookie = CookieManager.getInstance().getCookie(url);
            if (!TextUtils.isEmpty(cookie)) request.addRequestHeader("Cookie", cookie);
            if (!TextUtils.isEmpty(userAgent)) request.addRequestHeader("User-Agent", userAgent);
            if (!TextUtils.isEmpty(mimeType)) request.setMimeType(mimeType);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "DSH/" + fileName);
            DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            manager.enqueue(request);
            toast("开始下载：" + fileName);
        } catch (Exception e) {
            toast("下载失败：" + e.getMessage());
            openExternal(Uri.parse(url));
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_TERMUX_RUN) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                sendTermuxRunCommand();
            } else {
                toast("未授权 Termux 执行权限，请手动打开 Termux 运行 dsh-runner");
                openTermux();
            }
            return;
        }
        if (requestCode == REQ_MIC) {
            PermissionRequest pending = pendingMicRequest;
            pendingMicRequest = null;
            if (pending == null) return;
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            try {
                if (granted) pending.grant(pending.getResources());
                else pending.deny();
            } catch (Exception ignored) {
                // The page navigated away while the system dialog was open.
            }
            return;
        }
        if (requestCode != REQ_STORAGE) return;
        Uri pending = pendingDownload;
        pendingDownload = null;
        if (pending == null) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            enqueueDownload(pending.toString(), web != null ? web.getSettings().getUserAgentString() : null,
                    null, null);
        } else {
            toast("未授予存储权限，改为在浏览器中下载");
            openExternal(pending);
        }
    }

    // ------------------------------------------------------------------- misc

    /** Handle launcher shortcuts and dshpad://...?token= links. */
    private boolean handleIntent(Intent intent) {
        if (intent == null) return false;
        String action = intent.getAction();
        if (ACTION_RELOAD.equals(action)) {
            reloadNow();
            return true;
        }
        if (ACTION_BROWSER.equals(action)) {
            openExternal(Uri.parse(origin + "/"));
            return true;
        }
        if (ACTION_TERMUX.equals(action)) {
            openTermux();
            return true;
        }
        Uri data = intent.getData();
        if (data == null) return false;
        String token = data.getQueryParameter("token");
        if (token == null || token.isEmpty()) return false;
        submitToken(data.toString());
        return true;
    }

    private String readClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null) return null;
        ClipData clip = cm.getPrimaryClip();
        if (clip.getItemCount() == 0) return null;
        CharSequence text = clip.getItemAt(0).coerceToText(this);
        return text == null ? null : text.toString();
    }

    private void reloadNow() {
        if (web != null && state == STATE_WEB) {
            web.reload();
            toast("正在重新加载");
            return;
        }
        probeAndEnter();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applySystemBars();
        if (root != null) root.setBackgroundColor(color(R.color.window_bg));
        if (web != null) web.setBackgroundColor(color(R.color.window_bg));
        rebuildOverlays();
    }

    /** Re-create the native screens so values-night colours apply immediately. */
    private void rebuildOverlays() {
        if (root == null) return;
        for (View view : new View[]{splash, authView, offlineView}) {
            if (view != null && view.getParent() == root) root.removeView(view);
        }
        splash = buildSplash();
        authView = buildAuth();
        offlineView = buildOffline();
        root.addView(splash, matchFrame());
        root.addView(authView, matchFrame());
        root.addView(offlineView, matchFrame());
        if (state == STATE_SPLASH) {
            showOnly(splash);
        } else if (state == STATE_AUTH) {
            showOnly(authView);
        } else if (state == STATE_OFFLINE) {
            showOnly(offlineView);
        } else {
            splash.setVisibility(View.GONE);
            authView.setVisibility(View.GONE);
            offlineView.setVisibility(View.GONE);
        }
    }

    /**
     * Ask Termux to run dsh-start through its RUN_COMMAND service. This is the
     * only way a normal app can start the harness without adb; Termux has to
     * allow it (allow-external-apps=true) and grant the RUN_COMMAND
     * permission, so every failure path falls back to opening Termux.
     */
    private void requestTermuxStart() {
        if (checkSelfPermission(TERMUX_RUN_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            sendTermuxRunCommand();
            return;
        }
        try {
            requestPermissions(new String[]{TERMUX_RUN_PERMISSION}, REQ_TERMUX_RUN);
        } catch (Exception e) {
            toast("无法申请 Termux 执行权限，请手动打开 Termux");
            openTermux();
        }
    }

    private void sendTermuxRunCommand() {
        try {
            Intent intent = new Intent("com.termux.RUN_COMMAND");
            intent.setClassName("com.termux", "com.termux.app.RunCommandService");
            intent.putExtra("com.termux.RUN_COMMAND_PATH", TERMUX_BIN + "/bash");
            // Visible terminal session: the user sees the launcher output in
            // Termux instead of a silent background run, and the final `am start`
            // hands the foreground back to Dterm once the service is ready.
            String script = "printf '\\033[36m▸ Dterm：正在启动 DSH…\\033[0m\\n'; "
                    + "dsh-start; "
                    + "printf '\\033[1;32m▸ 已就绪，正在返回 Dterm…\\033[0m\\n'; "
                    + "am start -n " + getPackageName() + "/" + MainActivity.class.getName()
                    + " >/dev/null 2>&1";
            intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-lc", script});
            intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME);
            intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", false);
            intent.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", 2);
            intent.putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "启动 DSH");
            intent.putExtra("com.termux.RUN_COMMAND_COMMAND_DESCRIPTION",
                    "start the DSH web service on 127.0.0.1:3080");
            startService(intent);
            showOffline("已请 Termux 启动 DSH，正在等待服务就绪…");
            waitForService(150);
        } catch (Exception e) {
            toast("调用 Termux 失败：" + e.getMessage());
            openTermux();
        }
    }

    /**
     * Best-effort return to the foreground after Termux started the service.
     * Android 10+ only lets a background app do this when it holds the
     * "display over other apps" app-op, which the offline screen offers to grant.
     */
    private void returnToFront() {
        try {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(intent);
        } catch (Exception ignored) {
            // Blocked by the background-activity-start policy; the user comes back manually.
        }
    }

    private void openOverlaySettings(String pkg) {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + pkg));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            toast("打不开权限设置页，请手动去 设置 → 应用 → 显示在其它应用上层");
        }
    }

    /**
     * Poll the loopback port until DSH answers, then switch screens.
     *
     * On Android 10+ Termux may accept the RUN_COMMAND intent and still wait for
     * a foreground session before executing it, so if the port is not up after a
     * few seconds we surface Termux once: the queued command then runs and the
     * app is already polling when the user comes back.
     */
    private void waitForService(final int seconds) {
        if (waitingForService) return;
        waitingForService = true;
        final String base = origin;
        final String cookie = CookieManager.getInstance().getCookie(base + "/");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final long start = System.currentTimeMillis();
                final long deadline = start + seconds * 1000L;
                boolean surfacedTermux = false;
                int status = -1;
                while (System.currentTimeMillis() < deadline) {
                    status = probe(base, cookie);
                    if (status >= 0) break;
                    if (!surfacedTermux && System.currentTimeMillis() - start > 5000L) {
                        surfacedTermux = true;
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                if (state != STATE_OFFLINE) return;
                                toast("Termux 在后台没有执行，正在切过去触发一次");
                                openTermux();
                            }
                        });
                    }
                    try {
                        Thread.sleep(1500L);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                final int result = status;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        waitingForService = false;
                        if (isFinishing()) return;
                        if (result == 200) {
                            enterWeb(base);
                            if (!foreground) returnToFront();
                        } else if (result == 401) {
                            showAuth(null);
                        } else {
                            showOffline("等待 " + seconds + " 秒仍未就绪。请在 Termux 里确认：\n"
                                    + "① allow-external-apps=true ② 允许「显示在其它应用上层」，"
                                    + "然后手动运行 dsh-runner。");
                        }
                    }
                });
            }
        }, "dsh-wait").start();
    }

    private void openTermux() {
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage(TERMUX_PACKAGE);
            if (intent == null) {
                toast("未找到 Termux，请手动打开");
                return;
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            toast("无法打开 Termux：" + e.getMessage());
        }
    }

    private void openExternal(Uri uri) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            toast("没有应用可以打开这个链接");
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
