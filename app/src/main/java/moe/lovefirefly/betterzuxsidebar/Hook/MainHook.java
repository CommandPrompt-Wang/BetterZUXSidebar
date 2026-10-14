package moe.lovefirefly.betterzuxsidebar.Hook;

import android.app.Application;
import android.content.Context;
import android.os.Looper;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupWindow;
import android.widget.Toast;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

// 目标 apk 未混淆，方法名是明文，先走反射；换机型/固件需要兜底时再启用 DexKit
//（兜底锚点：showPopupWindow 内有唯一日志串 "popupWindow is null, cannot show popup window"）
//import org.luckypray.dexkit.DexKitBridge;
//import org.luckypray.dexkit.query.FindMethod;
//import org.luckypray.dexkit.query.matchers.MethodMatcher;

import moe.lovefirefly.betterzuxsidebar.Ui.SidebarPanel;
import moe.lovefirefly.betterzuxsidebar.Utils.LogHelper;

public class MainHook extends XposedModule {
    private static final String targetPackageName = "com.zui.freeform.sidebar";
    private static final String systemUiPackageName = "com.android.systemui";
    private static final String floatServiceClass = "com.zui.freeform.sidebar.FloatService";
    private static final String showTop5Method = "showTop5PopupWindow";
    private static final String showAllAppsMethod = "showAllAppWindow";
    private static final String popupWindowField = "popupWindow";

