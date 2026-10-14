package moe.lovefirefly.betterzuxsidebar.Ui;

import android.animation.LayoutTransition;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.ClipDescription;
import android.content.BroadcastReceiver;
import android.content.ClipboardManager;
import android.content.IntentFilter;
import android.view.ContextThemeWrapper;
import android.content.Intent;
import android.app.PendingIntent;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.UserHandle;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.icu.text.Transliterator;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.TextKeyListener;
import android.util.TypedValue;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.Paint;
import android.graphics.Rect;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import moe.lovefirefly.betterzuxsidebar.Utils.LogHelper;

/**
 * 悬浮窗的两页 UI（纯 framework 控件）：
 *
 *  A 页（钉住页，仿原版 top5 窄条）：竖向排列钉住的应用 + 底部 "..." → 切到 B 页
 *  B 页（全部应用页）：搜索 + [冻结的钉住行(拖拽落点)] + 5 列网格 + 右侧字母索引条
 *
 * 切页不在这里做，交给 Host（MainHook 用厂商的 dismiss → show 复现"浮入浮出"）。
 * 数据/启动复用厂商实现，图标走桌面渲染链（LauncherIconLoader），索引条复用
 * 厂商的 zui.widget.AlphabeticIndexBar。
 */
public final class SidebarPanel {
    public interface Host {
        /** 仿原版：dismiss 后重新弹出"全部应用"页；delayed 对应悬停触发的 400ms 间隔。 */
        void switchToAllApps(boolean delayed);

        void launch(Object appInfo);

        void refreshPopupBackground();
    }

    /** 钉住个数上界，仅作为加载/存储的循环上界（不再限制为 5，超出会换行显示）。 */
    private static final int PINNED_MAX = 200;

    private static final int COLUMNS = 5;
    private static final int PINNED_PAGE_WIDTH_DP = 84;
    /** 5×55dp 图标 + 24dp×2 padding + 索引条。放宽到 460dp，让每列更宽，
     *  应用名不容易被截成 "..."（列宽 ~82dp，图标与列边之间也自然多出一圈空隙）。 */
    private static final int ALL_APPS_WIDTH_DP = 460;
    /** 原版 all_grid_window_height=463dp，这里放大到 ≈1.2 倍，配合放大后的图标与折行文字。 */
    private static final int ALL_APPS_MAX_HEIGHT_DP = 556;
    /** 钉住行与网格统一用这个尺寸，5 列上下对齐。图标走桌面渲染链（自适应遮罩会向内裁掉
     *  一圈，视觉上偏小），所以显示尺寸再放大一点补偿。 */
    private static final int GRID_ICON_DP = 74;
    /** A 页窄条（仿原版 top5）的图标尺寸，同样放大一档。 */
    private static final int PINNED_PAGE_ICON_DP = 66;
    private static final int GRID_PADDING_DP = 24;
    private static final String OTHER_SECTION = "#";
    /** AlphabeticIndexBar 的字母表固定是 A-Z + #（来自其 style 的 android:entries）。 */
    private static final int INDEX_BAR_SLOTS = 27;
    private static final String PREFS_NAME = "betterzux_pinned";
    private static final String PREF_FOLLOW_SYSTEM_THEME = "follow_system_theme";
    private static final String PREF_SHORTCUT_BAR_TOP5 = "shortcut_bar_top5";
    private static final String PREF_OPEN_ALL_APPS_DIRECTLY = "open_all_apps_directly";
    private static final String PIN_KEY_PREFIX = "pin_";
    /** 面板圆角（与 vendor popup_background_zui 的边界设计一致）；搜索框也用这个。 */
    private static final int PANEL_CORNER_DP = 16;

    private static final class Row {
        final String section;
        /** 该行是其字母组的第一行（用于组内/组间行距）。 */
        final boolean firstOfSection;
        /** 非 null 表示这是"字母标题行"（该行没有 apps，只显示一个字母）。 */
        final String header;
        final List<Object> apps = new ArrayList<>(COLUMNS);

        Row(String section, boolean firstOfSection) {
            this(section, firstOfSection, null);
        }

        Row(String section, boolean firstOfSection, String header) {
            this.section = section;
            this.firstOfSection = firstOfSection;
            this.header = header;
        }
    }

    private final Context ctx;
    private final ClassLoader targetCl;
    private final Host host;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private Runnable refreshCommit;
    private void commitRefresh() {
        LauncherIconLoader.clear(ctx);
        loadApps(false);
        preloadIcons();
    }

    private final List<Object> pinnedApps = new ArrayList<>();
    private final List<String> pinnedKeys = new ArrayList<>();
    private final List<Object> allApps = new ArrayList<>();
    private final Map<String, Object> appByKey = new HashMap<>();
    private final Map<Object, String> appNameCache = new IdentityHashMap<>();
    private final Map<Object, String> appPkgCache = new IdentityHashMap<>();
    private final Map<Object, String> appClassCache = new IdentityHashMap<>();
    private final List<Row> rows = new ArrayList<>();
    private final List<String> sections = new ArrayList<>();
    private final Map<String, Integer> sectionFirstRow = new HashMap<>();

    private Method mGenerateLauncherApp;
    private Method mRefreshLauncherApp;
    private Method mRefreshLauncherAppIcon;
    private Method mIsBadgedAppChanged;
    private Field mSwitchChangedFlag;
    private Method mGetAppName;
    private Method mGetPkgName;
    private Method mGetClassName;
    private Method mGetAppIcon;
    private Method mGetUser;

    private LinearLayout pinnedStrip;   // A 页：竖向
    private LinearLayout pinnedBar;     // B 页：冻结的横向行（拖拽落点）
    private View pinnedPage;
    private View allAppsPage;
    private ImageView moreButton;
    private ImageButton menuButton;
    private PopupMenu searchMenu;
    private ListView listView;
    private EditText searchField;
    private RowAdapter adapter;
    /** 列表正在被手指按住/拖动时为 true；用于避免在手势中途刷新列表导致触摸丢失、按压态卡死。 */
    private volatile boolean listTouching;
    /** 钉住行拖拽来源：>=0 = 行内第几格（移动），-1 = 来自网格（新增）。 */
    private int dragFromPinned = -1;
    /** 被拿起的钉住格（拖动期间置 alpha=0 隐藏，松手恢复）。 */
    private View pinnedDragCell;
    /** A 页或 B 页网格的窗口外拖拽源；外拖期间不允许 pinned 区删除/插入。 */
    private View externalDragCell;
    private volatile boolean externalDragInProgress;
    /** 当前全局拖拽的应用包名；拖出窗口后由 SystemUI SplitScreenProvider 接管。 */
    private String draggedPackageForSplit;
    private boolean splitRequestedForDrag;
    /** 是否有拖拽正在进行（网格→钉住区 或 钉住区内移动）。用于挡住后台刷新。 */
    private volatile boolean dragInProgress;
    /** B 页钉住网格里的所有等宽格子（按线性顺序展平），用于拖拽计算与动画。 */
    private final List<FrameLayout> pinnedSlots = new ArrayList<>();
    /** 钉住行拖拽预览状态（当前的插入位），-1 = 无预览。 */
    private int pinnedPreviewState = -1;
    /** 预览"新开一行"的高度动画（行数扩展动画）与当前额外行高。 */
    private android.animation.ValueAnimator rowGrowAnimator;
    private float extraRowH;
    /** 行数收起动画进行中（禁止后台刷新重建）。 */
    private volatile boolean pinnedAnimating;
    /** 挤压/交换动画照抄 zui.launcher：时长 230ms，级联起播延迟 0/30/57…（步长 ×0.9）。 */
    private static final long REORDER_ANIM_MS = 230L;
    private static final float REORDER_DELAY_STEP_MS = 30f;
    /** 面板刚显示、还没成功把焦点交给搜索框（窗口可能尚未获焦），需重试。 */
    private volatile boolean pendingSearchFocus;
    /**
     * 键盘导航模式（搜索框按 Enter 后进入）。
     * 注意：**view 焦点始终留在搜索框**，高亮只是一个 selected 光标，
     * 所以任何时候打字都是直接进输入框（无需再抢焦点），方向键才由容器拦截。
     */
    private boolean kbNav;
    /** 导航目标在顶部钉住行（没有搜索结果时）。 */
    private boolean kbNavPinned;
    /** 导航目标在网格中的位置：行（rows 下标）+ 列。 */
    private int kbRow = -1;
    private int kbCol;
    /** 导航目标在钉住行里的扁平下标。 */
    private int kbIndex;
    /** 自定义索引条，绕过 vendor 的 BadTokenException bug。# 在最前，触摸无异常。 */
    private IndexBarView indexBar;
    private FrameLayout indexBarHost;
    private Transliterator pinyin;
    private BroadcastReceiver themeReceiver;
    private volatile boolean themeRefreshPending = true;
    private boolean allAppsEditMode;
    private ImageButton editModeButton;

    public SidebarPanel(Context ctx, ClassLoader targetCl, Host host) {
        this.ctx = ctx;
        this.targetCl = targetCl;
        this.host = host;
        this.refreshCommit = this::commitRefresh;
        android.util.Log.e("BetterZUXSidebar", "PANEL_CONSTRUCTOR_DIRECT pid="
            + android.os.Process.myPid() + " ctxPackage=" + ctx.getPackageName());
        resolveVendorApi();
        registerThemeReceiver();
        LauncherIconLoader.setFollowSystemTheme(prefs().getBoolean(PREF_FOLLOW_SYSTEM_THEME, true));
        // 用较大的格图标尺寸渲染，55dp(A 页) 与 62dp(网格/钉住行) 都能清晰显示
        LauncherIconLoader.init(ctx, dp(GRID_ICON_DP));
    }

    // ---------------------------------------------------------------- 页面

