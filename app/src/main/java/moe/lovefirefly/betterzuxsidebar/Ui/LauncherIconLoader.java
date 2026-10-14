package moe.lovefirefly.betterzuxsidebar.Ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.util.LruCache;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import moe.lovefirefly.betterzuxsidebar.Utils.LogHelper;

/**
 * 复用桌面（com.zui.launcher = Launcher3）的图标渲染链：
 * IconProvider.getZuiIcon(ActivityInfo) → BaseIconFactory.createIconBitmap()
 * 好处是遮罩（RRO config_icon_mask）、缩放规则、图标包（IconThemeController）、
 * 以及 ZUI 的日历/时钟动态图标都与桌面一致，切换主题时也自动跟随。
 */
public final class LauncherIconLoader {
    private static final String LAUNCHER_PKG = "com.zui.launcher";
    private static final String PROVIDER_CLS = "com.android.launcher3.icons.IconProvider";
    private static final String FACTORY_CLS = "com.android.launcher3.icons.BaseIconFactory";
    private static final String BITMAP_INFO_CLS = "com.android.launcher3.icons.BitmapInfo";
    /** 桌面 ThemeManager.f4659g 就是它（单色主题图标生成器）。 */
    private static final String MONO_CTRL_CLS =
            "com.android.launcher3.icons.mono.MonoIconThemeController";
    /**
     * ZUI 设置“将系统主题色应用到桌面图标”对应的开关。
     * 桌面自己读的是私有 prefs（com.zui.launcher.prefs / LauncherPrefs "themed_icons"，
     * 跨包读不到），系统设置的镜像就是 Settings.System 的这个键。
     */
    private static final String SYSTEM_THEMED_KEY = "icon_themed_enable";

    /** launcher 的 Context（createPackageContext 包装）。 */
    private static Context sLauncherCtx;
    private static Context sHostContext;
    private static Class<?> sMonoCls;
    private static Method sGetZuiIcon;
    private static Method sCreateIconBitmap;
    private static Method sCreateIconBitmap3;
    /** BaseIconFactory.normalizeAndWrapToAdaptiveIcon(Drawable, float[])。 */
    private static Method sNormalize;
    /** ZUI 专有：createBadgedIconBitmapZui(Drawable, IconOptions) —— 内含归一化 + 套遮罩。 */
    private static Method sCreateZuiBitmap;
    /** IconThemeController.createThemedAdaptiveIcon(Context, AdaptiveIconDrawable, BitmapInfo)。 */
    private static Method sCreateThemedIcon;
    private static Method sBitmapInfoThemedBitmap;
    private static Method sBitmapInfoNewIcon;
    private static Class<?> sMonoFactoryCls;
    private static java.lang.reflect.Constructor<?> sMonoFactoryConstructor;
    private static Method sMonoFactoryWrap;
    /** BitmapInfo.icon（公有字段）。 */
    private static Field sBitmapInfoIcon;
    /** BaseIconFactory.mThemeController —— 桌面靠它决定是否生成 mono 主题图标。 */
    private static Field sThemeControllerField;
    private static int sDpi;
    private static int sIconSizePx;
    /** 对应 BaseIconFactory 第 4 个构造参（桌面传 GraphicsUtils.supportChangeShape）。
     *  false 时非自适应图标会原样返回（不套系统遮罩→保持方形）；true 才会包成自适应图标。 */
    private static boolean sSupportChangeShape;
    private static boolean sReady;
    private static boolean sThemedEnabled;
    private static volatile boolean sFollowSystemTheme = true;
    private static long sThemedCheckedAt;
    private static volatile int sFactoryGeneration;
    private static volatile int sCacheGeneration;
    private static String sLastThemeSignature;

    /**
     * BaseIconFactory / IconProvider / MonoIconThemeController 都是有状态且**非线程安全**的：
     * BaseIconFactory 内部持有一个共享 Canvas 成员（f4825c），桌面靠 LauncherIcons 对象池
     * 单线程使用。早先共享单例，被主线程 + 后台线程并发调用时 Canvas 状态被踩坏，
     * 直接 native SIGSEGV（SkCanvas::clipPath / save 崩在 libhwui）。所以每线程一套。
     */
    private static final ThreadLocal<Object[]> TL_FACTORY = new ThreadLocal<>();
    /** 归一化缩放系数（IconNormalizer.ICON_VISIBLE_AREA_FACTOR）。 */
    private static final float ICON_VISIBLE_AREA_FACTOR = 0.7894737f;
    private static final LruCache<String, Bitmap> CACHE = new LruCache<>(256);

