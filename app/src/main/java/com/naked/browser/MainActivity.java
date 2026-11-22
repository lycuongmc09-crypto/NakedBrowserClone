package com.naked.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Patterns;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Naked Browser — Ultra-lightweight, zero-bloat, privacy & speed oriented browser.
 * 100% Native Pure Java UI (No XML layouts, no heavy libraries).
 */
public class MainActivity extends Activity {

    // Palettes
    private static final int C_BG         = 0xFF121212;
    private static final int C_PANEL      = 0xFF1E1E1E;
    private static final int C_BAR        = 0xFF252526;
    private static final int C_TEXT       = 0xFFE0E0E0;
    private static final int C_TAB_ACTIVE = 0xFF374151;
    private static final int C_TAB_IDLE   = 0xFF1F2937;
    private static final int C_ACCENT     = 0xFF3B82F6;

    // Default Home / Search engine
    private static final String DEFAULT_HOME = "https://duckduckgo.com";
    private static final String SEARCH_URL   = "https://duckduckgo.com/?q=";

    // Desktop User Agent
    private static final String DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    // View Components
    private FrameLayout rootContainer;
    private LinearLayout topBar;
    private HorizontalScrollView tabScrollView;
    private LinearLayout tabStrip;
    private EditText urlInput;
    private ProgressBar progressBar;
    private FrameLayout webContainer;
    private Button btnDesktop;

    // Tab Management
    public static class TabItem {
        public final WebView webView;
        public String title = "New Tab";
        public String currentUrl = "";
        public boolean isDesktop = false;
        public Button tabButton;

        public TabItem(WebView webView) {
            this.webView = webView;
        }
    }

    private final List<TabItem> tabs = new ArrayList<>();
    private int activeIndex = -1;
    private final Deque<String> closedTabsHistory = new ArrayDeque<>();

    // Bookmarks Preference
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("naked_prefs", MODE_PRIVATE);

        buildPureUI();