    /** A 页：竖向钉住条 + "..." */
    public View getPinnedPage() {
        if (pinnedPage == null) {
            LinearLayout root = new LinearLayout(ctx);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER_HORIZONTAL);
            root.setBackground(panelBackground());
            root.setFocusable(true);
            root.setFocusableInTouchMode(true);
            // 四周留一圈边距，避免图标贴着面板圆角边框
            root.setPadding(dp(6), dp(8), dp(6), dp(8));
            root.setLayoutParams(new FrameLayout.LayoutParams(
                    dp(PINNED_PAGE_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT));

            pinnedStrip = new LinearLayout(ctx);
            pinnedStrip.setOrientation(LinearLayout.VERTICAL);
            pinnedStrip.setGravity(Gravity.CENTER_HORIZONTAL);
            pinnedStrip.setOnDragListener((view, event) -> {
                switch (event.getAction()) {
                    case DragEvent.ACTION_DRAG_STARTED:
                        return true;
                    case DragEvent.ACTION_DRAG_LOCATION:
                        return true;
                    case DragEvent.ACTION_DROP:
                        // 第一页不排序；接收内部落点即可阻止外部小窗接管。
                        return true;
                    case DragEvent.ACTION_DRAG_ENDED:
                        externalDragInProgress = false;
                        if (externalDragCell != null) {
                            externalDragCell.setAlpha(1f);
                            externalDragCell = null;
                        }
                        return true;
                    default:
                        return true;
                }
            });

            // 上面是滚动区：视口最多 6 个 app 高；pinned 不足 6 个就相应变矮
            MaxHeightScrollView scroll = new MaxHeightScrollView(ctx);
            scroll.setVerticalScrollBarEnabled(false);
            scroll.setClipToPadding(false);
            scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
            scroll.setMaxHeight(dp(6 * (PINNED_PAGE_ICON_DP + 6)));
            scroll.addView(pinnedStrip, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            root.addView(scroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            root.addView(buildDivider());
            root.addView(buildMoreButton(PINNED_PAGE_ICON_DP));
            pinnedPage = root;
        }
        return pinnedPage;
    }

    /** B 页：搜索 + 冻结钉住行（拖拽落点）+ 网格 + 索引条 */
    public View getAllAppsPage() {
        if (allAppsPage == null) {
            AllAppsRoot root = new AllAppsRoot(ctx);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackground(panelBackground());
            root.setFocusable(true);
            root.setFocusableInTouchMode(true);
            root.setLayoutParams(new FrameLayout.LayoutParams(
                    dp(ALL_APPS_WIDTH_DP), panelHeightPx()));

            root.addView(buildSearchRow());

            // 钉住行和网格放在同一个宽度容器里（索引条占的宽度也一样），
            // 这样两边的 5 列宽度完全一致、上下对齐。
            LinearLayout column = new LinearLayout(ctx);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setClipChildren(false);   // 钉住区换行/展开时不要裁掉子视图
            column.addView(buildPinnedBar());
            column.addView(buildDivider());   // 钉住区与下面列表之间的分割线

            // 列表行：网格 + 索引条。索引条与列表区同高（MATCH_PARENT），
            // 即顶部、底部对齐 allapp 的 box，而不是整个小窗。
            LinearLayout listRow = new LinearLayout(ctx);
            listRow.setOrientation(LinearLayout.HORIZONTAL);

            listView = new ListView(ctx);
            listView.setDivider(null);
            listView.setDividerHeight(0);
            listView.setVerticalScrollBarEnabled(false);
            // 关掉 ListView 默认的 item 选中高亮：按下时会整行（一行 5 个格子的“组”）铺一层高亮，
            // 视觉上就是一大片方块动画，很诡异。我们只要格子自身的反馈，不要行级高亮。
            listView.setSelector(new ColorDrawable(0x00000000));
            listView.setDrawSelectorOnTop(false);
            listView.setPadding(dp(GRID_PADDING_DP), 0, 0, dp(12));
            listView.setClipToPadding(false);
            // 记录手指是否正按在列表上：后台图标预加载完成后要刷新时，必须避开手势进行中，
            // 否则 ListView 会在按住的过程中重建行、移除被按住的子视图 → 触摸丢失、按压态卡在 down。
            listView.setOnTouchListener((v, ev) -> {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                    case MotionEvent.ACTION_MOVE:
                        listTouching = true;
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        listTouching = false;
                        break;
                    default:
                        break;
                }
                return false;
            });
            listView.setOnScrollListener(new AbsListView.OnScrollListener() {
                @Override
                public void onScrollStateChanged(AbsListView view, int scrollState) {
                }

                @Override
                public void onScroll(AbsListView view, int firstVisible, int visibleCount, int total) {
                    syncIndexBarFocus(firstVisible);
                }
            });
            listRow.addView(listView, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

            buildIndexBar();
            if (indexBarHost != null) {
                listRow.addView(indexBarHost, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            }

            listRow.setOnDragListener((view, event) -> {
                if (!allAppsEditMode || externalDragInProgress || dragFromPinned < 0) {
                    return false;
                }
                switch (event.getAction()) {
                    case DragEvent.ACTION_DRAG_STARTED:
                    case DragEvent.ACTION_DRAG_LOCATION:
                        previewPinnedDelete();
                        return true;
                    case DragEvent.ACTION_DROP:
                        int deleteIndex = dragFromPinned;
                        commitPinnedDelete(deleteIndex);
                        pinnedPreviewState = -1;
                        dragFromPinned = -1;
                        return true;
                    case DragEvent.ACTION_DRAG_ENDED:
                        if (event.getResult()) {
                            return true;
                        }
                        clearPinnedPreview(true);
                        return true;
                    default:
                        return true;
                }
            });

            column.addView(listRow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            root.addView(column, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            adapter = new RowAdapter();
            listView.setAdapter(adapter);
            allAppsPage = root;
        }
        return allAppsPage;
    }

    public boolean shouldOpenAllAppsDirectly() {
        return prefs().getBoolean(PREF_OPEN_ALL_APPS_DIRECTLY, false);
    }

    /**
     * 每次显示 B 页时把焦点直接交给搜索框。
     * 这样 ImeFocusController 会立即把搜索框标成输入目标，按键从**第一次**开始就先过 IME，
     * 不会出现“第一个字符直出”（那是焦点刚转移、IME 还没接手）；退格/选择/复制/输入法
     * 也全部是 EditText 自己的原生行为。requestFocus 不会主动弹软键盘。
     */
    public void focusSearchField() {
        if (searchField == null) {
            return;
        }
        pendingSearchFocus = true;
        searchField.post(this::tryFocusSearchField);
    }

    /**
     * 把焦点交给搜索框。弹窗刚 show 时窗口还没拿到焦点，requestFocus() 会被忽略，
     * 所以没成功就留着 pending，由 AllAppsRoot.onWindowFocusChanged() 再补一次。
     */
    private void tryFocusSearchField() {
        if (!pendingSearchFocus || searchField == null || searchField.hasFocus()) {
            pendingSearchFocus = false;
            return;
        }
        if (searchField.requestFocus()) {
            pendingSearchFocus = false;
        }
    }

    // ---------------------------------------------------------------- 键盘导航

    /**
     * 搜索框按 Enter 的入口：有搜索结果就高亮第一个结果格子，没搜到就高亮顶部钉住行
     * 的第一个 app，然后进入键盘导航模式（方向键在格子间移动高亮）。
     */
    private void enterKeyboardNav() {
        int[] first = firstResultCell();
        if (first != null) {
            kbNavPinned = false;
            kbNav = true;
            kbRow = first[0];
            kbCol = first[1];
            listView.setSelection(kbRow);
            applyKbHighlight();
            return;
        }
        if (!pinnedApps.isEmpty()) {
            kbNavPinned = true;
            kbNav = true;
            kbIndex = 0;
            applyKbHighlight();
        }
    }

    /** 退出键盘导航并清掉高亮。焦点不动（本来就在搜索框）。 */
    private void exitKeyboardNav() {
        if (!kbNav) {
            return;
        }
        kbNav = false;
        kbRow = -1;
        kbCol = 0;
        kbIndex = 0;
        applyKbHighlight();
    }

    /** 第一个有内容的网格格子的 (行, 列)；一个结果都没有就返回 null。 */
    private int[] firstResultCell() {
        for (int r = 0; r < rows.size(); r++) {
            Row row = rows.get(r);
            if (row.header == null && !row.apps.isEmpty()) {
                return new int[]{r, 0};
            }
        }
        return null;
    }

    /**
     * 方向键移动高亮。
     *
     * 钉住行是横向铺开的：上下按整行（COLUMNS）步进；向下越过钉住行底部就"穿越"到
     * 网格顶部同一列。网格里上下逐行（跳过字母标题行），向上越过网格顶部就穿越回
     * 钉住行里"同一列的最后一个 app"。
     * 例如钉住 a b c d e / f，网格首行 v w x y z：v↑=f，w↑=b、x↑=c、y↑=d、z↑=e；
     * 钉住只有一个 a 时，w x y z ↑ 都安全落到 a。任一侧为空则不穿越。
     */
    private void moveKbNav(int dx, int dy) {
        if (kbNavPinned) {
            int total = pinnedApps.size();
            if (total <= 0) {
                return;
            }
            if (dx != 0) {
                int nextIndex = kbIndex + dx;
                if (nextIndex >= 0 && nextIndex < total) {
                    kbIndex = nextIndex;
                    applyKbHighlight();
                }
                return;
            }
            if (dy < 0) {
                int prev = kbIndex - COLUMNS;
                if (prev >= 0) {
                    kbIndex = prev;
                    applyKbHighlight();
                } else {
                    // pinned 第一行向上回到搜索框，保留原文本与光标位置。
                    kbNav = false;
                    kbNavPinned = false;
                    applyKbHighlight();
                    if (searchField != null) {
                        searchField.requestFocus();
                        int end = searchField.getText().length();
                        searchField.setSelection(end);
                    }
                }
                return;
            }
            int nextRow = kbIndex + COLUMNS;
            if (nextRow < total) {
                kbIndex = nextRow;
                applyKbHighlight();
                return;
            }
            // 向下越过钉住行底部 → 穿越到网格顶部同一列。
            // 列号必须由 pinned 的当前位置反推（kbIndex % COLUMNS）：
            // kbCol 停留在"进 pinned 时那一列"，在 pinned 里移动时并没有跟着更新。
            int[] first = firstResultCell();
            if (first == null) {
                return;     // 网格没有结果，不穿越
            }
            kbNavPinned = false;
            kbRow = first[0];
            kbCol = Math.min(kbIndex % COLUMNS, rows.get(first[0]).apps.size() - 1);
            listView.setSelection(kbRow);
            applyKbHighlight();
            return;
        }

        if (dy != 0) {
            int r = kbRow;
            do {
                r += dy;
            } while (r >= 0 && r < rows.size() && rows.get(r).header != null);
            if (r < 0 || r >= rows.size()) {
                if (dy < 0 && !pinnedApps.isEmpty()) {
                    // 向上越过网格顶部 → 穿越到钉住行里同一列的最后一个 app；
                    // 该列在钉住行里不存在（比如钉住只有一个 a）就落到最后一个，保证安全
                    int target = lastPinnedIndexOfColumn(kbCol);
                    if (target < 0) {
                        target = pinnedApps.size() - 1;
                    }
                    kbNavPinned = true;
                    kbIndex = target;
                    applyKbHighlight();
                }
                return;
            }
            kbRow = r;
            kbCol = Math.min(kbCol, rows.get(r).apps.size() - 1);
            listView.setSelection(kbRow);
            applyKbHighlight();
        } else if (dx != 0) {
            if (kbRow < 0 || kbRow >= rows.size()) {
                return;
            }
            int next = kbCol + dx;
            if (next < 0 || next >= rows.get(kbRow).apps.size()) {
                return;
            }
            kbCol = next;
            applyKbHighlight();
        }
    }

    /** 钉住行中第 col 列的最后一个 app 下标（没有该列则返回 -1）。 */
    private int lastPinnedIndexOfColumn(int col) {
        int result = -1;
        for (int i = col; i < pinnedApps.size(); i += COLUMNS) {
            result = i;
        }
        return result;
    }

    /**
     * 把 selected 高亮应用到当前可见的格子上。
     * ListView 的 item 是复用的、不能长期持有引用，所以每次按坐标重新扫一遍。
     */
    private void applyKbHighlight() {
        for (int i = 0; i < pinnedSlots.size(); i++) {
            FrameLayout slot = pinnedSlots.get(i);
            if (slot.getChildCount() > 0) {
                slot.getChildAt(0).setSelected(kbNav && kbNavPinned && i == kbIndex);
            }
        }
        for (int i = 0; i < listView.getChildCount(); i++) {
            View item = listView.getChildAt(i);
            if (!(item instanceof LinearLayout)) {
                continue;   // 字母标题行
            }
            LinearLayout rowView = (LinearLayout) item;
            int position = listView.getFirstVisiblePosition() + i;
            for (int c = 0; c < rowView.getChildCount(); c++) {
                View slot = rowView.getChildAt(c);
                if (!(slot instanceof FrameLayout) || ((FrameLayout) slot).getChildCount() == 0) {
                    continue;
                }
                ((FrameLayout) slot).getChildAt(0).setSelected(
                        kbNav && !kbNavPinned && position == kbRow && c == kbCol);
            }
        }
    }

    /**
     * B 页根布局，照抄桌面程序抽屉的做法：
     * `ActivityAllAppsContainerView.dispatchKeyEvent()` → `SearchUiManager.preDispatchKeyEvent()`
     * → `AppsSearchContainerLayout.preDispatchKeyEvent()`。
     *
     * 在按键分发给 UI 树**之前**，先用 TextKeyListener 试打一个字，只有“确实能产出
     * 可打印字符”的按键才会把焦点交给搜索框；之后这次按键仍不消费、继续正常分发，
     * 由 EditText + 输入法自己处理。Ctrl/Alt 组合、退格、方向键、Esc、音量键在
     * TextKeyListener 那里就过不了，所以选中/复制、退格、输入法全部保持原生行为。
     */
    private final class AllAppsRoot extends LinearLayout {

        /** 只用来试探“这个键能不能打出字”，内容用完即弃。 */
        private final SpannableStringBuilder probe = new SpannableStringBuilder();

        AllAppsRoot(Context context) {
            super(context);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            if (handleShortcut(event)) {
                return true;
            }
            if (handleNavKey(event)) {
                return true;
            }
            if (searchField != null && !searchField.hasFocus()) {
                preDispatchKeyEvent(event);
            }
            return super.dispatchKeyEvent(event);
        }

        /**
         * 键盘导航模式下的方向键/打字处理。
         * 方向键由这里吃掉（否则会被输入框拿去移动光标）；
         * 一旦打字或退格就退出导航、把事件放回去给搜索框（内容**保留**，方便继续筛选）。
         */
        private boolean handleNavKey(KeyEvent event) {
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return false;
            }
            if (kbNav && (event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    || event.getKeyCode() == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                launchKeyboardSelection();
                return true;
            }
            // 搜索框光标在末尾时，下键进入 pinned 第一行；空文本的选择位置也是 0，
            // 因而自然满足这个条件。光标在文本中间时保留 EditText 原生的下键行为。
            if (!kbNav && searchField != null && searchField.hasFocus()
                    && event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN
                    && isSearchCursorAtEnd() && !pinnedApps.isEmpty()) {
                kbNav = true;
                kbNavPinned = true;
                kbIndex = 0;
                applyKbHighlight();
                return true;
            }
            if (!kbNav) {
                return false;
            }
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    moveKbNav(0, -1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    moveKbNav(0, 1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    moveKbNav(-1, 0);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    moveKbNav(1, 0);
                    return true;
                default:
                    break;
            }
            int keyCode = event.getKeyCode();
            if (isTypingKey(event) || keyCode == KeyEvent.KEYCODE_DEL
                    || keyCode == KeyEvent.KEYCODE_FORWARD_DEL) {
                exitKeyboardNav();
            }
            return false;
        }

        /** 启动当前键盘高亮的应用；启动逻辑继续走宿主 FloatService。 */
        private void launchKeyboardSelection() {
            Object app = null;
            if (kbNavPinned) {
                if (kbIndex >= 0 && kbIndex < pinnedApps.size()) {
                    app = pinnedApps.get(kbIndex);
                }
            } else if (kbRow >= 0 && kbRow < rows.size()) {
                Row row = rows.get(kbRow);
                if (row.header == null && kbCol >= 0 && kbCol < row.apps.size()) {
                    app = row.apps.get(kbCol);
                }
            }
            if (app != null) {
                exitKeyboardNav();
                host.launch(app);
            }
        }

        private boolean isSearchCursorAtEnd() {
            Editable text = searchField.getText();
            return searchField.getSelectionStart() == text.length()
                    && searchField.getSelectionEnd() == text.length();
        }

        /** 是否为能打出可打印字符的按键（排除 Ctrl/Alt 组合与空白）。 */
        private boolean isTypingKey(KeyEvent event) {
            if (event.isCtrlPressed() || event.isAltPressed() || event.isMetaPressed()) {
                return false;
            }
            int c = event.getUnicodeChar();
            return c > 0 && !Character.isWhitespace(c) && !Character.isSpaceChar(c);
        }

        /**
         * Ctrl+A/C/X/V/Z/Y 等编辑快捷键，直接映射到 EditText 的文本菜单项。
         * 常规路径是 TextView.onKeyShortcut()（由 dispatchKeyShortcutEvent 过来），
         * 但在这种 PopupWindow 里快捷键事件不保证能走到 View 层级，所以这里显式兜一手
         * （AOSP 的 TextView.onKeyShortcut 内部也是调 onTextContextMenuItem()）。
         * 从 dispatchKeyEvent 走即可：若系统已经走过 shortcut 路径并消费，这里根本收不到，
         * 不会重复执行。
         */
        private boolean handleShortcut(KeyEvent event) {
            if (searchField == null || event.getAction() != KeyEvent.ACTION_DOWN
                    || !event.isCtrlPressed() || event.isAltPressed()) {
                return false;
            }
            int itemId;
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_A:
                    itemId = android.R.id.selectAll;
                    break;
                case KeyEvent.KEYCODE_C:
                    itemId = android.R.id.copy;
                    break;
                case KeyEvent.KEYCODE_X:
                    itemId = android.R.id.cut;
                    break;
                case KeyEvent.KEYCODE_V:
                    itemId = android.R.id.paste;
                    break;
                case KeyEvent.KEYCODE_Z:
                    itemId = android.R.id.undo;
                    break;
                case KeyEvent.KEYCODE_Y:
                    itemId = android.R.id.redo;
                    break;
                default:
                    return false;
            }
            if (!searchField.hasFocus()) {
                searchField.requestFocus();
            }
            return searchField.onTextContextMenuItem(itemId);
        }

        @Override
        public void onWindowFocusChanged(boolean hasWindowFocus) {
            super.onWindowFocusChanged(hasWindowFocus);
            if (hasWindowFocus) {
                // 窗口刚获得焦点时补上那次没成功的搜索框聚焦（面板第一次滑出时会用到）
                tryFocusSearchField();
            }
        }

        private void preDispatchKeyEvent(KeyEvent event) {
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return;
            }
            int unicode = event.getUnicodeChar();
            if (unicode <= 0 || Character.isWhitespace(unicode)
                    || Character.isSpaceChar(unicode)) {
                return;
            }
            probe.clear();
            if (TextKeyListener.getInstance().onKeyDown(this, probe, event.getKeyCode(), event)
                    && probe.length() > 0) {
                // 是“开始打字”：把焦点交给搜索框（requestFocus 同步更新 mFocused，
                // 紧接着的 super.dispatchKeyEvent 就会把这次按键分给搜索框）。
                searchField.requestFocus();
            }
        }
    }

    // ---------------------------------------------------------------- 构建细节

    private View buildMoreButton(int iconDp) {
        ImageView more = new ImageView(ctx);
        moreButton = more;
        int id = ctx.getResources().getIdentifier("ic_more_menu", "drawable", ctx.getPackageName());
        if (id != 0) {
            more.setImageDrawable(ctx.getResources().getDrawable(id));
        } else {
            // 兜底：用文字画一个 "..."
            more.setImageDrawable(null);
        }
        more.setScaleType(ImageView.ScaleType.CENTER);
        more.setBackground(pressedBackground(dp(10)));
        int size = dp(iconDp);
        LinearLayout.LayoutParams moreParams = new LinearLayout.LayoutParams(size, size);
        moreParams.gravity = Gravity.CENTER_HORIZONTAL;
        moreParams.topMargin = dp(2);
        moreParams.bottomMargin = dp(2);
        more.setLayoutParams(moreParams);
        // 仿原版：点击"..." 立即切页（原版 footview 点击 → post 无延迟）
        more.setOnClickListener(v -> host.switchToAllApps(false));
        // 仿原版：悬停"..." 400ms 后切页
        final Runnable hoverSwitch = () -> host.switchToAllApps(true);
        more.setOnHoverListener((v, event) -> {
            int action = event.getAction();
            if (action == android.view.MotionEvent.ACTION_HOVER_ENTER) {
                mainHandler.postDelayed(hoverSwitch, 400L);
            } else if (action == android.view.MotionEvent.ACTION_HOVER_EXIT) {
                mainHandler.removeCallbacks(hoverSwitch);
            }
            return false;
        });
        return more;
    }

    /** 按名字找一个 vendor/appcompat 的 drawable，找不到返回 null。 */
    private Drawable findDrawable(String... names) {
        for (String name : names) {
            int id = ctx.getResources().getIdentifier(name, "drawable", ctx.getPackageName());
            if (id != 0) {
                try {
                    return ctx.getResources().getDrawable(id);
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private Drawable moduleDrawable(int resourceId) {
        try {
            Context moduleContext = ctx.createPackageContext(
                    "moe.lovefirefly.betterzuxsidebar", Context.CONTEXT_IGNORE_SECURITY);
            return moduleContext.getResources().getDrawable(resourceId, moduleContext.getTheme());
        } catch (Throwable t) {
            LogHelper.log(LogHelper.VerboseLevel.WARNING,
                    "load module drawable failed: ", String.valueOf(t));
            return null;
        }
    }

    private View buildSearchRow() {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(6), dp(6));

        EditText search = new EditText(ctx);
        searchField = search;
        search.setHint("搜索...");
        search.setSingleLine(true);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        search.setHintTextColor(isNight() ? 0x66FFFFFF : 0x66000000);
        search.setTextColor(labelColor());
        search.setBackground(roundedRect(searchBgColor(), dp(PANEL_CORNER_DP)));
        int sp = dp(10);
        search.setPadding(sp, sp, sp, sp);
        // 弹窗本身是 focusable 的，面板一滑出/鼠标一悬停，输入框获焦就会弹出软键盘挡住界面。
        // 但之前用 setFocusable(false) 来防这件事是错的：那样 requestFocus() 永远失败，
        // 必须先点一下输入框（OnClickListener 里再解锁）才能键入。
        // 正解：保留可聚焦能力（键盘输入/IME 都要它），只禁止“获焦即弹键盘”；
        // 需要软键盘时点一下输入框即可，showSoftInput 不受这个开关影响。
        search.setShowSoftInputOnFocus(false);
        search.setOnClickListener(v -> {
            search.requestFocus();
            InputMethodManager imm = (InputMethodManager)
                    ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
            }
        });
        // 按 Enter：有搜索结果就定位到第一个格子，没有就定位到顶部钉住行，进入键盘导航。
        // OnKeyListener 能拦住物理键盘的 Enter；IME 那边则走 OnEditorActionListener。
        search.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && (keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                enterKeyboardNav();
                return true;
            }
            return false;
        });
        search.setOnEditorActionListener((v, actionId, event) -> {
            if (event == null || event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                enterKeyboardNav();
                return true;
            }
            return false;
        });

        // 清空按钮：直接用现成的图标当输入框右侧的复合图标（有文字时才显示）
        final Drawable clearIcon = findDrawable("ic_clear_zui", "abc_ic_clear_material", "ic_clear");
        final int clearPx = dp(18);
        if (clearIcon != null) {
            search.setCompoundDrawablePadding(dp(6));
            search.setOnTouchListener((v, ev) -> {
                if (ev.getAction() == MotionEvent.ACTION_UP) {
                    Drawable d = search.getCompoundDrawables()[2];
                    if (d != null && ev.getX() >= search.getWidth()
                            - search.getPaddingRight() - d.getBounds().width()) {
                        search.setText("");     // 点右侧清空图标
                        return true;
                    }
                }
                return false;
            });
        }
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (clearIcon != null) {
                    if (s != null && s.length() > 0) {
                        clearIcon.setBounds(0, 0, clearPx, clearPx);
                        search.setCompoundDrawables(null, null, clearIcon, null);
                    } else {
                        search.setCompoundDrawables(null, null, null, null);
                    }
                }
                rebuildRows(s == null ? null : s.toString());
            }
        });
        row.addView(search, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        editModeButton = new ImageButton(ctx);
        editModeButton.setContentDescription("编辑应用列表");
        Drawable editIcon = moduleDrawable(moe.lovefirefly.betterzuxsidebar.R.drawable.ic_material_edit);
        if (editIcon != null) {
            editModeButton.setImageDrawable(editIcon);
        }
        editModeButton.setScaleType(ImageView.ScaleType.CENTER);
        applyEditModeButtonAppearance();
        editModeButton.setStateListAnimator(null);
        editModeButton.setOnClickListener(v -> setAllAppsEditMode(!allAppsEditMode));
        updateEditModeButton();
        row.addView(editModeButton, new LinearLayout.LayoutParams(dp(40), dp(40)));
        menuButton = new ImageButton(ctx);
        menuButton.setContentDescription("更多");
        Drawable moreIcon = moduleDrawable(moe.lovefirefly.betterzuxsidebar.R.drawable.ic_material_more_vert);
        if (moreIcon != null) {
            menuButton.setImageDrawable(moreIcon);
        }
        menuButton.setScaleType(ImageView.ScaleType.CENTER);
        menuButton.setBackground(pressedBackground(dp(10)));
        menuButton.setStateListAnimator(null);
        menuButton.setColorFilter(labelColor(), android.graphics.PorterDuff.Mode.SRC_IN);
        menuButton.setOnClickListener(v -> showSearchMenu(menuButton));
        menuButton.setOnLongClickListener(v -> {
            forceReload();
            return true;
        });
        row.addView(menuButton, new LinearLayout.LayoutParams(dp(40), dp(40)));
        return row;
    }

    private void setAllAppsEditMode(boolean enabled) {
        allAppsEditMode = enabled;
        updateEditModeButton();
        rebuildPinnedViews();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    public void onAllAppsPageHidden() {
        if (searchField != null) {
            searchField.setText("");
        }
        if (allAppsEditMode) {
            setAllAppsEditMode(false);
        }
    }

    public void onVendorPageAttached() {
        dismissSearchMenu();
        if (themeRefreshPending) {
            themeRefreshPending = false;
            refreshAppearanceSafely();
            refreshIconsAsync();
        }
    }

    private void dismissSearchMenu() {
        if (searchMenu != null) {
            searchMenu.setOnDismissListener(null);
            searchMenu.dismiss();
            searchMenu = null;
        }
    }

    private void updateEditModeButton() {
        if (editModeButton != null) {
            applyEditModeButtonAppearance();
        }
    }

    private void applyEditModeButtonAppearance() {
        if (editModeButton != null) {
            editModeButton.setBackground(allAppsEditMode
                    ? roundedRect(withAlpha(systemAccentColor(), 0x38), dp(20))
                    : roundedRect(0x00000000, dp(20)));
            editModeButton.setAlpha(allAppsEditMode ? 1f : 0.55f);
            editModeButton.setColorFilter(labelColor(),
                android.graphics.PorterDuff.Mode.SRC_IN);
            editModeButton.invalidate();
        }
    }

    private void showSearchMenu(View anchor) {
        LogHelper.log(LogHelper.VerboseLevel.INFO, "search menu opened");
        dismissSearchMenu();
        android.content.res.Configuration menuConfig =
                new android.content.res.Configuration(ctx.getResources().getConfiguration());
        Context configContext = ctx.createConfigurationContext(menuConfig);
        int menuTheme = isNight()
            ? android.R.style.Theme_DeviceDefault
            : android.R.style.Theme_DeviceDefault_Light;
        Context menuContext = new ContextThemeWrapper(configContext, menuTheme);
        PopupMenu menu = new PopupMenu(menuContext, anchor);
        searchMenu = menu;
        menu.setOnDismissListener(ignored -> {
            if (searchMenu == menu) {
                searchMenu = null;
            }
        });
        android.view.MenuItem refreshItem = menu.getMenu().add("刷新应用列表和图标");
        refreshItem.setCheckable(false);
        android.view.MenuItem top5Item = menu.getMenu().add("快捷栏恢复最常用5应用");
        top5Item.setCheckable(true);
        top5Item.setChecked(prefs().getBoolean(PREF_SHORTCUT_BAR_TOP5, false));
        android.view.MenuItem themeItem = menu.getMenu().add("图标跟随系统主题");
        themeItem.setCheckable(true);
        themeItem.setChecked(prefs().getBoolean(PREF_FOLLOW_SYSTEM_THEME, true));
        android.view.MenuItem directAllAppsItem = menu.getMenu().add("直接打开此页面");
        directAllAppsItem.setCheckable(true);
        directAllAppsItem.setChecked(prefs().getBoolean(PREF_OPEN_ALL_APPS_DIRECTLY, false));
        menu.setOnMenuItemClickListener(item -> {
            String title = String.valueOf(item.getTitle());
            if (item == refreshItem) {
                android.util.Log.e("BetterZUXSidebar", "REFRESH_MENU_ONCLICK_DIRECT");
                LogHelper.log(LogHelper.VerboseLevel.INFO, "refresh menu clicked palette=",
                        currentSystemPalette());
                forceReload();
                return true;
            }
            if (item == top5Item) {
                boolean enabled = !item.isChecked();
                item.setChecked(enabled);
                prefs().edit().putBoolean(PREF_SHORTCUT_BAR_TOP5, enabled).apply();
                forceReload();
                return true;
            }
            if (item == themeItem) {
                boolean follow = !item.isChecked();
                item.setChecked(follow);
                prefs().edit().putBoolean(PREF_FOLLOW_SYSTEM_THEME, follow).apply();
                LauncherIconLoader.setFollowSystemTheme(follow);
                forceReload();
                return true;
            }
            if (item == directAllAppsItem) {
                boolean enabled = !item.isChecked();
                item.setChecked(enabled);
                prefs().edit().putBoolean(PREF_OPEN_ALL_APPS_DIRECTLY, enabled).apply();
                return true;
            }
            return false;
        });
        menu.show();
    }

    private String currentSystemPalette() {
        try {
            android.provider.Settings.Secure secure = null;
            String value = android.provider.Settings.Secure.getString(
                    ctx.getContentResolver(), "theme_customization_overlay_packages");
            return value == null ? "<null>" : value;
        } catch (Throwable t) {
            return "<error>";
        }
    }

    /** 一条 1dp 分割线（跟随昼夜主题，很淡）。 */
    private View buildDivider() {
        View line = new View(ctx);
        line.setBackgroundColor(isNight() ? 0x26FFFFFF : 0x1A000000);
        line.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return line;
    }

    /** B 页顶部冻结的钉住行：既是展示，也是拖拽落点。5 个等宽槽位 + 与网格相同的
     *  左右 padding，保证和下面 ListView 的 5 列一一对齐。 */
    private View buildPinnedBar() {
        pinnedBar = new LinearLayout(ctx);
        // 竖向容器：里面按行放；每行 COLUMNS 个等宽格子（超出自动换行，不再挤出）
        pinnedBar.setOrientation(LinearLayout.VERTICAL);
        pinnedBar.setPadding(dp(GRID_PADDING_DP), 0, dp(GRID_PADDING_DP), dp(8));
        // 跨行移动时格子会超出所在行的边界，关掉裁剪，否则动画期间会被裁掉（看起来被行框挡住）
        pinnedBar.setClipChildren(false);
        pinnedBar.setClipToPadding(false);
        // 格子按 pinnedApps 动态创建/换行，见 rebuildPinnedGrid()

        pinnedBar.setOnDragListener((v, event) -> {
            LogHelper.log(LogHelper.VerboseLevel.WARNING, "pinned drag action=",
                String.valueOf(event.getAction()), " result=", String.valueOf(event.getResult()),
                " external=", String.valueOf(externalDragInProgress),
                " from=", String.valueOf(dragFromPinned),
                " local=", event.getLocalState() == null ? "null"
                    : event.getLocalState().getClass().getName());
            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    return true;
                case DragEvent.ACTION_DRAG_LOCATION:
                    if (externalDragInProgress || !allAppsEditMode) {
                        return true;
                    }
                    previewPinnedDrop(event.getX(), event.getY());
                    return true;
                case DragEvent.ACTION_DRAG_EXITED:
                    if (externalDragInProgress || !allAppsEditMode) {
                        return true;
                    }
                    LogHelper.log(LogHelper.VerboseLevel.INFO, "pinned DRAG_EXITED from=", String.valueOf(dragFromPinned));
                    if (dragFromPinned >= 0) {
                        previewPinnedDelete();   // 拖出钉住区 → 预览"删除"（其余格子合拢）
                    } else {
                        clearPinnedPreview(true);
                    }
                    return true;
                case DragEvent.ACTION_DRAG_ENDED: {
                    if (externalDragInProgress) {
                        if (externalDragCell != null) {
                            externalDragCell.setAlpha(1f);
                            externalDragCell = null;
                        }
                        externalDragInProgress = false;
                        return true;
                    }
                    int from = dragFromPinned;
                    boolean accepted = event.getResult();
                    LogHelper.log(LogHelper.VerboseLevel.INFO, "pinned DRAG_ENDED result=",
                            String.valueOf(accepted), " from=", String.valueOf(from));
                    // ACTION_DRAG_ENDED(result=false) 同时表示“拖到窗口外未被本窗口接收”。
                    // 由于现在图标拖拽带有全局 IntentSender，窗口外拖拽应交给 SystemUI 分屏，
                    // 不能再把这个事件误判成“删除 pinned”。删除不再由 drag ended 触发。
                    if (pinnedDragCell != null) {
                        pinnedDragCell.setAlpha(1f);   // 恢复被隐藏的原图标
                        pinnedDragCell = null;
                    }
                    if (!accepted) {
                        clearPinnedPreview(true);
                    }
                    dragFromPinned = -1;
                    dragInProgress = false;
                    return true;
                }
                case DragEvent.ACTION_DROP: {
                    if (externalDragInProgress || !allAppsEditMode) {
                        return true;
                    }
                    Object app = event.getLocalState();
                    int from = dragFromPinned;
                    // 不复位预览位移：留给 applyPinnedDrop 从当前位置平滑归位（否则会看到跳变）
                    pinnedPreviewState = -1;
                    if (app != null) {
                        applyPinnedDrop(app, from, event.getX(), event.getY());
                    }
                    dragFromPinned = -1;
                    return true;
                }
                default:
                    return true;
            }
        });
        return pinnedBar;
    }

    /** 钉住行每格的宽度（去掉左右 padding）。 */
    private float pinnedCellWidth() {
        if (pinnedBar == null) {
            return 0f;
        }
        int content = pinnedBar.getWidth() - pinnedBar.getPaddingLeft() - pinnedBar.getPaddingRight();
        return content <= 0 ? 0f : (float) content / COLUMNS;
    }

    /**
     * 解析拖拽落点 → 挤压插入位（线性序号 0..格子数）。
     * 取离落点最近的格子中心（= launcher 的 findNearestArea），再按落点在格心左右决定插到前/后。
     * 所有落子一律"挤压"，不做替换/交换。
     */
    private int resolvePinnedInsertIndex(float x, float y) {
        if (pinnedSlots.isEmpty()) {
            return 0;
        }
        int[] c = new int[2];
        int best = 0;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < pinnedSlots.size(); i++) {
            slotCenter(pinnedSlots.get(i), c);
            float d = (float) Math.hypot(x - c[0], y - c[1]);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        slotCenter(pinnedSlots.get(best), c);
        int insert = x < c[0] ? best : best + 1;
        return Math.max(0, Math.min(pinnedSlots.size(), insert));
    }

    /** 某个格子中心在 pinnedBar 坐标系里的位置（含 translation，即当前视觉位置）。 */
    private void slotCenter(View slot, int[] out) {
        Rect r = new Rect(0, 0, slot.getWidth(), slot.getHeight());
        pinnedBar.offsetDescendantRectToMyCoords(slot, r);
        out[0] = r.centerX();
        out[1] = r.centerY();
    }

    /** 某个格子的"布局中心"（不含 translation），用 getLeft/getTop 逐层累加。 */
    private void slotLayoutCenter(View slot, int[] out) {
        float x = 0f;
        float y = 0f;
        View v = slot;
        while (v != null && v != pinnedBar) {
            x += v.getLeft();
            y += v.getTop();
            android.view.ViewParent p = v.getParent();
            v = (p instanceof View) ? (View) p : null;
        }
        out[0] = Math.round(x + slot.getWidth() / 2f);
        out[1] = Math.round(y + slot.getHeight() / 2f);
    }

    /** 第 i 个格子里 cell 的实际高度（没有 cell 时按一格算）。 */
    private int cellHeightOf(int i) {
        if (i < pinnedSlots.size()) {
            FrameLayout slot = pinnedSlots.get(i);
            if (slot.getChildCount() > 0) {
                int h = slot.getChildAt(0).getHeight();
                if (h > 0) {
                    return h;
                }
            }
        }
        return pinnedContentRowHeight();
    }

    /** 按 toPos 预估"重排后"每行的实际高度（某行会放入高格子就变高；没内容的行 = 0）。 */
    private int[] futureRowHeights(int[] toPos, int n) {
        int rows = Math.max(1, pinnedBar == null ? 1 : pinnedBar.getChildCount());
        int[] h = new int[rows];
        for (int i = 0; i < n && i < pinnedSlots.size(); i++) {
            int destRow = toPos[i] / COLUMNS;
            if (destRow >= 0 && destRow < rows) {
                h[destRow] = Math.max(h[destRow], cellHeightOf(i));
            }
        }
        return h;
    }

    /**
     * 目标格在"重排后"布局里的中心：行高用 futureRowHeights 预估，
     * 这样即使某行因为 2 行文字变高/变矮，也不会差出一行字。
     */
    private void destLayoutCenter(int dest, int[] out, int[] frh, int cellH) {
        int row = dest / COLUMNS;
        int y = pinnedBar.getPaddingTop();
        for (int r = 0; r < row; r++) {
            y += (r < frh.length && frh[r] > 0) ? frh[r] : pinnedContentRowHeight();
        }
        out[0] = Math.round(destCenterX(dest));
        // 格子在某行里是顶部对齐的 → 垂直中心用"格子自身高度的一半"，而不是行高的一半
        out[1] = Math.round(y + cellH / 2f);
    }

    /** 一格（一行）的高度：取第一行的高度；没有内容时用估算值。 */
    private int pinnedContentRowHeight() {
        if (pinnedBar != null && pinnedBar.getChildCount() > 0) {
            int h = pinnedBar.getChildAt(0).getHeight();
            if (h > 0) {
                return h;
            }
        }
        return dp(GRID_ICON_DP + 30);
    }

    /** 第 dest 个格子的目标中心 X（解析式，不依赖当前布局）。 */
    private float destCenterX(int dest) {
        return pinnedBar.getPaddingLeft() + ((dest % COLUMNS) + 0.5f) * pinnedCellWidth();
    }

    /** 第 dest 个格子的目标中心 Y：累加其上各行的实际高度 + 半格高。 */
    private float destCenterY(int dest) {
        int row = dest / COLUMNS;
        float cellH = pinnedContentRowHeight();
        float y = pinnedBar.getPaddingTop();
        for (int r = 0; r < row && r < pinnedBar.getChildCount(); r++) {
            float h = pinnedBar.getChildAt(r).getHeight();
            y += (h > 0 ? h : cellH);   // 尚未展开的行按一格算
        }
        return y + cellH / 2f;
    }

    /** 预览时的"行数扩展"动画：把内容行之外的行高从当前值动画到 target。 */
    private void animateExtraRowHeight(int target) {
        if (rowGrowAnimator != null) {
            rowGrowAnimator.cancel();
        }
        if (Math.round(extraRowH) == target) {
            extraRowH = target;
            applyExtraRowHeight();
            return;
        }
        rowGrowAnimator = android.animation.ValueAnimator.ofFloat(extraRowH, target);
        rowGrowAnimator.setDuration(REORDER_ANIM_MS);
        rowGrowAnimator.setInterpolator(new android.view.animation.DecelerateInterpolator());
        rowGrowAnimator.addUpdateListener(a -> {
            extraRowH = (Float) a.getAnimatedValue();
            applyExtraRowHeight();
        });
        rowGrowAnimator.start();
    }

    /** 把内容行之外的行设成当前额外行高（0 = 收起来）。 */
    private void applyExtraRowHeight() {
        if (pinnedBar == null) {
            return;
        }
        int contentRows = (pinnedKeys.size() + COLUMNS - 1) / COLUMNS;
        int h = Math.round(extraRowH);
        for (int r = contentRows; r < pinnedBar.getChildCount(); r++) {
            pinnedBar.getChildAt(r).setMinimumHeight(h);
        }
        pinnedBar.requestLayout();
    }

    /** 拖拽悬停时实时预览挤压：把会移动的格子平移到目标格子所在的位置。 */
    private void previewPinnedDrop(float x, float y) {
        int index = resolvePinnedInsertIndex(x, y);
        if (index == pinnedPreviewState) {
            return;
        }
        pinnedPreviewState = index;
        if (index < 0) {
            return;
        }
        int from = dragFromPinned;
        int n = Math.min(pinnedKeys.size(), pinnedSlots.size());

        // 每个现有项的新位置（线性序号）
        int[] toPos = new int[n];
        for (int i = 0; i < n; i++) {
            toPos[i] = i;
        }
        int maxPos = n > 0 ? n - 1 : 0;
        if (from >= 0) {
            int at = from < index ? index - 1 : index;
            at = Math.max(0, Math.min(n - 1, at));   // 落到最终列表里的位置（0..n-1），否则会越界偏列
            for (int i = 0; i < n; i++) {
                if (i == from) {
                    toPos[i] = at;
                } else if (from < at && i > from && i <= at) {
                    toPos[i] = i - 1;
                } else if (from > at && i >= at && i < from) {
                    toPos[i] = i + 1;
                }
                maxPos = Math.max(maxPos, toPos[i]);
            }
        } else {
            maxPos = Math.max(maxPos, index);
            for (int i = 0; i < n; i++) {
                if (i >= index) {
                    toPos[i] = i + 1;
                    maxPos = Math.max(maxPos, toPos[i]);
                }
            }
        }

        // 会被挤到新行的 → 交给 applyPreviewTranslations 按预估行高撑开
        applyPreviewTranslations(toPos, n, maxPos);
    }

    /** 按 toPos 把各格子平移到目标格中心（预览用）；不再需要移动的动画归位。
     *  @param maxPos 本次预览可能占到的最大线性序号（含"新图标要放的位置"） */
    private void applyPreviewTranslations(int[] toPos, int n, int maxPos) {
        // 重排后各行的预估高度（考虑 2 行文字的格子进出某行）
        int[] frh = futureRowHeights(toPos, n);
        // 当前内容行之外的行需要多高：有格子搬进去就按那格的高度；
        // 只是"落点在新行里"（没格子搬过去）也要开出一格来，不然看不到落点
        int curContentRows = (n + COLUMNS - 1) / COLUMNS;
        int extra = 0;
        for (int r = curContentRows; r < frh.length; r++) {
            extra = Math.max(extra, frh[r]);
        }
        if (extra == 0 && (maxPos / COLUMNS + 1) > curContentRows) {
            extra = pinnedContentRowHeight();
        }
        animateExtraRowHeight(extra);

        if (LogHelper.currentLevel != LogHelper.VerboseLevel.SILENT) {
            StringBuilder rb = new StringBuilder();
            for (int r = 0; r < (pinnedBar == null ? 0 : pinnedBar.getChildCount()); r++) {
                rb.append("row").append(r).append('=').append(pinnedBar.getChildAt(r).getHeight()).append(' ');
            }
            LogHelper.log(LogHelper.VerboseLevel.WARNING, "preview n=", String.valueOf(n),
                    " toPos=", java.util.Arrays.toString(toPos),
                    " frh=", java.util.Arrays.toString(frh),
                    " extra=", String.valueOf(extra),
                    " cellH=", String.valueOf(pinnedContentRowHeight()),
                    " realRows[", rb.toString(), "]");
        }

        int[] oldC = new int[2];
        float acc = 0f;
        float step = REORDER_DELAY_STEP_MS;
        for (int i = 0; i < pinnedSlots.size(); i++) {
            View slot = pinnedSlots.get(i);
            slot.animate().cancel();
            if (i >= n || toPos[i] == i) {
                // 不再需要移动了 → 也要"飞回去"，不能瞬间归零（否则看着像瞬移）
                if (slot.getTranslationX() != 0f || slot.getTranslationY() != 0f) {
                    slot.animate().translationX(0f).translationY(0f)
                            .setDuration(REORDER_ANIM_MS)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator());
                } else {
                    slot.setTranslationX(0f);
                    slot.setTranslationY(0f);
                }
                continue;
            }
            slotCenter(slot, oldC);
            int[] newC = new int[2];
            destLayoutCenter(toPos[i], newC, frh, cellHeightOf(i));
            float dx = newC[0] - oldC[0];
            float dy = newC[1] - oldC[1];
            slot.animate().translationX(dx).translationY(dy)
                    .setDuration(REORDER_ANIM_MS)
                    .setStartDelay((long) acc);
            if (LogHelper.currentLevel != LogHelper.VerboseLevel.SILENT) {
                LogHelper.log(LogHelper.VerboseLevel.WARNING, "  move i=", String.valueOf(i),
                        " -> ", String.valueOf(toPos[i]),
                        " from=(", String.valueOf(oldC[0]), ",", String.valueOf(oldC[1]), ")",
                        " to=(", String.valueOf(newC[0]), ",", String.valueOf(newC[1]), ")",
                        " d=(", String.valueOf((int) dx), ",", String.valueOf((int) dy), ")",
                        " cellH=", String.valueOf(cellHeightOf(i)));
            }
            acc += step;
            step *= 0.9f;
        }
    }

    /** 预览"拖出删除"：其余格子合拢补上空位（与插入预览同一套动画）。 */
    private void previewPinnedDelete() {
        int from = dragFromPinned;
        if (from < 0) {
            return;
        }
        pinnedPreviewState = -1;
        int n = Math.min(pinnedKeys.size(), pinnedSlots.size());
        int[] toPos = new int[n];
        int maxPos = 0;
        for (int i = 0; i < n; i++) {
            toPos[i] = (i > from) ? i - 1 : i;
            maxPos = Math.max(maxPos, toPos[i]);
        }
        applyPreviewTranslations(toPos, n, maxPos);
    }

    /** 拖出删除落子（预览已合拢，直接落子，不再做缩小/位移动画）。 */
    private void commitPinnedDelete(int index) {
        if (index < 0 || index >= pinnedKeys.size()) {
            return;
        }
        pinnedKeys.remove(index);
        collapseShrinkingRowsThen(pinnedKeys.size(), () -> {
            savePinned();
            loadPinned();
            rebuildPinnedViews();
            resetPinnedTranslations();
            LogHelper.log(LogHelper.VerboseLevel.INFO, "pinned remove -> ", pinnedKeys.toString());
        });
    }

    /** 清掉预览位移与"新开行"的高度。@param animate true=平滑回位，false=立刻归零。 */
    private void clearPinnedPreview(boolean animate) {
        LogHelper.log(LogHelper.VerboseLevel.INFO, "clearPinnedPreview animate=",
                String.valueOf(animate), " previewState=", String.valueOf(pinnedPreviewState),
                " extraRowH=", String.valueOf((int) extraRowH));
        pinnedPreviewState = -1;
        if (animate) {
            animateExtraRowHeight(0);
        } else {
            if (rowGrowAnimator != null) {
                rowGrowAnimator.cancel();
            }
            extraRowH = 0f;
            applyExtraRowHeight();
        }
        for (int i = 0; i < pinnedSlots.size(); i++) {
            View slot = pinnedSlots.get(i);
            slot.animate().cancel();
            if (animate) {
                // 与"行收起"同一时长/曲线，飞回来才连贯
                slot.animate().translationX(0f).translationY(0f).setDuration(REORDER_ANIM_MS)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator());
            } else {
                slot.setTranslationX(0f);
                slot.setTranslationY(0f);
            }
        }
    }