    private LauncherIconLoader() {
    }

    public static synchronized boolean init(Context context, int iconSizePx) {
        if (sReady && sIconSizePx == iconSizePx) {
            return true;
        }
        try {
            sHostContext = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
            Context launcher = createLauncherContext(context);
            ClassLoader cl = launcher.getClassLoader();
            Class<?> providerCls = Class.forName(PROVIDER_CLS, true, cl);
            Class<?> factoryCls = Class.forName(FACTORY_CLS, true, cl);

            sLauncherCtx = launcher;
            sDpi = context.getResources().getConfiguration().densityDpi;
            sIconSizePx = iconSizePx;
            // 桌面 IconFactory.obtain() 会给 BaseIconFactory 传 supportChangeShape：
            // 它决定“非自适应图标是否被包成自适应图标并套上系统遮罩”（即圆角/形状）。
            // 之前一直传 false，所以非自适应图标不套遮罩、保持方形。
            sSupportChangeShape = computeSupportChangeShape(context);

            // ---- 必需：拿图标源 + 渲染 ----
            sGetZuiIcon = providerCls.getMethod("getZuiIcon", ActivityInfo.class, int.class);
            sCreateIconBitmap = factoryCls.getMethod("createIconBitmap", Drawable.class, float.class);

            // ---- 增强项：失败就降级，不影响主流程 ----
            // 照抄 ZuiLauncher：createBadgedIconBitmapZui 内含 normalizeAndWrapToAdaptiveIcon
            // （图标归一化 + 自适应遮罩 + 图标包），比裸 createIconBitmap 更贴近桌面。
            try {
                Class<?> iconOptionsCls = Class.forName(
                        "com.android.launcher3.icons.BaseIconFactory$IconOptions", false, cl);
                sCreateZuiBitmap = factoryCls.getMethod(
                        "createBadgedIconBitmapZui", Drawable.class, iconOptionsCls);
                sBitmapInfoIcon = Class.forName(BITMAP_INFO_CLS, false, cl).getField("icon");
                Class<?> bitmapInfoCls = Class.forName(BITMAP_INFO_CLS, false, cl);
                sBitmapInfoThemedBitmap = bitmapInfoCls.getMethod("getThemedBitmap");
                sBitmapInfoNewIcon = bitmapInfoCls.getMethod("newIcon", Context.class, int.class);
                sNormalize = factoryCls.getMethod(
                        "normalizeAndWrapToAdaptiveIcon", Drawable.class, float[].class);
                sCreateIconBitmap3 = factoryCls.getMethod(
                        "createIconBitmap", Drawable.class, float.class, int.class);
            } catch (Throwable t) {
                sCreateZuiBitmap = null;
                sBitmapInfoIcon = null;
                sBitmapInfoThemedBitmap = null;
                sBitmapInfoNewIcon = null;
                sNormalize = null;
                sCreateIconBitmap3 = null;
                LogHelper.log(LogHelper.VerboseLevel.WARNING,
                        "launcher icon enhancers unavailable: ", String.valueOf(t));
            }

            // ---- 主题图标（“将系统主题色应用到桌面图标”）----
            // 桌面：ThemeManager.IconControllerFactory 在 prefs("themed_icons")==true 时
            // 返回 MonoIconThemeController，注入 BaseIconFactory.mThemeController，
            // createBadgedIconBitmapZui() 便自动 setThemedBitmap()。这里照做一份。
            try {
                Class<?> infoCls = Class.forName(BITMAP_INFO_CLS, true, cl);
                sThemeControllerField = findThemeControllerField(factoryCls, cl);
                sThemeControllerField.setAccessible(true);
                sMonoCls = Class.forName(MONO_CTRL_CLS, true, cl);
                sCreateThemedIcon = Class.forName(
                                "com.android.launcher3.icons.IconThemeController", false, cl)
                        .getMethod("createThemedAdaptiveIcon",
                                Context.class, AdaptiveIconDrawable.class, infoCls);
                        sMonoFactoryCls = Class.forName(
                            "com.android.launcher3.icons.MonochromeIconFactory", false, cl);
                        sMonoFactoryConstructor = sMonoFactoryCls.getConstructor(int.class);
                        sMonoFactoryWrap = sMonoFactoryCls.getMethod("wrap",
                            AdaptiveIconDrawable.class, Path.class, Float.class);
                LogHelper.log(LogHelper.VerboseLevel.INFO, "themed icon controller available");
            } catch (Throwable t) {
                sThemeControllerField = null;
                sMonoCls = null;
                sCreateThemedIcon = null;
                sMonoFactoryCls = null;
                sMonoFactoryConstructor = null;
                sMonoFactoryWrap = null;
                LogHelper.log(LogHelper.VerboseLevel.WARNING,
                        "themed icon controller unavailable: ", String.valueOf(t));
            }

            TL_FACTORY.remove();
            if (acquire() == null) {
                sReady = false;
                return false;
            }
            CACHE.evictAll();
            sLastThemeSignature = themeCacheSignature(context);
            sReady = true;
            LogHelper.log(LogHelper.VerboseLevel.INFO,
                    "LauncherIconLoader ready dpi=", String.valueOf(sDpi),
                    " size=", String.valueOf(iconSizePx));
            return true;
        } catch (Throwable t) {
            sReady = false;
            LogHelper.log(LogHelper.VerboseLevel.ERROR, "LauncherIconLoader init failed: ", String.valueOf(t));
            return false;
        }
    }