        // Handle open link intent or load default home
        String targetUrl = DEFAULT_HOME;
        Intent intent = getIntent();
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            targetUrl = intent.getData().toString();
        }

        createNewTab(targetUrl);
    }

    private int dp(float v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private void buildPureUI() {
        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setBackgroundColor(C_BG);

        // 1. Top Panel (Tab Bar + URL Bar + Actions)
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.VERTICAL);
        topBar.setBackgroundColor(C_PANEL);

        // 1a. Tab strip row
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setGravity(Gravity.CENTER_VERTICAL);
        tabRow.setPadding(dp(4), dp(2), dp(4), dp(2));

        tabScrollView = new HorizontalScrollView(this);
        tabScrollView.setHorizontalScrollBarEnabled(false);
        tabStrip = new LinearLayout(this);
        tabStrip.setOrientation(LinearLayout.HORIZONTAL);
        tabScrollView.addView(tabStrip);

        tabRow.addView(tabScrollView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // + Button for new tab (Long-press to restore closed tab)
        Button btnNewTab = makeSmallButton("+");
        btnNewTab.setOnClickListener(v -> createNewTab(DEFAULT_HOME));
        btnNewTab.setOnLongClickListener(v -> {
            restoreLastClosedTab();
            return true;
        });
        tabRow.addView(btnNewTab);

        topBar.addView(tabRow);

        // 1b. Address & Navigation Row
        LinearLayout navRow = new LinearLayout(this);
        navRow.setOrientation(LinearLayout.HORIZONTAL);
        navRow.setGravity(Gravity.CENTER_VERTICAL);
        navRow.setPadding(dp(4), dp(2), dp(4), dp(2));

        Button btnBack = makeSmallButton("◀");
        btnBack.setOnClickListener(v -> {
            WebView wv = getActiveWebView();
            if (wv != null && wv.canGoBack()) wv.goBack();
        });
        navRow.addView(btnBack);

        Button btnForward = makeSmallButton("▶");
        btnForward.setOnClickListener(v -> {
            WebView wv = getActiveWebView();
            if (wv != null && wv.canGoForward()) wv.goForward();
        });
        navRow.addView(btnForward);

        // URL Input
        urlInput = new EditText(this);
        urlInput.setSingleLine(true);
        urlInput.setTextSize(13);
        urlInput.setTextColor(C_TEXT);
        urlInput.setBackgroundColor(C_BAR);
        urlInput.setPadding(dp(10), dp(6), dp(10), dp(6));
        urlInput.setImeOptions(EditorInfo.IME_ACTION_GO);
        urlInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO ||
                    (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                submitUrl(urlInput.getText().toString());
                hideKeyboard();
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams urlLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        urlLp.setMargins(dp(4), 0, dp(4), 0);
        navRow.addView(urlInput, urlLp);

        // Reload / Stop button
        Button btnReload = makeSmallButton("↻");
        btnReload.setOnClickListener(v -> {
            WebView wv = getActiveWebView();
            if (wv != null) wv.reload();
        });
        navRow.addView(btnReload);

        // Desktop Toggle
        btnDesktop = makeSmallButton("D");
        btnDesktop.setOnClickListener(v -> toggleDesktopMode());
        navRow.addView(btnDesktop);

        // Menu / Extra button
        Button btnMenu = makeSmallButton("⋮");
        btnMenu.setOnClickListener(v -> showMenuDialog());
        navRow.addView(btnMenu);

        topBar.addView(navRow);

        // 1c. Micro Progress Bar
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setVisibility(View.GONE);
        topBar.addView(progressBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(2)));

        mainLayout.addView(topBar);

        // 2. Web Container (Holds WebViews)
        webContainer = new FrameLayout(this);
        mainLayout.addView(webContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        rootContainer = new FrameLayout(this);
        rootContainer.addView(mainLayout);
        setContentView(rootContainer);
    }

    private Button makeSmallButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(C_TEXT);
        b.setTextSize(12);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackgroundColor(C_BAR);
        b.setPadding(dp(8), dp(4), dp(8), dp(4));
        b.setMinWidth(dp(32));
        b.setMinHeight(dp(32));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
        lp.setMargins(dp(2), 0, dp(2), 0);
        b.setLayoutParams(lp);
        return b;
    }

    // =====================================================================
    //  Tab Management
    // =====================================================================

    private void createNewTab(String url) {
        WebView wv = new WebView(this);
        configureWebView(wv);

        TabItem item = new TabItem(wv);
        tabs.add(item);
        int newIdx = tabs.size() - 1;

        // Create Tab Button
        Button tabBtn = new Button(this);
        tabBtn.setText("Tab " + (newIdx + 1));
        tabBtn.setTextColor(C_TEXT);
        tabBtn.setTextSize(11);
        tabBtn.setPadding(dp(10), dp(2), dp(10), dp(2));
        tabBtn.setAllCaps(false);
        tabBtn.setSingleLine(true);
        tabBtn.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(110), dp(32));
        lp.setMargins(dp(2), 0, dp(2), 0);
        tabBtn.setLayoutParams(lp);

        tabBtn.setOnClickListener(v -> switchTab(tabs.indexOf(item)));
        tabBtn.setOnLongClickListener(v -> {
            showTabOptionsDialog(tabs.indexOf(item));
            return true;
        });

        item.tabButton = tabBtn;
        tabStrip.addView(tabBtn);

        // Add WebView to container (hidden until switched)
        wv.setVisibility(View.GONE);
        webContainer.addView(wv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        switchTab(newIdx);

        if (!TextUtils.isEmpty(url)) {
            wv.loadUrl(url);
        }
    }

    private void switchTab(int index) {
        if (index < 0 || index >= tabs.size()) return;

        // Hide current
        if (activeIndex >= 0 && activeIndex < tabs.size()) {
            TabItem old = tabs.get(activeIndex);
            old.webView.setVisibility(View.GONE);
            old.tabButton.setBackgroundColor(C_TAB_IDLE);
        }

        activeIndex = index;
        TabItem current = tabs.get(index);
        current.webView.setVisibility(View.VISIBLE);
        current.tabButton.setBackgroundColor(C_TAB_ACTIVE);

        // Sync URL and desktop status
        urlInput.setText(current.currentUrl.isEmpty() ? current.webView.getUrl() : current.currentUrl);
        updateDesktopButton(current.isDesktop);

        tabScrollView.post(() -> tabScrollView.smoothScrollTo(current.tabButton.getLeft(), 0));
    }

    private void closeTab(int index) {
        if (index < 0 || index >= tabs.size()) return;
        TabItem item = tabs.get(index);

        if (!TextUtils.isEmpty(item.currentUrl)) {
            closedTabsHistory.push(item.currentUrl);
        }

        // Clean WebView
        webContainer.removeView(item.webView);
        item.webView.stopLoading();
        item.webView.destroy();

        tabStrip.removeView(item.tabButton);
        tabs.remove(index);

        if (tabs.isEmpty()) {
            createNewTab(DEFAULT_HOME);
        } else {
            int next = Math.max(0, index - 1);
            switchTab(next);
        }
    }

    private void restoreLastClosedTab() {
        if (!closedTabsHistory.isEmpty()) {
            String url = closedTabsHistory.pop();
            createNewTab(url);
            toast("Restored: " + url);
        } else {
            toast("No closed tabs to restore");
        }
    }

    private void showTabOptionsDialog(int index) {
        if (index < 0 || index >= tabs.size()) return;
        new AlertDialog.Builder(this)
                .setTitle(tabs.get(index).title)
                .setItems(new String[]{"Close Tab", "Duplicate Tab", "Close Other Tabs"}, (d, which) -> {
                    if (which == 0) {
                        closeTab(index);
                    } else if (which == 1) {
                        String u = tabs.get(index).currentUrl;
                        createNewTab(u.isEmpty() ? DEFAULT_HOME : u);
                    } else if (which == 2) {
                        TabItem target = tabs.get(index);
                        for (int i = tabs.size() - 1; i >= 0; i--) {
                            if (tabs.get(i) != target) closeTab(i);
                        }
                    }
                })
                .show();
    }

    // =====================================================================
    //  WebView Configuration
    // =====================================================================

    private void configureWebView(WebView wv) {
        WebSettings ws = wv.getSettings();

        // High performance & essentials
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
        ws.setAllowFileAccess(false); // Security
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);

        // Hardware accelerated layer
        wv.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        wv.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false;
                }
                // External protocol handling (tel, mailto, etc.)
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception ignored) {}
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                int idx = findTabByWebView(view);
                if (idx != -1) {
                    tabs.get(idx).currentUrl = url;
                    if (idx == activeIndex) {
                        urlInput.setText(url);
                        progressBar.setVisibility(View.VISIBLE);
                    }
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                int idx = findTabByWebView(view);
                if (idx != -1) {
                    tabs.get(idx).currentUrl = url;
                    if (idx == activeIndex) {
                        progressBar.setVisibility(View.GONE);
                    }
                }
            }
        });

        wv.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                int idx = findTabByWebView(view);
                if (idx == activeIndex) {
                    progressBar.setProgress(newProgress);
                    if (newProgress >= 100) {
                        progressBar.setVisibility(View.GONE);
                    } else {
                        progressBar.setVisibility(View.VISIBLE);
                    }
                }
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                int idx = findTabByWebView(view);
                if (idx != -1) {
                    TabItem it = tabs.get(idx);
                    it.title = title;
                    if (it.tabButton != null) {
                        it.tabButton.setText(title);
                    }
                }
            }
        });

        // Download Listener
        wv.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setData(Uri.parse(url));
                startActivity(i);
                toast("Opening download...");
            } catch (Exception e) {
                toast("Cannot start download: " + e.getMessage());
            }
        });
    }

    private int findTabByWebView(WebView wv) {
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i).webView == wv) return i;
        }
        return -1;
    }

    private WebView getActiveWebView() {
        if (activeIndex >= 0 && activeIndex < tabs.size()) {
            return tabs.get(activeIndex).webView;
        }
        return null;
    }

    // =====================================================================
    //  Navigation & Actions
    // =====================================================================

    private void submitUrl(String query) {
        if (TextUtils.isEmpty(query)) return;
        query = query.trim();

        String destination;
        if (Patterns.WEB_URL.matcher(query).matches() || query.startsWith("http://") || query.startsWith("https://") || query.contains(".")) {
            destination = (query.startsWith("http://") || query.startsWith("https://")) ? query : "https://" + query;
        } else {
            // Search Query
            destination = SEARCH_URL + Uri.encode(query);
        }

        WebView wv = getActiveWebView();
        if (wv != null) {
            wv.loadUrl(destination);
        }
    }

    private void toggleDesktopMode() {
        if (activeIndex < 0 || activeIndex >= tabs.size()) return;
        TabItem item = tabs.get(activeIndex);
        item.isDesktop = !item.isDesktop;
        updateDesktopButton(item.isDesktop);

        WebSettings ws = item.webView.getSettings();
        if (item.isDesktop) {
            ws.setUserAgentString(DESKTOP_UA);
            toast("Desktop Mode ON");
        } else {
            ws.setUserAgentString(null);
            toast("Desktop Mode OFF");
        }
        item.webView.reload();
    }

    private void updateDesktopButton(boolean isDesktop) {
        if (btnDesktop != null) {
            btnDesktop.setTextColor(isDesktop ? C_ACCENT : C_TEXT);
        }
    }

    private void showMenuDialog() {
        String[] options = new String[]{
                "⭐ Add Bookmark",
                "📖 Bookmarks",
                "🧹 Clear Cache & History",
                "🚪 Exit Browser"
        };

        new AlertDialog.Builder(this)
                .setTitle("Naked Browser")
                .setItems(options, (d, which) -> {
                    if (which == 0) addBookmark();
                    else if (which == 1) showBookmarks();
                    else if (which == 2) clearBrowserData(true);
                    else if (which == 3) gracefulExit();
                })
                .show();
    }

    private void addBookmark() {
        WebView wv = getActiveWebView();
        if (wv == null || TextUtils.isEmpty(wv.getUrl())) return;

        Set<String> set = prefs.getStringSet("bookmarks", new HashSet<>());
        Set<String> updated = new HashSet<>(set);
        updated.add(wv.getUrl());
        prefs.edit().putStringSet("bookmarks", updated).apply();
        toast("Saved bookmark");
    }

    private void showBookmarks() {
        Set<String> set = prefs.getStringSet("bookmarks", new HashSet<>());
        if (set.isEmpty()) {
            toast("No bookmarks yet");
            return;
        }
        final String[] arr = set.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("Bookmarks")
                .setItems(arr, (d, which) -> {
                    WebView wv = getActiveWebView();
                    if (wv != null) wv.loadUrl(arr[which]);
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void clearBrowserData(boolean showToast) {
        for (TabItem it : tabs) {
            it.webView.clearCache(true);
            it.webView.clearHistory();
        }
        CookieManager.getInstance().removeAllCookies(null);
        CookieManager.getInstance().flush();
        if (showToast) toast("Cache & Cookies cleared");
    }

    private void gracefulExit() {
        clearBrowserData(false);
        finishAffinity();
        System.exit(0);
    }

    private void hideKeyboard() {
        View v = getCurrentFocus();
        if (v != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onBackPressed() {
        WebView wv = getActiveWebView();
        if (wv != null && wv.canGoBack()) {
            wv.goBack();
        } else if (tabs.size() > 1) {
            closeTab(activeIndex);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        for (TabItem it : tabs) {
            it.webView.stopLoading();
            it.webView.destroy();
        }
        tabs.clear();
        super.onDestroy();
    }
}