    /**
     * 落子：一律"挤压插入"。from >= 0 = 行内移动（内部重排，不丢东西）；
     * from < 0 = 从网格拖入（超过一行就自动换行，不再挤出）。
     */
    private void applyPinnedDrop(Object app, int from, float x, float y) {
        int index = resolvePinnedInsertIndex(x, y);
        String key = appKey(app);
        if (index < 0 || key == null) {
            return;
        }
        // 从网格拖入的若是已钉住的图标 → 视作行内移动，而不是新增
        int effFrom = from;
        if (effFrom < 0) {
            int existing = pinnedKeys.indexOf(key);
            if (existing >= 0) {
                effFrom = existing;
            }
        }

        int placed;
        if (effFrom >= 0) {
            String moved = pinnedKeys.remove(effFrom);
            int at = effFrom < index ? index - 1 : index;
            at = Math.max(0, Math.min(pinnedKeys.size(), at));
            pinnedKeys.add(at, moved);
            placed = at;
        } else {
            int at = Math.max(0, Math.min(pinnedKeys.size(), index));
            pinnedKeys.add(at, key);
            placed = at;
        }

        final boolean isNewFromGrid = effFrom < 0;
        final int fPlaced = placed;
        // 落子后行数若变少，先把多出来的行动画收起再重建（否则是瞬间塌陷）
        collapseShrinkingRowsThen(pinnedKeys.size(), () -> {
            savePinned();
            loadPinned();
            rebuildPinnedViews();
            // 立即把位移归零：预览时邻居已经停在"终态位置"，重建后 translation 归 0 视觉连续
            resetPinnedTranslations();
            // 只有"从下方（网格）拖入的新图标"才做长出动画；行内移动不做
            if (isNewFromGrid) {
                growInPinnedSlot(fPlaced);
            }
        });
    }