    public static Bitmap get(Context context, String pkgName, String className) {
        String key = pkgName + '/' + className + '/' + themeCacheSignature(context);
        Bitmap cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        int generation = sCacheGeneration;
        Bitmap bitmap = render(context, pkgName, className);
        if (bitmap != null && generation == sCacheGeneration) {
            CACHE.put(key, bitmap);
        }
        return bitmap;
    }

    /** 仅在系统主题图标开关开启时使用 Launcher 的主题渲染结果。 */
    public static Bitmap getIfSystemThemed(Context context, String pkgName, String className) {
        if (!sFollowSystemTheme || !isThemedIconEnabled(context)) {
            return null;
        }
        return get(context, pkgName, className);
    }

    public static void setFollowSystemTheme(boolean follow) {
        boolean changed = sFollowSystemTheme != follow;
        sFollowSystemTheme = follow;
        sThemedCheckedAt = 0L;
        CACHE.evictAll();
        if (changed) {
            rebuildFactory();
        }
        LogHelper.log(LogHelper.VerboseLevel.INFO, "theme follow system=", String.valueOf(follow));
    }

    public static void clear() {
        clear(sHostContext);
    }

    /** 清理图标缓存；只有主题签名变化时才重建有状态的 Launcher 工厂。 */
    public static void clear(Context context) {
        sCacheGeneration++;
        sThemedCheckedAt = 0L;
        CACHE.evictAll();
        Context host = context != null ? context : sHostContext;
        if (host != null) {
            try {
                sLauncherCtx = createLauncherContext(host);
            } catch (Throwable t) {
                LogHelper.log(LogHelper.VerboseLevel.WARNING,
                        "refresh launcher context failed: ", String.valueOf(t));
            }
        }
        String currentSignature = host == null ? null : themeCacheSignature(host);
        boolean themeChanged = !same(sLastThemeSignature, currentSignature);
        sLastThemeSignature = currentSignature;
        if (themeChanged) {
            rebuildFactory();
        }
        LogHelper.log(LogHelper.VerboseLevel.INFO, "icon cache cleared generation=",
            String.valueOf(sFactoryGeneration), " themeChanged=", String.valueOf(themeChanged));
    }

