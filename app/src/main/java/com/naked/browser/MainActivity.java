package com.naked.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
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
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
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

    // Sub-resource tracker hosts (suffix-anchored, never substring matched).
    private static final String[] BLOCKED_HOSTS = {
            "doubleclick.net", "google-analytics.com", "googletagmanager.com",
            "scorecardresearch.com", "quantserve.com", "facebook.net"
    };
    private static final String PERF_TAG = "NakedPerf";
    private static final java.util.concurrent.atomic.AtomicInteger blockedRequests =
            new java.util.concurrent.atomic.AtomicInteger();

    // View Components
    private FrameLayout rootContainer;
    private LinearLayout topBar;
    private HorizontalScrollView tabScrollView;
    private LinearLayout tabStrip;
    private EditText urlInput;
    private ProgressBar progressBar;
    private FrameLayout webContainer;
    private ImageView snapshotView;
    private Button btnDesktop;

    // Tab Management
    public static class TabItem {
        // Null while the tab is hibernated: only metadata + savedState remain.
        public WebView webView;
        public Bundle savedState;
        public Bitmap snapshot;
        public long loadStartNs;
        public String title = "New Tab";
        public String currentUrl = "";
        public boolean isDesktop = false;
        public Button tabButton;

        public TabItem(String url) {
            this.currentUrl = url == null ? "" : url;
        }
    }

    private final List<TabItem> tabs = new ArrayList<>();
    private int activeIndex = -1;
    private final Deque<String> closedTabsHistory = new ArrayDeque<>();
    // While backgrounded under memory pressure the adjacent tab is not kept warm.
    private boolean lowMemory = false;

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

        // Overlay shown while a hibernated tab reloads behind it.
        snapshotView = new ImageView(this);
        snapshotView.setScaleType(ImageView.ScaleType.FIT_XY);
        snapshotView.setVisibility(View.GONE);
        webContainer.addView(snapshotView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

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
        TabItem item = new TabItem(TextUtils.isEmpty(url) ? DEFAULT_HOME : url);
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

        // WebView is created lazily by switchTab() -> materializeTab().
        switchTab(newIdx);
    }

    private void switchTab(int index) {
        if (index < 0 || index >= tabs.size()) return;

        // Clear the overlay before any snapshot can be recycled by hibernation.
        snapshotView.setVisibility(View.GONE);
        snapshotView.setImageBitmap(null);

        // Only the target tab and, normally, one adjacent tab keep a live WebView.
        // Under memory pressure no neighbour is kept warm.
        int neighbor = lowMemory ? -1 : neighborOf(index);
        for (int i = 0; i < tabs.size(); i++) {
            TabItem it = tabs.get(i);
            if (i == index) continue;
            if (it.tabButton != null) it.tabButton.setBackgroundColor(C_TAB_IDLE);
            if (it.webView != null) {
                if (i != neighbor) {
                    // Capture while the outgoing tab is still visible.
                    hibernateTab(it);
                } else {
                    // Capture while it is still visible, before hiding it.
                    captureSnapshot(it, it.webView);
                    // A live neighbour must not keep drawing a full-screen frame.
                    it.webView.setVisibility(View.GONE);
                }
            }
        }

        activeIndex = index;
        progressBar.setVisibility(View.GONE);
        progressBar.setProgress(0);
        TabItem current = tabs.get(index);
        if (current.tabButton != null) current.tabButton.setBackgroundColor(C_TAB_ACTIVE);

        materializeTab(current);
        if (current.snapshot != null) {
            showSnapshot(current.snapshot);
        }
        current.webView.setVisibility(View.VISIBLE);

        // Sync URL and desktop status
        urlInput.setText(current.currentUrl.isEmpty() ? current.webView.getUrl() : current.currentUrl);
        updateDesktopButton(current.isDesktop);

        tabScrollView.post(() -> tabScrollView.smoothScrollTo(current.tabButton.getLeft(), 0));
    }

    private void closeTab(int index) {
        if (index < 0 || index >= tabs.size()) return;

        int target;
        if (activeIndex == index) {
            target = -1;
        } else if (activeIndex > index) {
            target = activeIndex - 1;
        } else {
            target = activeIndex;
        }

        removeTabAt(index, true);

        if (tabs.isEmpty()) {
            createNewTab(DEFAULT_HOME);
        } else if (target < 0) {
            switchTab(Math.max(0, Math.min(index, tabs.size() - 1)));
        } else {
            switchTab(target);
        }
    }

    private void removeTabAt(int index, boolean remember) {
        TabItem item = tabs.get(index);
        if (remember && !TextUtils.isEmpty(item.currentUrl)) {
            closedTabsHistory.push(item.currentUrl);
        }
        WebView wv = item.webView;
        if (wv != null) {
            webContainer.removeView(wv);
            wv.stopLoading();
            wv.destroy();
            item.webView = null;
        }
        item.savedState = null;
        if (index == activeIndex) {
            snapshotView.setVisibility(View.GONE);
            snapshotView.setImageBitmap(null);
        }
        releaseSnapshot(item);
        tabStrip.removeView(item.tabButton);
        tabs.remove(index);
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
                            if (tabs.get(i) != target) removeTabAt(i, true);
                        }
                        switchTab(tabs.indexOf(target));
                    }
                })
                .show();
    }

    private boolean handleExternalUrl(String url) {
        if (url.startsWith("http://") || url.startsWith("https://")) return false;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {}
        return true;
    }

    private static boolean isBlocked(String host) {
        if (TextUtils.isEmpty(host)) return false;
        String h = host.toLowerCase(java.util.Locale.ROOT);
        for (String b : BLOCKED_HOSTS) {
            if (h.equals(b) || h.endsWith("." + b)) return true;
        }
        return false;
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

        wv.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleExternalUrl(url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                try {
                    return handleExternalUrl(request.getUrl().toString());
                } catch (Throwable ignored) {
                    return false;
                }
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                try {
                    if (!request.isForMainFrame() && isBlocked(request.getUrl().getHost())) {
                        int blocked = blockedRequests.incrementAndGet();
                        Log.d(PERF_TAG, "blocked=" + blocked + " " + request.getUrl());
                        return new WebResourceResponse("text/plain", "UTF-8",
                                new ByteArrayInputStream(new byte[0]));
                    }
                } catch (Throwable ignored) {
                    // Fail open: the blocker must never break navigation.
                }
                return super.shouldInterceptRequest(view, request);
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
                    hideSnapshot(view);
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
                if (idx != -1 && idx == activeIndex) {
                    progressBar.setProgress(newProgress);
                    if (newProgress >= 100) {
                        progressBar.setVisibility(View.GONE);
                        hideSnapshot(view);
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
            TabItem item = tabs.get(activeIndex);
            if (item.webView == null) {
                materializeTab(item);
                item.webView.setVisibility(View.VISIBLE);
            }
            return item.webView;
        }
        return null;
    }

    // =====================================================================
    //  WebView lifecycle — only the active tab and one neighbour stay alive
    // =====================================================================

    private int neighborOf(int index) {
        if (index + 1 < tabs.size()) return index + 1;
        if (index - 1 >= 0) return index - 1;
        return -1;
    }

    private void hibernateTab(TabItem item) {
        WebView wv = item.webView;
        if (wv == null) return;

        if (!TextUtils.isEmpty(wv.getUrl())) item.currentUrl = wv.getUrl();
        if (!TextUtils.isEmpty(wv.getTitle())) item.title = wv.getTitle();

        captureSnapshot(item, wv);

        Bundle state = new Bundle();
        item.savedState = wv.saveState(state) == null ? null : state;

        wv.stopLoading();
        webContainer.removeView(wv);
        wv.destroy();
        item.webView = null;
    }

    private void materializeTab(TabItem item) {
        if (item.webView != null) return;

        WebView wv = new WebView(this);
        configureWebView(wv);
        if (item.isDesktop) wv.getSettings().setUserAgentString(DESKTOP_UA);
        wv.setVisibility(View.GONE);
        webContainer.addView(wv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        item.webView = wv;
        showSnapshot(item.snapshot);
        item.loadStartNs = System.nanoTime();

        String url = TextUtils.isEmpty(item.currentUrl) ? DEFAULT_HOME : item.currentUrl;
        if (item.savedState != null) {
            boolean restored = wv.restoreState(item.savedState) != null;
            item.savedState = null;
            if (restored) {
                // restoreState restores history but not display data. Reload the
                // current entry instead of loadUrl to avoid a duplicate entry.
                wv.reload();
            } else {
                wv.loadUrl(url);
            }
        } else {
            wv.loadUrl(url);
        }
    }

    private void captureSnapshot(TabItem item, WebView wv) {
        // Keep the last valid capture; a re-capture from a hidden view is blank.
        if (item.snapshot != null && !item.snapshot.isRecycled()) return;
        int w = wv.getWidth();
        int h = wv.getHeight();
        if (w <= 0 || h <= 0) return;

        final float scale = 0.25f;
        Bitmap bmp = Bitmap.createBitmap(Math.max(1, Math.round(w * scale)),
                Math.max(1, Math.round(h * scale)), Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(bmp);
        canvas.scale(scale, scale);
        try {
            wv.draw(canvas);
        } catch (Throwable t) {
            bmp.recycle();
            return;
        }

        // A hardware-composited WebView can draw nothing; drop blank snapshots.
        int first = bmp.getPixel(0, 0);
        boolean uniform = true;
        for (int i = 1; i < 8 && uniform; i++) {
            int x = Math.min(bmp.getWidth() - 1, i * bmp.getWidth() / 8);
            int y = Math.min(bmp.getHeight() - 1, i * bmp.getHeight() / 8);
            if (bmp.getPixel(x, y) != first) uniform = false;
        }
        if (uniform) {
            bmp.recycle();
            return;
        }
        item.snapshot = bmp;
    }

    private void showSnapshot(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return;
        snapshotView.setImageBitmap(bitmap);
        snapshotView.bringToFront();
        snapshotView.setVisibility(View.VISIBLE);
    }

    private void hideSnapshot(WebView wv) {
        int idx = findTabByWebView(wv);
        if (idx < 0 || idx >= tabs.size()) return;
        TabItem item = tabs.get(idx);
        if (item.loadStartNs != 0) {
            Log.d(PERF_TAG, "load ms=" + (System.nanoTime() - item.loadStartNs) / 1000000L
                    + " url=" + item.currentUrl);
            item.loadStartNs = 0;
        }
        if (item.snapshot == null) return;
        if (idx == activeIndex) {
            snapshotView.setVisibility(View.GONE);
            snapshotView.setImageBitmap(null);
        }
        item.snapshot.recycle();
        item.snapshot = null;
    }

    private void releaseSnapshot(TabItem item) {
        if (item.snapshot != null) {
            item.snapshot.recycle();
            item.snapshot = null;
        }
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
        WebView wv = getActiveWebView();
        if (wv == null) return;
        TabItem item = tabs.get(activeIndex);
        item.isDesktop = !item.isDesktop;
        updateDesktopButton(item.isDesktop);

        WebSettings ws = wv.getSettings();
        if (item.isDesktop) {
            ws.setUserAgentString(DESKTOP_UA);
            toast("Desktop Mode ON");
        } else {
            ws.setUserAgentString(null);
            toast("Desktop Mode OFF");
        }
        wv.reload();
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
            if (it.webView != null) {
                it.webView.clearCache(true);
                it.webView.clearHistory();
            }
            // Dropping saved state also drops the history of a hibernated tab.
            it.savedState = null;
            releaseSnapshot(it);
        }
        snapshotView.setVisibility(View.GONE);
        snapshotView.setImageBitmap(null);
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
    protected void onPause() {
        for (TabItem it : tabs) {
            if (it.webView != null) it.webView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        lowMemory = false;
        for (TabItem it : tabs) {
            if (it.webView != null) it.webView.onResume();
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_RUNNING_LOW) hibernateInactiveTabs();
        // Only a background-level trim disables neighbour warming; a transient
        // foreground trim must not stick for the rest of the session.
        if (level >= TRIM_MEMORY_UI_HIDDEN) lowMemory = true;
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        lowMemory = true;
        hibernateInactiveTabs();
    }

    private void hibernateInactiveTabs() {
        for (int i = 0; i < tabs.size(); i++) {
            if (i != activeIndex) hibernateTab(tabs.get(i));
        }
    }

    @Override
    protected void onDestroy() {
        for (TabItem it : tabs) {
            WebView wv = it.webView;
            if (wv != null) {
                webContainer.removeView(wv);
                wv.stopLoading();
                wv.destroy();
                it.webView = null;
            }
            it.savedState = null;
            releaseSnapshot(it);
        }
        snapshotView.setVisibility(View.GONE);
        snapshotView.setImageBitmap(null);
        tabs.clear();
        super.onDestroy();
    }
}