    /** 落子前：若落子后行数变少，先把多出来的行平滑收到 0，再执行 commit。 */
    private void collapseShrinkingRowsThen(int newCount, Runnable commit) {
        if (pinnedBar == null) {
            commit.run();
            return;
        }
        int curRows = pinnedBar.getChildCount();
        int newRows = Math.max(1, (Math.max(newCount, 1) + COLUMNS - 1) / COLUMNS);
        if (curRows <= newRows) {
            commit.run();
            return;
        }
        final int rowsFrom = newRows;
        final int[] origH = new int[curRows];
        for (int r = 0; r < curRows; r++) {
            origH[r] = pinnedBar.getChildAt(r).getHeight();
        }
        pinnedAnimating = true;
        android.animation.ValueAnimator anim =
                android.animation.ValueAnimator.ofFloat(1f, 0f);
        anim.setDuration(REORDER_ANIM_MS);
        anim.setInterpolator(new android.view.animation.DecelerateInterpolator());
        anim.addUpdateListener(a -> {
            float t = (Float) a.getAnimatedValue();
            for (int r = rowsFrom; r < pinnedBar.getChildCount() && r < origH.length; r++) {
                View row = pinnedBar.getChildAt(r);
                android.view.ViewGroup.LayoutParams lp = row.getLayoutParams();
                lp.height = Math.max(1, Math.round(origH[r] * t));
                row.setLayoutParams(lp);
            }
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                pinnedAnimating = false;
                commit.run();
            }
        });
        anim.start();
    }

    /** 把所有钉住格子立即归零（位置与透明度）。 */
    private void resetPinnedTranslations() {
        if (rowGrowAnimator != null) {
            rowGrowAnimator.cancel();
        }
        extraRowH = 0f;
        for (int i = 0; i < pinnedSlots.size(); i++) {
            View slot = pinnedSlots.get(i);
            slot.animate().cancel();
            slot.setTranslationX(0f);
            slot.setTranslationY(0f);
            slot.setAlpha(1f);
        }
    }

    /** 从钉住区删除第 index 个（把图标往下拖出钉住区 = 删除），带"缩小消失"动画。 */
    private void removePinnedAt(int index) {
        if (index < 0 || index >= pinnedKeys.size() || index >= pinnedSlots.size()) {
            return;
        }
        final int i = index;
        final int contentH = pinnedContentRowHeight();   // 在重建前取（此时布局有效）
        FrameLayout slot = pinnedSlots.get(i);
        View cell = slot.getChildCount() > 0 ? slot.getChildAt(0) : null;

        // 记录删除前每个格子的中心，供重建后计算"从哪滑到哪"
        final int[] oldX = new int[pinnedSlots.size()];
        final int[] oldY = new int[pinnedSlots.size()];
        int[] c = new int[2];
        for (int k = 0; k < pinnedSlots.size(); k++) {
            slotCenter(pinnedSlots.get(k), c);
            oldX[k] = c[0];
            oldY[k] = c[1];
        }

        Runnable commit = () -> {
            if (i < pinnedKeys.size()) {
                pinnedKeys.remove(i);
            }
            savePinned();
            loadPinned();
            rebuildPinnedViews();
            // 新格子 k(>=i) 的内容来自旧格子 k+1：先摆到旧中心，再平滑滑回 0。
            // 刚 rebuild 过的子视图还没布局（getWidth/Height 都是 0），slotCenter 不可用
            // → 新中心必须用解析式算，否则起点会算错（看着像先瞬移到别处再飞回来）。
            float acc = 0f;
            float step = REORDER_DELAY_STEP_MS;
            float cellW = pinnedCellWidth();
            float padLeft = pinnedBar == null ? 0f : pinnedBar.getPaddingLeft();
            float padTop = pinnedBar == null ? 0f : pinnedBar.getPaddingTop();
            for (int k = i; k < pinnedSlots.size() && k + 1 < oldX.length; k++) {
                View s = pinnedSlots.get(k);
                s.animate().cancel();
                float ncX = padLeft + ((k % COLUMNS) + 0.5f) * cellW;
                float ncY = padTop + (k / COLUMNS + 0.5f) * contentH;
                float dx = oldX[k + 1] - ncX;
                float dy = oldY[k + 1] - ncY;
                if (dx == 0f && dy == 0f) {
                    continue;
                }
                s.setTranslationX(dx);
                s.setTranslationY(dy);
                s.animate().translationX(0f).translationY(0f)
                        .setDuration(REORDER_ANIM_MS)
                        .setStartDelay((long) acc)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator());
                acc += step;
                step *= 0.9f;
            }
            LogHelper.log(LogHelper.VerboseLevel.INFO, "pinned remove -> ", pinnedKeys.toString());
        };

        if (cell != null) {
            cell.animate().scaleX(0f).scaleY(0f).alpha(0f)
                    .setDuration(160L)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator())
                    .withEndAction(commit)
                    .start();
        } else {
            commit.run();
        }
    }

    /** 单格放大淡入（0.6→1，alpha 0→1，贝塞尔），对应 launcher 的"长出"观感。 */
    private void growInPinnedSlot(int index) {
        if (index < 0 || index >= pinnedSlots.size()) {
            return;
        }
        FrameLayout slot = pinnedSlots.get(index);
        if (slot.getChildCount() == 0) {
            return;
        }
        View cell = slot.getChildAt(0);
        cell.setScaleX(0.6f);
        cell.setScaleY(0.6f);
        cell.setAlpha(0f);
        cell.animate().scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(200L)
                .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
                .start();
    }

    /** 自定义索引条，完全绕过 vendor 的 AlphabeticIndexBar 以避免 BadTokenException。 */
    private void buildIndexBar() {
        try {
            indexBar = new IndexBarView(ctx);
            
            // 设置初始行高，布局完成后会精确调整
            int estimated = panelHeightPx() - dp(56) - dp(100);
            indexBar.setItemHeight(Math.max(dp(8), estimated / INDEX_BAR_SLOTS));
            
            indexBarHost = new FrameLayout(ctx) {
                @Override
                public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
                    try {
                        return super.dispatchTouchEvent(ev);
                    } catch (Throwable t) {
                        // 吃掉异常，保住进程
                        return true;
                    }
                }

                @Override
                public boolean dispatchHoverEvent(android.view.MotionEvent ev) {
                    try {
                        return super.dispatchHoverEvent(ev);
                    } catch (Throwable t) {
                        return true;
                    }
                }
            };
            indexBarHost.addView(indexBar, new FrameLayout.LayoutParams(
                    dp(48), ViewGroup.LayoutParams.MATCH_PARENT));

            // 设置字母变化监听器；颜色跟随昼夜主题（theme 变化时 rebuildRows 会再刷新）
            indexBar.setOnLetterChangedListener(letter -> jumpToSection(letter));
            indexBar.setLetterColors(labelColor(),
                    isNight() ? 0xFFFF8A80 : 0xFFFF6B6B,
                    isNight() ? 0x33FFFFFF : 0x33000000);
        } catch (Throwable t) {
            indexBar = null;
            indexBarHost = null;
            LogHelper.log(LogHelper.VerboseLevel.WARNING, "index bar unavailable: ", String.valueOf(t));
        }
    }

    // ---------------------------------------------------------------- 数据

    private void resolveVendorApi() {
        try {
            Class<?> utils = Class.forName("com.zui.freeform.sidebar.utils.SidebarAppUtils", false, targetCl);
            mGenerateLauncherApp = utils.getMethod("generateLauncherApp", Context.class);
            mRefreshLauncherApp = utils.getMethod("refreshLauncherApp", Context.class);
            mRefreshLauncherAppIcon = utils.getMethod("refreshLauncherAppIcon", Context.class);
            mIsBadgedAppChanged = utils.getMethod("isBadgedAppChanged");
            mSwitchChangedFlag = utils.getField("isSideBarSwitchChangeFlag");

            Class<?> appInfo = Class.forName("com.zui.freeform.sidebar.utils.AppInfo", false, targetCl);
            mGetAppName = appInfo.getMethod("getAppName");
            mGetPkgName = appInfo.getMethod("getPkgName");
            mGetClassName = appInfo.getMethod("getClassName");
            mGetAppIcon = appInfo.getMethod("getAppIcon");
            mGetUser = appInfo.getMethod("getUser");
        } catch (Throwable t) {
            LogHelper.log(LogHelper.VerboseLevel.ERROR, "resolve vendor api failed: ", String.valueOf(t));
        }
    }

    /** 每次弹窗显示时刷新一次（两页共用同一份数据）。 */
    public void reload() {
        loadApps(false);
    }

    /** 进入 All Apps 只复用当前数据；完整图标/主题刷新由刷新菜单显式触发。 */
    public void reloadAllApps() {
        loadApps(false);
    }

    private void forceReload() {
        LogHelper.log(LogHelper.VerboseLevel.INFO, "force reload begin");
        appNameCache.clear();
        appPkgCache.clear();
        appClassCache.clear();
        refreshVendorApps();
        mainHandler.removeCallbacks(refreshCommit);
        mainHandler.postDelayed(refreshCommit, 500L);
    }

    private void refreshVendorApps() {
        try {
            if (mRefreshLauncherApp != null) {
                mRefreshLauncherApp.invoke(null, ctx);
            }
        } catch (Throwable t) {
            LogHelper.log(LogHelper.VerboseLevel.WARNING,
                    "refresh vendor apps failed: ", String.valueOf(t));
        }
        refreshVendorIcons();
    }

    private void refreshVendorIcons() {
        try {
            if (mRefreshLauncherAppIcon != null) {
                mRefreshLauncherAppIcon.invoke(null, ctx);
            }
        } catch (Throwable t) {
            LogHelper.log(LogHelper.VerboseLevel.WARNING,
                    "refresh vendor icons failed: ", String.valueOf(t));
        }
    }

    private void registerThemeReceiver() {
        try {
            Class<?> themeManager = Class.forName("zui.themes.ThemeManager", false, targetCl);
            String action = String.valueOf(themeManager.getField("ACTION_THEME_CHANGED").get(null));
            themeReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null) {
                        return;
                    }
                    String intentAction = intent.getAction();
                    if (Intent.ACTION_CONFIGURATION_CHANGED.equals(intentAction)) {
                        themeRefreshPending = true;
                        return;
                    }
                    if (!action.equals(intentAction)) {
                        return;
                    }
                        LogHelper.log(LogHelper.VerboseLevel.INFO,
                            "theme changed; wait for vendor popup rebuild");
                            themeRefreshPending = true;
                }
            };
            // 原版 FloatService 用 flag=2 注册；主题广播来自外部 ThemeManager，
            // NOT_EXPORTED 会让侧栏收不到 ACTION_THEME_CHANGED。
            IntentFilter filter = new IntentFilter(action);
            filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
            ctx.registerReceiver(themeReceiver, filter, Context.RECEIVER_EXPORTED);
            LogHelper.log(LogHelper.VerboseLevel.INFO, "theme receiver registered action=", action);
        } catch (Throwable t) {
            LogHelper.log(LogHelper.VerboseLevel.WARNING,
                    "theme receiver unavailable: ", String.valueOf(t));
        }
    }

    private void refreshAppearance() {
        host.refreshPopupBackground();
        if (pinnedPage != null) {
            pinnedPage.setBackground(panelBackground());
        }
        if (allAppsPage != null) {
            allAppsPage.setBackground(panelBackground());
        }
        if (editModeButton != null) {
            updateEditModeButton();
        }
        if (searchField != null) {
            searchField.setTextColor(labelColor());
            searchField.setHintTextColor(isNight() ? 0x66FFFFFF : 0x66000000);
            searchField.setBackground(roundedRect(searchBgColor(), dp(PANEL_CORNER_DP)));
        }
        if (moreButton != null) {
            moreButton.setBackground(pressedBackground(dp(10)));
            moreButton.setColorFilter(labelColor(), android.graphics.PorterDuff.Mode.SRC_IN);
            moreButton.refreshDrawableState();
        }
        if (menuButton != null) {
            menuButton.setColorFilter(labelColor(), android.graphics.PorterDuff.Mode.SRC_IN);
            menuButton.setBackground(pressedBackground(dp(10)));
            menuButton.refreshDrawableState();
        }
        if (indexBar != null) {
            indexBar.setLetterColors(labelColor(),
                    isNight() ? 0xFFFF8A80 : 0xFFFF6B6B,
                    isNight() ? 0x33FFFFFF : 0x33000000);
        }
        rebuildPinnedViews();
        rebuildRows(searchField == null ? "" : searchField.getText().toString());
    }

    private void refreshAppearanceSafely() {
        try {
            refreshAppearance();
        } catch (Throwable t) {
            LogHelper.log(LogHelper.VerboseLevel.ERROR,
                    "theme appearance refresh failed: ", String.valueOf(t));
        }
    }

    private void refreshIconsAsync() {
        // 厂商刷新涉及其 AppInfo/Icon 缓存和当前 Popup 状态，必须在主线程调用。
        mainHandler.post(() -> {
            try {
                refreshVendorApps();
            } catch (Throwable t) {
                LogHelper.log(LogHelper.VerboseLevel.WARNING,
                        "theme vendor refresh failed: ", String.valueOf(t));
            }
        });
        final List<Object> snapshot = new ArrayList<>(allApps);
        io.execute(() -> {
            LauncherIconLoader.clear(ctx);
            for (Object app : snapshot) {
                LauncherIconLoader.get(ctx, appPkg(app), appClassName(app));
            }
            mainHandler.post(() -> {
                if (listTouching || dragInProgress || pinnedAnimating || pinnedPreviewState != -1) {
                    return;
                }
                rebuildPinnedViews();
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
            });
        });
    }

    private void loadApps(boolean force) {
        if (mGenerateLauncherApp == null) {
            return;
        }
        try {
            if (force || isBadgedAppChanged() || consumeSwitchChanged()) {
                if (mRefreshLauncherApp != null) {
                    mRefreshLauncherApp.invoke(null, ctx);
                }
            }
            allApps.clear();
            appByKey.clear();
            Object list = mGenerateLauncherApp.invoke(null, ctx);
            if (list instanceof List) {
                allApps.addAll((List<?>) list);
            }
            for (Object app : allApps) {
                String key = appKey(app);
                if (key != null) {
                    appByKey.put(key, app);
                }
            }
            loadPinned();
            LogHelper.log(LogHelper.VerboseLevel.INFO, "load: all=", String.valueOf(allApps.size()),
                    " pinned=", String.valueOf(pinnedApps.size()), " force=", String.valueOf(force));
        } catch (Throwable t) {
            LogHelper.log(LogHelper.VerboseLevel.ERROR, "load failed: ", String.valueOf(t));
        }
        rebuildPinnedViews();
        rebuildRows(searchField == null ? "" : searchField.getText().toString());
    }

    private boolean isBadgedAppChanged() {
        try {
            Object v = mIsBadgedAppChanged.invoke(null);
            return v instanceof Boolean && (Boolean) v;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean consumeSwitchChanged() {
        try {
            boolean changed = (Boolean) mSwitchChangedFlag.get(null);
            if (changed) {
                mSwitchChangedFlag.set(null, false);
            }
            return changed;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 钉住列表存在厂商进程自己的 prefs 里（键 pkg:userId），与厂商的 top_N 互不干扰。 */
    private SharedPreferences prefs() {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private void loadPinned() {
        pinnedKeys.clear();
        pinnedApps.clear();
        SharedPreferences sp = prefs();
        for (int i = 1; i <= PINNED_MAX; i++) {
            String key = sp.getString(PIN_KEY_PREFIX + i, null);
            if (TextUtils.isEmpty(key)) {
                break;              // 键是连续的，遇到空就结束
            }
            pinnedKeys.add(key);
        }
        if (pinnedKeys.isEmpty()) {
            seedPinnedFromVendor();
        }
        for (String key : pinnedKeys) {
            Object app = appByKey.get(key);
            if (app != null) {
                pinnedApps.add(app);
            }
        }
    }

    /** 首次运行时用厂商原有的 top_1..top_5 做初始值，避免升级后钉住区是空的。 */
    private void seedPinnedFromVendor() {
        try {
            Class<?> utils = Class.forName("com.zui.freeform.sidebar.utils.SidebarAppUtils", false, targetCl);
            Object top5 = utils.getMethod("generateTop5App", Context.class).invoke(null, ctx);
            if (top5 instanceof List) {
                for (Object app : (List<?>) top5) {
                    String key = appKey(app);
                    if (key != null && !pinnedKeys.contains(key)) {
                        pinnedKeys.add(key);
                    }
                }
            }
            savePinned();
        } catch (Throwable t) {
            LogHelper.log(LogHelper.VerboseLevel.WARNING, "seed pinned failed: ", String.valueOf(t));
        }
    }

    private void savePinned() {
        SharedPreferences.Editor e = prefs().edit();
        int n = Math.min(pinnedKeys.size(), PINNED_MAX);
        for (int i = 1; i <= n; i++) {
            e.putString(PIN_KEY_PREFIX + i, pinnedKeys.get(i - 1));
        }
        for (int i = n + 1; i <= PINNED_MAX; i++) {
            e.remove(PIN_KEY_PREFIX + i);      // 清掉旧的尾部
        }
        e.apply();
    }

    /** 拖进来的应用插到松手的位置（0=最左），满员则把最后一个挤出去；已钉住的是移动。 */
    private void pin(Object app, int index) {
        String key = appKey(app);
        if (key == null) {
            return;
        }
        pinnedKeys.remove(key);
        int at = Math.min(index, pinnedKeys.size());
        if (pinnedKeys.size() >= PINNED_MAX) {
            // 满员时最多插到原最后一个之前，保证新来的不会立刻把自己挤出去
            at = Math.min(at, PINNED_MAX - 1);
        }
        pinnedKeys.add(at, key);
        while (pinnedKeys.size() > PINNED_MAX) {
            pinnedKeys.remove(pinnedKeys.size() - 1);
        }
        savePinned();
        loadPinned();
        rebuildPinnedViews(true);
        LogHelper.log(LogHelper.VerboseLevel.INFO, "pinned -> ", pinnedKeys.toString());
    }

    private void rebuildRows(String query) {
        // 结果集变了，原先的导航位置失效
        exitKeyboardNav();
        String q = query == null ? "" : query.trim().toLowerCase();
        List<Object> filtered = new ArrayList<>();
        for (Object app : allApps) {
            if (q.isEmpty() || matches(app, q)) {
                filtered.add(app);
            }
        }
        sortBySection(filtered);

        // 先在局部列表里构造，最后原子替换：避免 ListView 在任何时刻读到"半成品"的行
        // （半成品的行 apps 为空 → 会渲染出一整行空壳/无文字）。
        List<Row> newRows = new ArrayList<>();
        List<String> newSections = new ArrayList<>();
        Map<String, Integer> newSectionFirstRow = new HashMap<>();
        String currentSection = null;
        Row row = null;
        for (Object app : filtered) {
            String section = sectionKey(appName(app));
            if (!section.equals(currentSection)) {
                currentSection = section;
                if (!newSections.contains(section)) {
                    newSections.add(section);
                }
                // 每个字母节开头先插一个"字母标题行"；索引条跳转也定位到它
                newRows.add(new Row(section, false, section));
                newSectionFirstRow.put(section, newRows.size() - 1);
                row = null;
            }
            if (row == null || row.apps.size() == COLUMNS) {
                boolean firstOfSection = row == null;   // 标题行之后的第一行
                row = new Row(section, firstOfSection);
                newRows.add(row);
            }
            row.apps.add(app);
        }
        rows.clear();
        rows.addAll(newRows);
        sections.clear();
        sections.addAll(newSections);
        sectionFirstRow.clear();
        sectionFirstRow.putAll(newSectionFirstRow);

        if (indexBarHost != null) {
            indexBarHost.setVisibility(sections.size() <= 1 ? View.GONE : View.VISIBLE);
            if (indexBar != null) {
                // 跟随昼夜主题设置字母颜色
                indexBar.setLetterColors(labelColor(),
                        isNight() ? 0xFFFF8A80 : 0xFFFF6B6B,
                        isNight() ? 0x33FFFFFF : 0x33000000);
                // # 必须在最前，A-Z 顺序不变
                List<String> letters = new ArrayList<>();
                letters.add(OTHER_SECTION);
                for (String s : sections) {
                    if (!OTHER_SECTION.equals(s)) {
                        letters.add(s);
                    }
                }
                indexBar.setLetters(letters);
            }
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void sortBySection(List<Object> apps) {
        Collections.sort(apps, new Comparator<Object>() {
            @Override
            public int compare(Object a, Object b) {
                String na = appName(a);
                String nb = appName(b);
                int c = sectionKey(na).compareTo(sectionKey(nb));
                if (c != 0) {
                    return c;
                }
                if (na == null || nb == null) {
                    return 0;
                }
                return na.compareToIgnoreCase(nb);
            }
        });
    }

    private void jumpToSection(String section) {
        if (section == null) {
            return;
        }
        Integer row = sectionFirstRow.get(section.toUpperCase());
        if (row != null && listView != null) {
            listView.setSelection(row);
        }
    }

    /** 列表滚动同步索引条焦点时置位：bar 的 setFocusAlphabet 会同步回调
     *  OnFocusedAlphabetChanged，若不拦下会再次 jumpToSection 移动列表，
     *  形成反馈环——向顶滚被顺向加速、向底滚被对抗减速。 */
    private boolean syncingIndexFocus = false;

    private void syncIndexBarFocus(int firstVisibleRow) {
        // 自定义 IndexBarView 不需要同步焦点，触摸即响应
        // 保留此方法以防外部调用
    }

    private String sectionKey(String name) {
        if (TextUtils.isEmpty(name)) {
            return OTHER_SECTION;
        }
        char c = name.charAt(0);
        if (c >= 'a' && c <= 'z') {
            return String.valueOf((char) (c - 32));
        }
        if (c >= 'A' && c <= 'Z') {
            return String.valueOf(c);
        }
        String py = toPinyin(name);
        if (!TextUtils.isEmpty(py)) {
            char p = Character.toUpperCase(py.charAt(0));
            if (p >= 'A' && p <= 'Z') {
                return String.valueOf(p);
            }
        }
        return OTHER_SECTION;
    }

    /** 中文名取拼音首字母（framework 的 android.icu 音译器）。 */
    private String toPinyin(String name) {
        try {
            if (pinyin == null) {
                pinyin = Transliterator.getInstance("Han-Latin; Latin-ASCII");
            }
            return pinyin.transliterate(name);
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean matches(Object app, String lowerQuery) {
        String name = appName(app);
        String pkg = appPkg(app);
        if ((name != null && name.toLowerCase().contains(lowerQuery))
                || (pkg != null && pkg.toLowerCase().contains(lowerQuery))) {
            return true;
        }
        String py = toPinyin(name == null ? "" : name);
        return py != null && py.toLowerCase().contains(lowerQuery);
    }

    private void preloadIcons() {
        // 快照后再交给后台线程：主线程的 loadApps() 会 clear/addAll 这个列表，
        // 直接遍历会抛 ConcurrentModificationException 把目标进程搞崩。
        final List<Object> snapshot = new ArrayList<>(allApps);
        io.execute(() -> {
            for (Object app : snapshot) {
                LauncherIconLoader.get(ctx, appPkg(app), appClassName(app));
            }
            if (snapshot.isEmpty()) {
                return;
            }
            mainHandler.post(() -> {
                // 关键：绝不能在手指按住/拖动时刷新。否则 ListView 会在手势中途重建行、
                // 移除正在被按住的子视图；也会把拖拽预览的位置清零（看着像瞬间弹回、没有动画）。
                if (listTouching || dragInProgress || pinnedAnimating || pinnedPreviewState != -1) {
                    return;
                }
                rebuildPinnedViews();
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
            });
        });
    }

    // ---------------------------------------------------------------- 钉住区视图

    private void rebuildPinnedViews() {
        rebuildPinnedViews(false);
    }

    /** @param animate 仅在 pin() 落子时开 LayoutTransition：插入/位移/被挤出的图标做过渡动画。 */
    private void rebuildPinnedViews(boolean animate) {
        if (pinnedStrip != null) {
            pinnedStrip.setLayoutTransition(animate ? new LayoutTransition() : null);
            pinnedStrip.removeAllViews();
            for (Object app : firstPageApps()) {
                pinnedStrip.addView(buildIconCell(app, PINNED_PAGE_ICON_DP, false, true, true, -1));
            }
        }
        rebuildPinnedGrid();
    }

    private List<Object> firstPageApps() {
        if (!prefs().getBoolean(PREF_SHORTCUT_BAR_TOP5, false)) {
            return pinnedApps;
        }
        try {
            Class<?> utils = Class.forName(
                    "com.zui.freeform.sidebar.utils.SidebarAppUtils", false, targetCl);
            Object top5 = utils.getMethod("generateTop5App", Context.class).invoke(null, ctx);
            if (top5 instanceof List) {
                List<Object> result = new ArrayList<>();
                for (Object app : (List<?>) top5) {
                    if (app != null && result.size() < 5) {
                        result.add(app);
                    }
                }
                if (!result.isEmpty()) {
                    return result;
                }
            }
        } catch (Throwable t) {
            LogHelper.log(LogHelper.VerboseLevel.WARNING,
                    "load shortcut top5 failed: ", String.valueOf(t));
        }
        return pinnedApps;
    }

    /** 按 pinnedApps 重建 B 页钉住网格：每行 COLUMNS 个等宽格子，超出换行；
     *  末尾永远多留一个空槽当作拖拽落点。 */
    private void rebuildPinnedGrid() {
        if (pinnedBar == null) {
            return;
        }
        if (rowGrowAnimator != null) {
            rowGrowAnimator.cancel();
        }
        extraRowH = 0f;
        pinnedBar.removeAllViews();
        pinnedSlots.clear();
        int count = pinnedApps.size();
        int rows = Math.max(1, (count + COLUMNS) / COLUMNS);
        for (int r = 0; r < rows; r++) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.TOP);
            row.setClipChildren(false);   // 让跨行移动的格子能画出本行
            for (int c = 0; c < COLUMNS; c++) {
                FrameLayout slot = new FrameLayout(ctx);
                row.addView(slot, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                pinnedSlots.add(slot);
            }
            pinnedBar.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        for (int i = 0; i < count && i < pinnedSlots.size(); i++) {
            // 与网格行同一规格（GRID_ICON_DP + 标签）；pinIndex=i 让落子时能识别"行内移动"
            pinnedSlots.get(i).addView(
                    buildIconCell(pinnedApps.get(i), GRID_ICON_DP, true, true, false, i),
                    new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    // ---------------------------------------------------------------- 通用视图

    /**
     * @param draggable B 页网格里的格子可以长按拖到顶部钉住行
     */
    private View buildIconCell(Object app, int iconDp, boolean withLabel, boolean draggable,
                               boolean externalDragOnly, int pinIndex) {
        LinearLayout cell = new LinearLayout(ctx);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        // 格子自身的按压反馈（图标+文字那块）是保留的，这是正常且期望的。
        cell.setBackground(!externalDragOnly && allAppsEditMode
            ? editModeCellBackground(dp(10)) : pressedBackground(dp(10)));
        // 结构：间隙|图标|间隙 / 文字铺满整列。
        // 水平只留很小的间隙（块与块之间），剩下的padding都"转移"进块内由图标两侧的空隙体现。
        cell.setPadding(dp(2), dp(3), dp(2), dp(2));

        ImageView icon = new ImageView(ctx);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        cell.addView(icon, new LinearLayout.LayoutParams(dp(iconDp), dp(iconDp)));

        if (withLabel) {
            TextView label = new TextView(ctx);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);   // 比原 14sp 小一号
            label.setTextColor(labelColor());
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(2);                                 // 允许折行（最多两行）
            label.setEllipsize(TextUtils.TruncateAt.END);         // 两行还放不下才省略
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(2);
            cell.addView(label, lp);
        }

        bindIconCell(cell, app, draggable, externalDragOnly, pinIndex);
        return cell;
    }

    /**
     * 更新一个已存在的 cell 的内容（图标/文字/监听），**不增删子视图**。
     * ListView 在布局过程中会调用 getView，此时增删子视图会让行以"空壳/只有图标"的状态被
     * 测量，导致整行文字被挤没、行高错乱。所以复用行时只更新内容，结构保持不变。
     */
    private void bindIconCell(LinearLayout cell, Object app, boolean draggable,
                              boolean externalDragOnly, int pinIndex) {
        boolean internalEditDrag = !externalDragOnly && allAppsEditMode;
        boolean externalDrag = !internalEditDrag;
        cell.setBackground(internalEditDrag
            ? editModeCellBackground(dp(10)) : pressedBackground(dp(10)));
        cell.setSelected(internalEditDrag);
        if (cell.getChildCount() > 0 && cell.getChildAt(0) instanceof ImageView) {
            ImageView image = (ImageView) cell.getChildAt(0);
            image.setScaleX(1f);
            image.setScaleY(1f);
            Bitmap renderedIcon = LauncherIconLoader.get(
                    ctx, appPkg(app), appClassName(app));
            if (renderedIcon != null) {
                image.setImageBitmap(renderedIcon);
            } else {
                Drawable vendorIcon = vendorAppIcon(app);
                if (vendorIcon != null) {
                    image.setImageDrawable(vendorIcon);
                }
            }
        }
        if (cell.getChildCount() > 1 && cell.getChildAt(1) instanceof TextView) {
            String name = appName(app);
            TextView label = (TextView) cell.getChildAt(1);
            label.setTextColor(labelColor());
            label.setText(name == null ? "" : name);
        }
        boolean hasFreeformWindow = hasVisibleFreeformWindow(appPkg(app));
        boolean editMode = !externalDragOnly && allAppsEditMode;
        cell.setAlpha(hasFreeformWindow && !editMode ? 0.38f : 1f);
        cell.setEnabled(!hasFreeformWindow || editMode);
        if (hasFreeformWindow && !editMode) {
            cell.setOnClickListener(null);
            cell.setOnLongClickListener(null);
            cell.setOnDragListener(null);
            return;
        }
        if (internalEditDrag) {
            cell.setOnClickListener(null);
        } else {
            cell.setOnClickListener(v -> {
            if (hasVisibleFreeformWindow(appPkg(app))) {
                cell.setAlpha(0.38f);
                cell.setEnabled(false);
                LogHelper.log(LogHelper.VerboseLevel.INFO,
                        "ignore launch for app already in freeform pkg=", String.valueOf(appPkg(app)));
                return;
            }
            host.launch(app);
            });
        }
        if (draggable) {
            cell.setOnLongClickListener(v -> {
                if (hasVisibleFreeformWindow(appPkg(app)) && !internalEditDrag) {
                    cell.setAlpha(0.38f);
                    cell.setEnabled(false);
                    LogHelper.log(LogHelper.VerboseLevel.INFO,
                            "ignore drag for app already in freeform pkg=", String.valueOf(appPkg(app)));
                    return true;
                }
                draggedPackageForSplit = appPkg(app);
                splitRequestedForDrag = false;
                if (externalDrag) {
                    externalDragCell = v;
                    externalDragInProgress = true;
                    LogHelper.log(LogHelper.VerboseLevel.WARNING, "external drag start pkg=",
                            String.valueOf(appPkg(app)), " cell=", String.valueOf(System.identityHashCode(v)),
                            " pageOnly=", String.valueOf(externalDragOnly));
                    // A 页拖出只用于系统分屏，拿起后原位置立即消失。
                    v.setAlpha(0f);
                } else {
                    dragFromPinned = pinIndex;   // >=0 表示来自钉住行，用于行内移动判定
                    dragInProgress = true;       // 拖拽期间禁止后台刷新重建钉住区
                    LogHelper.log(LogHelper.VerboseLevel.WARNING, "internal drag start pkg=",
                            String.valueOf(appPkg(app)), " pinIndex=", String.valueOf(pinIndex),
                            " cell=", String.valueOf(System.identityHashCode(v)));
                    if (pinIndex >= 0) {
                        // B 页 pinned 内部排序：图标被拿起后，原位置留空
                        pinnedDragCell = v;
                        v.setAlpha(0f);
                    }
                }
                ClipData data = externalDrag
                    ? buildAppDragData(app)
                    : ClipData.newPlainText("app", appPkg(app));
                int dragFlags = externalDrag
                    ? View.DRAG_FLAG_GLOBAL | View.DRAG_FLAG_GLOBAL_URI_READ | 8192
                    : 0;
                ClipData.Item dragItem = data.getItemCount() == 0 ? null : data.getItemAt(0);
                LogHelper.log(LogHelper.VerboseLevel.INFO, "starting drag pkg=",
                    String.valueOf(appPkg(app)), " pageExternalOnly=", String.valueOf(externalDragOnly),
                    " flags=", String.valueOf(dragFlags),
                    " mime=", data.getDescription().getMimeType(0),
                    " intentSender=", String.valueOf(dragItem != null && dragItem.getIntentSender() != null));
                boolean started = startSystemDrag(v, data, app, dragFlags);
                if (!started) {
                    v.setAlpha(1f);
                    if (externalDrag) {
                        externalDragCell = null;
                        externalDragInProgress = false;
                    } else {
                        pinnedDragCell = null;
                        dragFromPinned = -1;
                        dragInProgress = false;
                    }
                        LogHelper.log(LogHelper.VerboseLevel.ERROR,
                            "startDragAndDrop returned false pkg=", String.valueOf(appPkg(app)),
                            " flags=", String.valueOf(dragFlags));
                }
                return true;
            });
            if (externalDrag) {
                cell.setOnDragListener((v, event) -> {
                    LogHelper.log(LogHelper.VerboseLevel.WARNING, "external cell drag action=",
                            String.valueOf(event.getAction()), " result=", String.valueOf(event.getResult()),
                            " external=", String.valueOf(externalDragInProgress),
                            " sameCell=", String.valueOf(externalDragCell == v));
                    if (event.getAction() == DragEvent.ACTION_DRAG_STARTED) {
                        return true;
                    }
                    if (event.getAction() == DragEvent.ACTION_DRAG_ENDED) {
                        if (externalDragCell == v) {
                            v.setAlpha(1f);
                            externalDragCell = null;
                            externalDragInProgress = false;
                        }
                        return true;
                    }
                    return true;
                });
            } else {
                cell.setOnDragListener(null);
            }
        } else {
            cell.setOnLongClickListener(null);
            cell.setOnDragListener(null);
        }
    }

    /** 原版 SidebarAppUtils 已经把主题色应用到 AppInfo.mAppIcon；优先复用它。 */
    private Drawable vendorAppIcon(Object app) {
        if (mGetAppIcon == null || app == null) {
            return null;
        }
        try {
            Object drawable = mGetAppIcon.invoke(app);
            return drawable instanceof Drawable ? (Drawable) drawable : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean hasVisibleFreeformWindow(String packageName) {
        if (TextUtils.isEmpty(packageName)) {
            return false;
        }
        try {
                ActivityManager activityManager =
                    (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
                List<ActivityManager.RunningTaskInfo> tasks =
                    activityManager.getRunningTasks(32);
            for (ActivityManager.RunningTaskInfo task : tasks) {
                if (!task.isVisible()
                            || taskWindowingMode(task) != 5) {
                    continue;
                }
                if (task.topActivity != null
                        && packageName.equals(task.topActivity.getPackageName())) {
                    return true;
                }
                if (task.baseActivity != null
                        && packageName.equals(task.baseActivity.getPackageName())) {
                    return true;
                }
            }
        } catch (Throwable t) {
            LogHelper.log(LogHelper.VerboseLevel.WARNING,
                    "freeform task check failed: ", String.valueOf(t));
        }
        return false;
    }

    private int taskWindowingMode(ActivityManager.RunningTaskInfo task) {
        try {
            Method method = task.getClass().getMethod("getWindowingMode");
            Object value = method.invoke(task);
            return value instanceof Integer ? (Integer) value : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /**
     * ZUI adds a hidden system-drag overload. The regular overload is intercepted by
     * MotoDragDropHelper and returns false before WindowManager sees the drag.
     */
    private boolean startSystemDrag(View source, ClipData data, Object localState, int dragFlags) {
        View.DragShadowBuilder shadow = new View.DragShadowBuilder(source);
        try {
            Method method = View.class.getMethod("startDragAndDrop", ClipData.class,
                    View.DragShadowBuilder.class, Object.class, int.class, boolean.class);
            Object result = method.invoke(source, data, shadow, localState, dragFlags, true);
                boolean started = result instanceof Boolean && (Boolean) result;
                LogHelper.log(LogHelper.VerboseLevel.INFO, "system drag result=",
                    String.valueOf(started), " flags=", String.valueOf(dragFlags));
                return started;
        } catch (NoSuchMethodException e) {
            return source.startDragAndDrop(data, shadow, localState, dragFlags);
        } catch (Throwable t) {
            LogHelper.log(LogHelper.VerboseLevel.ERROR, "system drag start failed: ",
                    String.valueOf(t));
            return false;
        }
    }

    /** 构造 SystemUI Shell 可识别的应用启动拖拽载荷。 */
    private ClipData buildAppDragData(Object app) {
        String pkg = appPkg(app);
        String cls = appClassName(app);
        Intent launch = new Intent(Intent.ACTION_MAIN);
        launch.addCategory(Intent.CATEGORY_LAUNCHER);
        if (pkg != null && cls != null) {
            launch.setComponent(new ComponentName(pkg, cls));
        } else if (pkg != null) {
            launch.setPackage(pkg);
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pending = buildAppPendingIntent(app);
        Intent itemIntent = new Intent(launch);
        itemIntent.putExtra("android.intent.extra.PENDING_INTENT", pending);
        itemIntent.putExtra(Intent.EXTRA_USER,
            UserHandle.getUserHandleForUid(android.os.Process.myUid()));
        ClipData.Item item = new ClipData.Item.Builder()
            .setIntent(itemIntent)
            .setIntentSender(pending.getIntentSender())
            .build();
        return new ClipData(
            new ClipDescription("app", new String[]{"application/vnd.android.activity"}),
            item);
    }

    private PendingIntent buildAppPendingIntent(Object app) {
        String pkg = appPkg(app);
        String cls = appClassName(app);
        Intent launch = new Intent(Intent.ACTION_MAIN);
        launch.addCategory(Intent.CATEGORY_LAUNCHER);
        if (pkg != null && cls != null) {
            launch.setComponent(new ComponentName(pkg, cls));
        } else if (pkg != null) {
            launch.setPackage(pkg);
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(ctx, pkg == null ? 0 : pkg.hashCode(), launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private final class RowAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return null;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).header != null ? 1 : 0;
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        @Override
        public boolean isEnabled(int position) {
            return rows.get(position).header == null;   // 字母标题行不可点/不可长按
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Row data = rows.get(position);
            if (data.header != null) {
                // 字母标题行：只显示一个字母（如 A / B / #）
                TextView header;
                if (convertView instanceof TextView) {
                    header = (TextView) convertView;
                } else {
                    header = new TextView(ctx);
                    header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
                    header.setTextColor(labelColor());
                    header.setTypeface(header.getTypeface(), android.graphics.Typeface.BOLD);
                    header.setGravity(Gravity.START);
                    header.setIncludeFontPadding(false);   // 压掉字体自带的上下留白
                }
                header.setText(data.header);
                header.setPadding(0, dp(2), 0, dp(1));     // 取消节间距 + 压缩标题行高度
                return header;
            }
            LinearLayout row;
            if (convertView instanceof LinearLayout) {
                row = (LinearLayout) convertView;
            } else {
                row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                // 顶部对齐：同排图标对齐，折行的文字向下延伸
                row.setGravity(Gravity.TOP);
                for (int i = 0; i < COLUMNS; i++) {
                    FrameLayout slot = new FrameLayout(ctx);
                    row.addView(slot, new LinearLayout.LayoutParams(0,
                            ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                }
            }
            // 组内行距 8dp；节首行紧贴上面的字母标题，只留 4dp。
            // 用 padding 做间距，绝不要改 row 的 LayoutParams：ListView 自己管理 item 的
            // AbsListView.LayoutParams，覆盖成 LinearLayout.LayoutParams 会破坏它的测量/回收，
            // 偶发把整行文字挤没（且每次受影响的行不同）。
            row.setPadding(0, data.firstOfSection ? dp(4) : dp(8), 0, 0);
            for (int i = 0; i < COLUMNS; i++) {
                FrameLayout slot = (FrameLayout) row.getChildAt(i);
                if (i >= data.apps.size()) {
                    slot.removeAllViews();
                    continue;
                }
                Object app = data.apps.get(i);
                LinearLayout cell = null;
                if (slot.getChildCount() == 1 && slot.getChildAt(0) instanceof LinearLayout) {
                    cell = (LinearLayout) slot.getChildAt(0);
                }
                if (cell != null && cell.getChildCount() >= 2) {
                    // 复用：只更新内容，结构不动（避免布局中增删子视图导致整行文字被挤没）
                    bindIconCell(cell, app, true, false, -1);
                } else {
                    slot.removeAllViews();
                    cell = (LinearLayout) buildIconCell(app, GRID_ICON_DP, true, true, false, -1);
                    slot.addView(cell, new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                }
                // 键盘导航高亮：格子是复用的，无论亮不亮都要显式设一遍，否则会把状态带错行
                cell.setSelected(allAppsEditMode
                    || (!kbNavPinned && position == kbRow && i == kbCol));
            }
            return row;
        }
    }

    // ---------------------------------------------------------------- 工具

    private String appName(Object app) {
        if (appNameCache.containsKey(app)) {
            return appNameCache.get(app);
        }
        String name = invokeString(mGetAppName, app);
        if (TextUtils.isEmpty(name)) {
            // vendor 的 getAppName 偶尔会返回 null/空（此时格子里只有图标没有文字、
            // 分组也会错乱）。退回 PackageManager 直接取 label，保证永远拿得到名字。
            String pkg = appPkg(app);
            if (pkg != null) {
                try {
                    PackageManager pm = ctx.getPackageManager();
                    ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    CharSequence label = pm.getApplicationLabel(ai);
                    if (!TextUtils.isEmpty(label)) {
                        name = label.toString();
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        appNameCache.put(app, name);
        return name;
    }

    private String appPkg(Object app) {
        if (!appPkgCache.containsKey(app)) {
            appPkgCache.put(app, invokeString(mGetPkgName, app));
        }
        return appPkgCache.get(app);
    }

    private String appClassName(Object app) {
        if (!appClassCache.containsKey(app)) {
            appClassCache.put(app, invokeString(mGetClassName, app));
        }
        return appClassCache.get(app);
    }

    /** 钉住列表的存储键，与厂商一致：pkg:userId */
    private String appKey(Object app) {
        String pkg = appPkg(app);
        if (pkg == null || mGetUser == null) {
            return null;
        }
        try {
            Object user = mGetUser.invoke(app);
            if (user == null) {
                return null;
            }
            Object id = user.getClass().getMethod("getIdentifier").invoke(user);
            return pkg + ":" + id;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String invokeString(Method m, Object target) {
        if (m == null || target == null) {
            return null;
        }
        try {
            Object v = m.invoke(target);
            return v == null ? null : String.valueOf(v);
        } catch (Throwable t) {
            return null;
        }
    }

    private int panelHeightPx() {
        int screen = ctx.getResources().getDisplayMetrics().heightPixels;
        return Math.min(dp(ALL_APPS_MAX_HEIGHT_DP), screen - dp(160));
    }

    /** 每次重新取厂商动态背景，让 ZUI 自己的主题资源决定面板颜色。 */
    private Drawable panelBackground() {
        int id = ctx.getResources().getIdentifier(
                "popup_background_zui", "drawable", ctx.getPackageName());
        if (id != 0) {
            try {
                return ctx.getResources().getDrawable(id);
            } catch (Throwable ignored) {
            }
        }
        return roundedRect(panelFallbackColor(), dp(PANEL_CORNER_DP));
    }

    public Drawable panelBackgroundForHost() {
        return panelBackground();
    }

    private int dp(int value) {
        return (int) (value * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---------------------------------------------------------------- 昼夜配色

    private boolean isNight() {
        int mode = ctx.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    /** 应用名标签：白天 #333333 / 夜间 #e0e0f0（原版 grid_pkg_name_color 两个模式的值）。 */
    private int labelColor() {
        return isNight() ? 0xFFE0E0F0 : 0xFF202124;
    }

    /** 搜索框底色：白天黑 8% / 夜间白 8%。 */
    private int searchBgColor() {
        return isNight() ? 0x14FFFFFF : 0x14000000;
    }

    /** 拖拽落点/插入位高亮与按压反馈：白天黑 12% / 夜间白 12%。 */
    private int overlayColor() {
        return isNight() ? 0x1FFFFFFF : 0x1F000000;
    }

    /** 面板兜底底色（popup_background_zui 取不到时）：白天 #f7f7f7 / 夜间 #2b2b2b。 */
    private int panelFallbackColor() {
        return isNight() ? 0xFF2B2B2B : 0xFFF7F7F7;
    }

    private static GradientDrawable roundedRect(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private StateListDrawable pressedBackground(int radius) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, roundedRect(overlayColor(), radius));
        // 键盘导航的高亮光标（搜索框按 Enter 后选中某个格子时）
        s.addState(new int[]{android.R.attr.state_selected}, roundedRect(overlayColor(), radius));
        s.addState(new int[]{}, roundedRect(0x00000000, radius));
        return s;
    }

    private StateListDrawable editModeButtonBackground() {
        int radius = dp(20);
        int accent = systemAccentColor();
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed},
                roundedRect(withAlpha(accent, 0x38), radius));
        states.addState(new int[]{android.R.attr.state_selected},
                roundedRect(withAlpha(accent, 0x38), radius));
        states.addState(new int[]{}, roundedRect(0x00000000, radius));
        return states;
    }

    private StateListDrawable editModeCellBackground(int radius) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed},
                roundedRect(isNight() ? 0x45FFFFFF : 0x35000000, radius));
        states.addState(new int[]{android.R.attr.state_selected},
                roundedRect(isNight() ? 0x28FFFFFF : 0x18000000, radius));
        states.addState(new int[]{}, roundedRect(0x00000000, radius));
        return states;
    }

    private int systemAccentColor() {
        try {
            int id = android.R.color.system_accent1_600;
            return ctx.getResources().getColor(id);
        } catch (Throwable ignored) {
            return isNight() ? 0xFF90CAF9 : 0xFF1976D2;
        }
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    // ---------------------------------------------------------------- 自定义索引条

    /**
     * 自定义索引条 View，完全绕过 vendor 的 AlphabeticIndexBar 以避免 BadTokenException。
     * 手动绘制 A-Z + # 字母，直接处理触摸事件跳转到对应章节。
     * 完全照抄原版行为，只是把 # 排在第一个。
     */
    /** ScrollView，但高度上限被限制：内容不足时按内容高度，不会撑满。 */
    private static class MaxHeightScrollView extends android.widget.ScrollView {
        private int maxHeight = 0;

        MaxHeightScrollView(Context context) {
            super(context);
        }

        void setMaxHeight(int h) {
            maxHeight = h;
            requestLayout();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            if (maxHeight > 0 && getMeasuredHeight() > maxHeight) {
                setMeasuredDimension(getMeasuredWidth(), maxHeight);
            }
        }
    }

    private static class IndexBarView extends View {
        private final List<String> letters = new ArrayList<>();
        private int itemHeight = 0;
        private int focusedIndex = -1;
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint focusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private OnLetterChangedListener listener;
        private final Context context;
        /** 未选中/选中字母色、聚焦圈色（跟随昼夜主题，由外部设置）。 */
        private int normalColor = 0xFF333333;
        private int activeColor = 0xFFFF6B6B;
        private int focusColor = 0x33000000;

        public interface OnLetterChangedListener {
            void onLetterChanged(String letter);
        }

        public IndexBarView(Context context) {
            super(context);
            this.context = context;
            textPaint.setTextSize(dp(12));
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setColor(normalColor);
            focusPaint.setColor(focusColor);
        }

        /** 跟随昼夜主题设置字母/聚焦圈颜色。 */
        public void setLetterColors(int normal, int active, int focus) {
            normalColor = normal;
            activeColor = active;
            focusColor = focus;
            focusPaint.setColor(focus);
            invalidate();
        }

        private int dp(int value) {
            return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
        }

        public void setLetters(List<String> letters) {
            this.letters.clear();
            this.letters.addAll(letters);
            // # 必须在最前，A-Z 顺序不变
            if (this.letters.contains(OTHER_SECTION)) {
                this.letters.remove(OTHER_SECTION);
                this.letters.add(0, OTHER_SECTION);
            }
            recalcItemHeight();
            requestLayout();
            invalidate();
        }

        /** 字母均分整条高度（不按 27 个槽位算，否则只显示少数几个字母时会挤在顶部）。
         *  末尾多留一格（除以 size+1）当"透明垫子"，免得最后一个字母顶到最底下。 */
        private void recalcItemHeight() {
            int h = getHeight();
            if (h > 0 && !letters.isEmpty()) {
                itemHeight = Math.max(dp(8), h / (letters.size() + 1));
            }
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            recalcItemHeight();
        }

        public void setItemHeight(int height) {
            itemHeight = height;
            requestLayout();
        }

        public int getItemHeightForLayout() {
            return itemHeight;
        }

        public void setOnLetterChangedListener(OnLetterChangedListener listener) {
            this.listener = listener;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int widthMode = MeasureSpec.getMode(widthMeasureSpec);
            int widthSize = MeasureSpec.getSize(widthMeasureSpec);
            
            // 完全照抄原版：宽度固定为 48dp
            int desiredWidth = dp(48);
            
            if (widthMode == MeasureSpec.EXACTLY) {
                desiredWidth = widthSize;
            } else if (widthMode == MeasureSpec.AT_MOST) {
                desiredWidth = Math.min(desiredWidth, widthSize);
            }
            
            setMeasuredDimension(desiredWidth, MeasureSpec.getSize(heightMeasureSpec));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (letters.isEmpty() || itemHeight == 0) return;

            int cx = getWidth() / 2;

            for (int i = 0; i < letters.size(); i++) {
                String letter = letters.get(i);
                int cy = i * itemHeight + itemHeight / 2;

                // 绘制选中背景（圆形）
                if (i == focusedIndex) {
                    canvas.drawCircle(cx, cy, itemHeight / 2.5f, focusPaint);
                }

                // 绘制文字：垂直居中要用 cy - (descent+ascent)/2。
                // 原来写成 cy + (...) 会把文字整体抬高，顶部那个 "#" 就顶出边界了。
                textPaint.setColor(i == focusedIndex ? activeColor : normalColor);
                canvas.drawText(letter, cx,
                        cy - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int action = event.getAction();
            int y = (int) event.getY();
            int index = y / itemHeight;

            switch (action) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE:
                    if (index >= 0 && index < letters.size()) {
                        if (focusedIndex != index) {
                            focusedIndex = index;
                            String letter = letters.get(index);
                            if (listener != null) {
                                listener.onLetterChanged(letter);
                            }
                            invalidate();
                        }
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    focusedIndex = -1;
                    invalidate();
                    return true;
            }
            return super.onTouchEvent(event);
        }
    }
}