    private static boolean same(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static synchronized void rebuildFactory() {
        sFactoryGeneration++;
        TL_FACTORY.remove();
        sReady = false;
        if (sHostContext != null && sIconSizePx > 0) {
            init(sHostContext, sIconSizePx);
        }
    }

    private static Context createLauncherContext(Context context) throws Exception {
        return new ContextWrapper(context.createPackageContext(LAUNCHER_PKG,
                Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY)) {
            @Override
            public Context getApplicationContext() {
                return this;
            }
        };
    }

    public static String diagnostics() {
        return "factory_generation=" + sFactoryGeneration
                + ", cache_generation=" + sCacheGeneration
                + ", themed_enabled=" + sThemedEnabled
                + ", follow_system=" + sFollowSystemTheme
                + ", themed_controller=" + (sThemeControllerField != null && sMonoCls != null);
    }

    private static Bitmap render(Context context, String pkgName, String className) {
        Object[] holder = sReady ? acquire() : null;
        Drawable source = null;
        try {
            if (holder != null && className != null) {
                ActivityInfo info = context.getPackageManager()
                        .getActivityInfo(new ComponentName(pkgName, className), 0);
                source = (Drawable) sGetZuiIcon.invoke(holder[0], info, sDpi);
            }
        } catch (Throwable ignored) {
            // 查不到 ActivityInfo 时退回应用图标
        }
        try {
            if (source == null) {
                source = context.getPackageManager().getApplicationIcon(pkgName);
            }
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
        try {
            if (holder != null) {
                Object factory = holder[1];
                // ① 走桌面管线 createBadgedIconBitmapZui（归一化 + 自适应遮罩 + 主题位图）
                if (sCreateZuiBitmap != null && sBitmapInfoIcon != null) {
                    Object bitmapInfo = sCreateZuiBitmap.invoke(factory, source, null);
                    if (bitmapInfo != null) {
                        boolean hasLauncherThemedBitmap = sBitmapInfoThemedBitmap != null
                                && sBitmapInfoThemedBitmap.invoke(bitmapInfo) != null;
                        if (hasLauncherThemedBitmap) {
                            Bitmap themed = renderThemed(holder, bitmapInfo, source, context);
                            if (themed != null) {
                                return themed;
                            }
                        }
                        // 主题渲染失败时保留普通图标。
                        // 注意：不能用 BitmapInfo.newIcon() —— 它返回的 ThemedBitmap
                        // 是 hardware bitmap 的 Drawable，画到软件 Canvas 会直接报错；
                        // 改用 createThemedAdaptiveIcon() 拼出自适应图标再走 createIconBitmap()。
                        Object bmp = sBitmapInfoIcon.get(bitmapInfo);
                        if (bmp instanceof Bitmap) {
                            return (Bitmap) bmp;
                        }
                    }
                }
                // ② 退一步：原始的 createIconBitmap
                Object bitmap = sCreateIconBitmap.invoke(factory, source, 1.0f);
                if (bitmap instanceof Bitmap) {
                    return (Bitmap) bitmap;
                }
            }
            return drawableToBitmap(source, sIconSizePx > 0 ? sIconSizePx : 128);
        } catch (Throwable t) {
            LogHelper.log(LogHelper.VerboseLevel.WARNING,
                    "render icon failed ", pkgName, ": ", String.valueOf(t));
            return null;
        }
    }

    /** 只返回 Launcher 已经确认存在的 themedBitmap；绝不强制把普通图标染色。 */
    private static Bitmap renderThemedOnly(Context context, String pkgName, String className) {
        Object[] holder = sReady ? acquire() : null;
        Drawable source = loadSourceIcon(context, holder, pkgName, className);
        if (holder == null || source == null || sCreateZuiBitmap == null
                || sBitmapInfoThemedBitmap == null || sBitmapInfoNewIcon == null) {
            return null;
        }
        try {
            Object bitmapInfo = sCreateZuiBitmap.invoke(holder[1], source, null);
            if (bitmapInfo == null || sBitmapInfoThemedBitmap.invoke(bitmapInfo) == null) {
                return null;
            }
            Drawable themed = (Drawable) sBitmapInfoNewIcon.invoke(bitmapInfo, sLauncherCtx, 1);
            return themed == null ? null : drawableToBitmap(themed,
                    sIconSizePx > 0 ? sIconSizePx : 128);
        } catch (Throwable t) {
            LogHelper.log(LogHelper.VerboseLevel.WARNING,
                    "render existing themed icon failed: ", String.valueOf(t));
            return null;
        }
    }

    private static Drawable loadSourceIcon(Context context, Object[] holder,
                                           String pkgName, String className) {
        Drawable source = null;
        try {
            if (holder != null && className != null) {
                ActivityInfo info = context.getPackageManager()
                        .getActivityInfo(new ComponentName(pkgName, className), 0);
                source = (Drawable) sGetZuiIcon.invoke(holder[0], info, sDpi);
            }
        } catch (Throwable ignored) {
        }
        if (source == null) {
            try {
                source = context.getPackageManager().getApplicationIcon(pkgName);
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return source;
    }

    private static Bitmap drawableToBitmap(Drawable drawable, int size) {
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, size, size);
        drawable.draw(canvas);
        return bitmap;
    }

    /**
     * 复现 GraphicsUtils.supportChangeShape(context)：
     *   1) 开了“将系统主题色应用到桌面图标” → true；
     *   2) 否则看 persist.sys.zui.theme == "mostbeautiful"。
     * （桌面读的是 launcher 私有 prefs＋ShapeIconManager，跨包拿不到；但上面两个条件
     *  与主题开关/系统属性完全等价，且本机两者都成立。）
     */
    private static boolean computeSupportChangeShape(Context context) {
        try {
            if (Settings.System.getInt(context.getContentResolver(), SYSTEM_THEMED_KEY, 0) == 1) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> spCls = Class.forName("android.os.SystemProperties");
            Object theme = spCls.getMethod("get", String.class, String.class)
                    .invoke(null, "persist.sys.zui.theme", "");
            return "mostbeautiful".equals(theme);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 取当前线程的 (provider, factory, controller)。
     * BaseIconFactory 持有共享 Canvas 等可变状态，非线程安全（桌面靠 LauncherIcons 对象池
     * 单线程使用）。早先共享单例时，主线程与后台线程并发调用会把 Canvas 踩坏，
     * native SIGSEGV 崩在 SkCanvas。所以每线程自建一套。
     */
    private static Object[] acquire() {
        Object[] holder = TL_FACTORY.get();
        if (holder != null && holder.length > 3
                && holder[3] instanceof Integer
                && ((Integer) holder[3]) == sFactoryGeneration) {
            return holder;
        }
        TL_FACTORY.remove();
        try {
            Context launcher = sLauncherCtx;
            ClassLoader cl = launcher.getClassLoader();
            Class<?> providerCls = Class.forName(PROVIDER_CLS, true, cl);
            Class<?> factoryCls = Class.forName(FACTORY_CLS, true, cl);
            Object provider = providerCls.getConstructor(Context.class).newInstance(launcher);
            Object factory;
            try {
                factory = factoryCls
                        .getConstructor(Context.class, int.class, int.class, boolean.class)
                        .newInstance(launcher, sDpi, sIconSizePx, sSupportChangeShape);
            } catch (NoSuchMethodException e) {
                factory = factoryCls.getConstructor(Context.class, int.class, int.class)
                        .newInstance(launcher, sDpi, sIconSizePx);
            }
            Object controller = null;
            if (sThemeControllerField != null && sMonoCls != null) {
                controller = sMonoCls.getConstructor().newInstance();
                sThemeControllerField.set(factory, controller);
            }
            holder = new Object[]{provider, factory, controller, sFactoryGeneration};
            TL_FACTORY.set(holder);
                LogHelper.log(LogHelper.VerboseLevel.INFO, "icon factory rebuilt generation=",
                    String.valueOf(sFactoryGeneration), " themedController=",
                    String.valueOf(controller != null));
            return holder;
        } catch (Throwable t) {
            LogHelper.log(LogHelper.VerboseLevel.WARNING,
                    "create icon factory failed: ", String.valueOf(t));
            return null;
        }
    }

    /**
     * 主题图标：拿 createThemedAdaptiveIcon() 拼出“主题色背景 + mono 前景”的自适应图标，
     * 再走 createIconBitmap() 输出软件位图（与桌面主题色图标一致，且避开 hardware bitmap）。
     * 无 monochrome 层且未强制 mono 的 app 返回 null，调用方退回普通图标。
     */
    private static Bitmap renderThemed(Object[] holder, Object bitmapInfo, Drawable source,
                                       Context context) {
        if (sCreateIconBitmap3 == null || sNormalize == null
            || !isThemedIconEnabled(context)) {
            return null;
        }
        try {
            Object factory = holder[1];
            float[] scale = new float[1];
            Object adaptive = sNormalize.invoke(factory, source, scale);
            if (!(adaptive instanceof AdaptiveIconDrawable)) {
                return null;
            }
                Object themed = holder[2] == null || sCreateThemedIcon == null ? null
                    : sCreateThemedIcon.invoke(holder[2], sLauncherCtx, adaptive, bitmapInfo);
            if (themed == null) {
                themed = createForcedThemedAdaptiveIcon(factory, (AdaptiveIconDrawable) adaptive,
                        bitmapInfo, context, scale[0]);
            }
            if (themed == null) {
                return null;
            }
            Object bitmap = sCreateIconBitmap3.invoke(factory, themed,
                    scale[0] > 0f ? scale[0] : ICON_VISIBLE_AREA_FACTOR, 0);
                LogHelper.log(LogHelper.VerboseLevel.DEBUG, "render themed icon result=",
                    String.valueOf(bitmap instanceof Bitmap));
            return bitmap instanceof Bitmap ? (Bitmap) bitmap : null;
        } catch (Throwable t) {
            LogHelper.log(LogHelper.VerboseLevel.WARNING, "themed icon failed: ", String.valueOf(t));
            return null;
        }
    }

    private static Object createForcedThemedAdaptiveIcon(Object factory,
                                                          AdaptiveIconDrawable adaptive,
                                                          Object bitmapInfo,
                                                          Context context,
                                                          float scale) {
        try {
            if (sMonoFactoryConstructor == null || sMonoFactoryWrap == null
                    || sCreateIconBitmap3 == null) {
                return null;
            }
            Method getIconBitmapSize = factory.getClass().getMethod("getIconBitmapSize");
            int size = ((Integer) getIconBitmapSize.invoke(factory)).intValue();
            Object monoFactory = sMonoFactoryConstructor.newInstance(size);
            Method getShapePath = factory.getClass().getMethod("getShapePath",
                    AdaptiveIconDrawable.class, Rect.class);
            Path shape = (Path) getShapePath.invoke(factory, adaptive,
                    new Rect(0, 0, size, size));
            Drawable mono = (Drawable) sMonoFactoryWrap.invoke(monoFactory, adaptive, shape,
                    Float.valueOf(scale > 0f ? scale : 1.0f));
            int[] colors = themeColors(context);
            if (mono == null || colors == null) {
                return null;
            }
            mono.setTint(colors[1]);
            return new AdaptiveIconDrawable(new ColorDrawable(colors[0]), mono);
        } catch (Throwable t) {
            LogHelper.log(LogHelper.VerboseLevel.WARNING,
                    "forced themed icon failed: ", String.valueOf(t));
            return null;
        }
    }

    private static String themeCacheSignature(Context context) {
        if (!isThemedIconEnabled(context)) {
            return "plain";
        }
        int[] colors = themeColors(context);
        return colors == null ? "theme-unknown" : colors[0] + ":" + colors[1];
    }

    private static int[] themeColors(Context context) {
        try {
            Resources resources = sLauncherCtx == null ? context.getResources()
                    : sLauncherCtx.getResources();
            int bgId = resources.getIdentifier("themed_icon_background_color", "color",
                    LAUNCHER_PKG);
            int fgId = resources.getIdentifier("themed_icon_color", "color", LAUNCHER_PKG);
            if (bgId == 0 || fgId == 0) {
                return null;
            }
            return new int[]{resources.getColor(bgId), resources.getColor(fgId)};
        } catch (Throwable t) {
            return null;
        }
    }

    /** 找 BaseIconFactory 里存 IconThemeController 的字段（保住名字/混淆两种情况）。 */
    private static Field findThemeControllerField(Class<?> factoryCls, ClassLoader cl)
            throws Exception {
        try {
            return factoryCls.getDeclaredField("mThemeController");
        } catch (NoSuchFieldException e) {
            Class<?> ctrlCls = Class.forName("com.android.launcher3.icons.IconThemeController",
                    false, cl);
            for (Field f : factoryCls.getDeclaredFields()) {
                if (ctrlCls.isAssignableFrom(f.getType())) {
                    return f;
                }
            }
            throw e;
        }
    }

    /**
     * “将系统主题色应用到桌面图标”是否开启。
     * 桌面读的是私有 prefs（GraphUtils.isThemedIconEnabled / ThemeManager.THEMED_ICONS），
     * 跨包读不到，所以读系统设置里的镜像键。结果缓存 5s，避免频繁 IPC。
     */
    private static boolean isThemedIconEnabled(Context context) {
        if (!sFollowSystemTheme) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - sThemedCheckedAt > 5000L) {
            sThemedCheckedAt = now;
            try {
                sThemedEnabled = Settings.System.getInt(context.getContentResolver(),
                        SYSTEM_THEMED_KEY, 0) == 1;
                LogHelper.log(LogHelper.VerboseLevel.INFO, "theme setting key=",
                        SYSTEM_THEMED_KEY, " enabled=", String.valueOf(sThemedEnabled),
                    " generation=", String.valueOf(sFactoryGeneration),
                    " zuiTheme=", zuiTheme());
            } catch (Throwable ignored) {
                sThemedEnabled = false;
            }
        }
        return sThemedEnabled;
    }

    private static String zuiTheme() {
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            return String.valueOf(properties.getMethod("get", String.class, String.class)
                    .invoke(null, "persist.sys.zui.theme", ""));
        } catch (Throwable ignored) {
            return "<unavailable>";
        }
    }
}