    private static volatile boolean sSelfHookDone = false;
    private static volatile Object sFloatService;
    private static volatile Method sShowAllAppsMethod;
    private static SidebarPanel sPanel;
    private static volatile boolean sDismissHookDone;
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());
    
    public MainHook() {
        super();
    }

    /*
     * TODO: 跨 Context 传输多语言文本实在是太麻烦了，有高人有简洁的方法可以来这里改
     * ~~反正我是懒得做了~~
     */
    private static String getSearchingMethodText() {
        return Locale.getDefault().getLanguage().startsWith("zh")
            ? "正在查找方法..."
            : "Searching for methods...";
    }

    @Override 
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        String packageName = param.getPackageName();
        String apkPath = param.getApplicationInfo().sourceDir; // DexKit 兜底时才会用到
        ClassLoader appCl = param.getClassLoader();
        android.util.Log.e("BetterZUXSidebar", "onPackageReady package=" + packageName
            + " first=" + param.isFirstPackage() + " loader=" + appCl);

        if (systemUiPackageName.equals(packageName)) {
            LogHelper.log(LogHelper.VerboseLevel.INFO, "SystemUI scope ready; native drag-and-drop owns split preview");
            return;
        }
        if (!targetPackageName.equals(packageName)) return;
        if (sSelfHookDone) return;
        if(!param.isFirstPackage()) return;

        sSelfHookDone = true;

        installPopupDismissHook();

        LogHelper.log(LogHelper.VerboseLevel.INFO, "Package ready: ", packageName,  
            " firstPackage: ", String.valueOf(param.isFirstPackage())
        );

        // // ~~拿到 Context 对象以弹出 Toast~~
        // try {
        //     Method attachMethod = Application.class.getDeclaredMethod("attach", Context.class);
        //     hook(attachMethod).intercept(chain -> {
        //         Object result = chain.proceed();
        //         Context ctx = (Context) chain.getArg(0);

        //         if (sAppContext == null) {
        //             sAppContext = (Context) chain.getArg(0);
        //         }

        //         // 好吧目前拿到了 Context 但保险起见还是就地弹出了
        //         new Handler(Looper.getMainLooper()).post(() -> {
        //             Toast.makeText(ctx, getSearchingMethodText(), Toast.LENGTH_SHORT).show();
        //         });
        //         return result;
        //     });
        // } catch (Throwable t) {
        //     LogHelper.log(LogHelper.VerboseLevel.ERROR, "Hook attach method failed thus no Toast can be shown: ", String.valueOf(t));
        // }

        // 查找目标方法
        try {
            Class<?> clazz = Class.forName(floatServiceClass, false, appCl);
            Method showAllApps = clazz.getDeclaredMethod(showAllAppsMethod);
            showAllApps.setAccessible(true);
            sShowAllAppsMethod = showAllApps;

            Method[] methods = {clazz.getDeclaredMethod(showTop5Method), showAllApps};
            for (Method method : methods) {
                method.setAccessible(true);
                final boolean allAppsPage = method == showAllApps;
                hook(method).intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        attachPage(chain.getThisObject(), allAppsPage);
                    } catch (Throwable t) {
                        LogHelper.log(LogHelper.VerboseLevel.ERROR, "attach page failed: ", String.valueOf(t));
                    }
                    return result;
                });
                LogHelper.log(LogHelper.VerboseLevel.INFO, "Hooked ", floatServiceClass, "#", method.getName());
            }
        } catch (Throwable t) {
            LogHelper.log(LogHelper.VerboseLevel.ERROR, "Hook FloatService methods failed: ", String.valueOf(t));
        }
    }

    /**
     * 弹窗内容根视图就是 PopupWindow 的 contentView（FloatFrameLayoutView）。
     * 两页共用它：展示"钉住页"或"全部应用页"时把对应的一页挂上去，
     * 其余状态机（显隐/悬停/自动消失）仍全部沿用厂商实现。
     */
    private static void attachPage(Object floatService, boolean allAppsPage) throws Exception {
        android.util.Log.e("BetterZUXSidebar", "ATTACH_PAGE_DIRECT allApps=" + allAppsPage
            + " pid=" + android.os.Process.myPid()
            + " process=" + android.app.Application.getProcessName());
        sFloatService = floatService;
        PopupWindow popupWindow = getPopupWindow(floatService);
        if (popupWindow == null) {
            return;
        }
        View content = popupWindow.getContentView();
        if (!(content instanceof ViewGroup)) {
            return;
        }
        ViewGroup frame = (ViewGroup) content;

        if (sPanel == null) {
            Context ctx = (Context) floatService;
            sPanel = new SidebarPanel(ctx, floatService.getClass().getClassLoader(), new SidebarPanel.Host() {
                @Override
                public void switchToAllApps(boolean delayed) {
                    transitionToAllApps(delayed);
                }

                @Override
                public void launch(Object appInfo) {
                    launchApp(sFloatService, appInfo);
                }

                @Override
                public void refreshPopupBackground() {
                    try {
                        PopupWindow popup = getPopupWindow(sFloatService);
                        if (popup != null && popup.getContentView() != null) {
                            popup.getContentView().setBackground(
                                    sPanel.panelBackgroundForHost());
                        }
                    } catch (Throwable t) {
                        LogHelper.log(LogHelper.VerboseLevel.WARNING,
                                "refresh popup background failed: ", String.valueOf(t));
                    }
                }
            });
        }

        boolean openAllApps = allAppsPage || sPanel.shouldOpenAllAppsDirectly();
        frame.setBackground(sPanel.panelBackgroundForHost());
        View page = openAllApps ? sPanel.getAllAppsPage() : sPanel.getPinnedPage();
        if (frame.getChildCount() != 1 || frame.getChildAt(0) != page) {
            frame.removeAllViews();
            // 厂商每次 show 都可能新建 PopupWindow/contentView，先从旧宿主摘下来再挂载
            if (page.getParent() instanceof ViewGroup) {
                ((ViewGroup) page.getParent()).removeView(page);
            }
            frame.addView(page);
            // 不注册的话 hover 会被 FloatFrameLayoutView 拦下，自己的 View 收不到悬停事件
            try {
                frame.getClass().getMethod("addNotInterceptView", View.class).invoke(frame, page);
            } catch (Throwable t) {
                LogHelper.log(LogHelper.VerboseLevel.WARNING, "addNotInterceptView failed: ", String.valueOf(t));
            }
        }
        if (openAllApps) {
            sPanel.reloadAllApps();
        } else {
            sPanel.reload();
        }
        if (openAllApps) {
            // 滑出 all apps 后直接把焦点给搜索框：IME 立即接管，任意按键自然进输入框
            sPanel.focusSearchField();
        }
        sPanel.onVendorPageAttached();
    }

    /** 先触发目标页面显示；不能在此处 dismiss，因为 vendor 可能复用同一个 PopupWindow。 */
    private static void transitionToAllApps(boolean delayed) {
        if (sFloatService == null || sShowAllAppsMethod == null) {
            return;
        }
        Runnable show = () -> {
            cancelPendingAutoDismiss(sFloatService);
            try {
                sShowAllAppsMethod.invoke(sFloatService);
            } catch (Throwable t) {
                LogHelper.log(LogHelper.VerboseLevel.ERROR, "switch to all apps failed: ", String.valueOf(t));
            }
        };
        if (delayed) {
            sMainHandler.postDelayed(show, 400L);
        } else {
            sMainHandler.post(show);
        }
    }

    private static void cancelPendingAutoDismiss(Object floatService) {
        try {
            Field handlerField = floatService.getClass().getDeclaredField("mHandler");
            Field runnableField = floatService.getClass().getDeclaredField("mRemovePopupWindows");
            handlerField.setAccessible(true);
            runnableField.setAccessible(true);
            Handler handler = (Handler) handlerField.get(floatService);
            Runnable runnable = (Runnable) runnableField.get(floatService);
            if (handler != null && runnable != null) {
                handler.removeCallbacks(runnable);
            }
        } catch (Throwable ignored) {
        }
    }

    private void installPopupDismissHook() {
        try {
            if (sDismissHookDone) {
                return;
            }
            Method dismiss = PopupWindow.class.getDeclaredMethod("dismiss");
            dismiss.setAccessible(true);
            hook(dismiss).intercept(chain -> {
                if (sPanel != null && chain.getThisObject() == currentPopup()) {
                    sPanel.onAllAppsPageHidden();
                }
                return chain.proceed();
            });
            sDismissHookDone = true;
        } catch (Throwable t) {
            LogHelper.log(LogHelper.VerboseLevel.WARNING,
                    "install dismiss hook failed: ", String.valueOf(t));
        }
    }

    private static PopupWindow currentPopup() {
        try {
            return sFloatService == null ? null : getPopupWindow(sFloatService);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static PopupWindow getPopupWindow(Object floatService) throws Exception {
        Field field = floatService.getClass().getDeclaredField(popupWindowField);
        field.setAccessible(true);
        return (PopupWindow) field.get(floatService);
    }

    /** 走厂商自己的启动逻辑（处理 freeform / 分屏 / 多用户分支），然后收起弹窗。 */
    private static void launchApp(Object floatService, Object appInfo) {
        if (floatService == null || appInfo == null) {
            return;
        }
        try {
            // 注意：FloatService.startActivity(AppInfo) 是 private，必须 getDeclaredMethods 逐个找
            Method start = null;
            for (Method m : floatService.getClass().getDeclaredMethods()) {
                if ("startActivity".equals(m.getName()) && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isInstance(appInfo)) {
                    start = m;
                    break;
                }
            }
            if (start == null) {
                throw new NoSuchMethodException("FloatService.startActivity(AppInfo) not found");
            }
            start.setAccessible(true);
            start.invoke(floatService, appInfo);
        } catch (Throwable t) {
            LogHelper.log(LogHelper.VerboseLevel.ERROR, "launch app failed: ", String.valueOf(t));
        }
        try {
            PopupWindow popupWindow = getPopupWindow(floatService);
            if (popupWindow != null && popupWindow.isShowing()) {
                popupWindow.dismiss();
            }
        } catch (Throwable t) {
            LogHelper.log(LogHelper.VerboseLevel.WARNING, "dismiss popup failed: ", String.valueOf(t));
        }
    }
}
