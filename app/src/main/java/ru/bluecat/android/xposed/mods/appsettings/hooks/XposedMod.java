package ru.bluecat.android.xposed.mods.appsettings.hooks;


import android.annotation.TargetApi;
import android.app.AndroidAppHelper;
import android.app.Notification;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.XResources;
import android.content.res.XResources.DimensionReplacement;
import android.media.AudioTrack;
import android.media.JetPlayer;
import android.media.MediaPlayer;
import android.media.SoundPool;
import android.os.Build;
import android.os.Environment;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.ViewConfiguration;

import java.io.File;
import java.util.Locale;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;
import ru.bluecat.android.xposed.mods.appsettings.Common;

public class XposedMod implements IXposedHookLoadPackage, IXposedHookZygoteInit {

	private static XSharedPreferences prefs;
	private static final String SYSTEMUI_PACKAGE = "com.android.systemui";
	private static final String[] SYSTEMUI_ADJUSTED_DIMENSIONS = {
		"status_bar_height",
		"navigation_bar_height", "navigation_bar_height_landscape",
		"navigation_bar_width",
		"system_bar_height"
	};

	@Override
	public void initZygote(IXposedHookZygoteInit.StartupParam startupParam) {

		if(prefs == null) {
			if(XposedBridge.getXposedVersion() < 93) {
				prefs = getLegacyModulePrefs();
			} else {
				prefs = getModulePrefs();
			}
		}

		if(prefs != null) {
			prefs.reload();
			adjustSystemDimensions(prefs);
			dpiInSystem(prefs);
			Activities.hookActivitySettings(prefs);
		}
	}

	@Override
	public void handleLoadPackage(LoadPackageParam lpparam) {
		if (lpparam.packageName.equals(Common.MY_PACKAGE_NAME)) {
			XposedHelpers.findAndHookMethod(Common.MY_PACKAGE_NAME + ".ui.MainActivity",
					lpparam.classLoader,
					"isModuleActive",
					XC_MethodReplacement.returnConstant(true));
		}

		if(prefs != null) {
			prefs.reload();
		} else {
			return;
		}

		if (isActive(prefs, lpparam.packageName, Common.PREF_LEGACY_MENU)) {
			try {
				XposedHelpers.findAndHookMethod(ViewConfiguration.class, "hasPermanentMenuKey",
						XC_MethodReplacement.returnConstant(true));
			} catch (Throwable t) {
				XposedBridge.log(t);
			}
		}

		if (lpparam.packageName.equals("android")) {
			Activities.hookActivitySettingsInSystemServer(lpparam, prefs);
			PackagePermissions.initHooks(lpparam, prefs);
			hookNotificationManager(lpparam, prefs);
		}
		hookScreenSettings(lpparam, prefs);
		hookSoundPool(lpparam, prefs);
	}

	public static XSharedPreferences getModulePrefs() {
		XSharedPreferences pref = new XSharedPreferences(Common.MY_PACKAGE_NAME, Common.PREFS);
		return pref.getFile().canRead() ? pref : null;
	}

	private XSharedPreferences getLegacyModulePrefs() {
		String dataDir = "data/";
		if (Build.VERSION.SDK_INT > Build.VERSION_CODES.M) {
			dataDir = "user_de/0/";
		}
		File f = new File(Environment.getDataDirectory(), dataDir + Common.MY_PACKAGE_NAME + "/shared_prefs/" + Common.PREFS + ".xml");
		return new XSharedPreferences(f);
	}

	static boolean isActive(XSharedPreferences prefs, String packageName) {
		return prefs.getBoolean(packageName + Common.PREF_ACTIVE, false);
	}

	static boolean isActive(XSharedPreferences prefs, String packageName, String sub) {
		return prefs.getBoolean(packageName + Common.PREF_ACTIVE, false) && prefs.getBoolean(packageName + sub, false);
	}

	private void hookNotificationManager (LoadPackageParam lpparam, XSharedPreferences prefs) {
		try {
			ClassLoader classLoader = lpparam.classLoader;
			XC_MethodHook notifyHook = new XC_MethodHook() {
				@SuppressWarnings("deprecation")
				@TargetApi(Build.VERSION_CODES.JELLY_BEAN)
				@Override
				protected void beforeHookedMethod(MethodHookParam param) {
					String packageName = (String) param.args[0];
					Notification n = (Notification) param.args[6];

					if (!isActive(prefs, packageName)) {
						return;
					}

					if (isActive(prefs, packageName, Common.PREF_INSISTENT_NOTIF)) {
						n.flags |= Notification.FLAG_INSISTENT;
					}
					if (isActive(prefs, packageName, Common.PREF_NO_BIG_NOTIFICATIONS)) {
						XposedHelpers.setObjectField(n, "bigContentView", null);
					}
					int ongoingNotif = prefs.getInt(packageName + Common.PREF_ONGOING_NOTIF,
							Common.ONGOING_NOTIF_DEFAULT);
					if (ongoingNotif == Common.ONGOING_NOTIF_FORCE) {
						n.flags |= Notification.FLAG_ONGOING_EVENT;
					} else if (ongoingNotif == Common.ONGOING_NOTIF_PREVENT) {
						n.flags &= ~Notification.FLAG_ONGOING_EVENT & ~Notification.FLAG_FOREGROUND_SERVICE;
					}

					if (isActive(prefs, packageName, Common.PREF_MUTE)) {
						n.sound = null;
						n.flags &= ~Notification.DEFAULT_SOUND;
					}
					if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O && isActive(prefs, packageName) && prefs.contains(packageName + Common.PREF_NOTIF_PRIORITY)) {
						int priority = prefs.getInt(packageName + Common.PREF_NOTIF_PRIORITY, 0);
						if (priority > 0 && priority < Common.notifPriCodes.length) {
							n.flags &= ~Notification.FLAG_HIGH_PRIORITY;
							n.priority = Common.notifPriCodes[priority];
						}
					}
				}
			};
			String notificationHookedMethod = "enqueueNotificationInternal";
			String notificationHookedClass = "com.android.server.notification.NotificationManagerService";
			if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1) {
				XposedHelpers.findAndHookMethod(notificationHookedClass, classLoader, notificationHookedMethod,
						String.class, String.class, int.class, int.class, String.class, int.class, Notification.class, int[].class, int.class,
						notifyHook);
			} else {
				XposedHelpers.findAndHookMethod(notificationHookedClass, classLoader, notificationHookedMethod,
						String.class, String.class, int.class, int.class, String.class, int.class, Notification.class, int.class,
						notifyHook);
			}
		} catch (Throwable t) {
			XposedBridge.log(t);
		}
	}

	private void hookScreenSettings(final LoadPackageParam lpparam, XSharedPreferences prefs) {
		if (isActive(prefs, lpparam.packageName)) {
			// Override settings used when loading resources
			try {
				XposedHelpers.findAndHookMethod(ContextWrapper.class, "attachBaseContext", Context.class, new XC_MethodHook() {
					@Override
					protected void beforeHookedMethod(MethodHookParam param) {
						if (param.args[0] != null && (param.args[0] instanceof Context)) {

							Context context = (Context) param.args[0];
							String packageName = lpparam.packageName;
							Resources res = context.getResources();
							Configuration config = new Configuration(res.getConfiguration());

							DisplayMetrics runningMetrics = res.getDisplayMetrics();
							DisplayMetrics newMetrics;
							if (runningMetrics != null) {
								newMetrics = new DisplayMetrics();
								newMetrics.setTo(runningMetrics);
							} else {
								newMetrics = res.getDisplayMetrics();
							}

							// Workaround for KitKat. The keyguard is a different package now but runs in the
							// same process as SystemUI and displays as main package
							if (packageName.equals("com.android.keyguard")) {
								packageName = SYSTEMUI_PACKAGE;
							}

							// settings related to the density etc. are calculated for the running app...
							if (packageName != null) {
								int screen = prefs.getInt(packageName + Common.PREF_SCREEN,
										prefs.getInt(Common.PREF_DEFAULT + Common.PREF_SCREEN, 0));
								if (screen < 0 || screen >= Common.swdp.length) {
									screen = 0;
								}

								int dpi = prefs.getInt(packageName + Common.PREF_DPI,
										prefs.getInt(Common.PREF_DEFAULT + Common.PREF_DPI, 0));
								int fontScale = prefs.getInt(packageName + Common.PREF_FONT_SCALE,
										prefs.getInt(Common.PREF_DEFAULT + Common.PREF_FONT_SCALE, 0));
								int swdp = Common.swdp[screen];
								int wdp = Common.wdp[screen];
								int hdp = Common.hdp[screen];

								boolean xlarge = prefs.getBoolean(packageName + Common.PREF_XLARGE, false);

								if (swdp > 0) {
									config.smallestScreenWidthDp = swdp;
									config.screenWidthDp = wdp;
									config.screenHeightDp = hdp;
								}
								if (xlarge) {
									config.screenLayout |= Configuration.SCREENLAYOUT_SIZE_XLARGE;
								}
								if (dpi > 0) {
									newMetrics.density = dpi / 160f;
									newMetrics.densityDpi = dpi;
									XposedHelpers.setIntField(config, "densityDpi", dpi);
								}
								if (fontScale > 0) {
									config.fontScale = fontScale / 100.0f;
								}

								// https://github.com/solohsu/XposedAppLocale
								Locale loc = getPackageSpecificLocale(prefs, packageName);

								if (loc != null) {
									config.setLocale(loc);
								}
								context = context.createConfigurationContext(config);
							}

							param.args[0] = context;
						}
					}
				});
			} catch (Throwable t) {
				XposedBridge.log(t);
			}

			// Override the default Locale if one is defined (not res-related, here)
			Locale packageLocale = getPackageSpecificLocale(prefs, lpparam.packageName);
			if (packageLocale != null) {
				Locale.setDefault(packageLocale);
			}
		}
	}

	private void hookSoundPool(LoadPackageParam lpparam, XSharedPreferences prefs) {
		if (isActive(prefs, lpparam.packageName, Common.PREF_MUTE)) {
			try {
				// Hook the AudioTrack API
				XposedHelpers.findAndHookMethod(AudioTrack.class, "play", XC_MethodReplacement.returnConstant(null));

				// Hook the JetPlayer API
				XposedHelpers.findAndHookMethod(JetPlayer.class, "play", XC_MethodReplacement.returnConstant(null));

				// Hook the MediaPlayer API
				XC_MethodHook displayHook = new XC_MethodHook() {
					@Override
					protected void beforeHookedMethod(MethodHookParam param) {
						// Detect if video will be used for this media
						if (param.args[0] != null)
							XposedHelpers.setAdditionalInstanceField(param.thisObject, "HasVideo", true);
						else
							XposedHelpers.removeAdditionalInstanceField(param.thisObject, "HasVideo");
					}
				};
				XposedHelpers.findAndHookMethod(MediaPlayer.class, "setSurface", Surface.class, displayHook);
				XposedHelpers.findAndHookMethod(MediaPlayer.class, "setDisplay", SurfaceHolder.class, displayHook);
				XposedHelpers.findAndHookMethod(MediaPlayer.class, "start", new XC_MethodHook() {
					@Override
					protected void beforeHookedMethod(MethodHookParam param) {
						if (XposedHelpers.getAdditionalInstanceField(param.thisObject, "HasVideo") != null)
							// Video will be used - still start the media but with muted volume
							((MediaPlayer) param.thisObject).setVolume(0, 0);
						else
							// No video - skip starting to play the media altogether
							param.setResult(null);
					}
				});

				// Hook the SoundPool API
				XposedHelpers.findAndHookMethod(SoundPool.class, "play", int.class, float.class, float.class,
						int.class, int.class, float.class,
						XC_MethodReplacement.returnConstant(0));
				XposedHelpers.findAndHookMethod(SoundPool.class, "resume", int.class, XC_MethodReplacement.returnConstant(null));
			} catch (Throwable t) {
				XposedBridge.log(t);
			}
		}
	}

	/** Adjust all framework dimensions that should reflect
	 *  changes related with SystemUI, namely statusbar and navbar sizes.
	 *  The values are adjusted and replaced system-wide by fixed px values.
	 */
	private void adjustSystemDimensions(XSharedPreferences prefs) {
		if (!isActive(prefs, SYSTEMUI_PACKAGE))
			return;

		int systemUiDpi = prefs.getInt(SYSTEMUI_PACKAGE + Common.PREF_DPI,
				prefs.getInt(Common.PREF_DEFAULT + Common.PREF_DPI, 0));
		if (systemUiDpi <= 0)
			return;

		// SystemUI had its DPI overridden.
		// Adjust the relevant framework dimen resources.
		Resources sysRes = Resources.getSystem();
		float sysDensity = sysRes.getDisplayMetrics().density;
		float scaleFactor = (systemUiDpi / 160f) / sysDensity;

		for (String resName : SYSTEMUI_ADJUSTED_DIMENSIONS) {
			int id = sysRes.getIdentifier(resName, "dimen", "android");
			if (id != 0) {
				float original = sysRes.getDimension(id);
				XResources.setSystemWideReplacement(id,
						new DimensionReplacement(original * scaleFactor, TypedValue.COMPLEX_UNIT_PX));
			}
		}
	}

	private void dpiInSystem(XSharedPreferences prefs) {
		// Hook to override DPI (globally, including resource load + rendering)
		try {
			XposedHelpers.findAndHookMethod(Display.class, "updateDisplayInfoLocked", new XC_MethodHook() {
				@Override
				protected void afterHookedMethod(MethodHookParam param) {
					String packageName = AndroidAppHelper.currentPackageName();

					if (!isActive(prefs, packageName)) {
						// No overrides for this package
						return;
					}

					int packageDPI = prefs.getInt(packageName + Common.PREF_DPI,
							prefs.getInt(Common.PREF_DEFAULT + Common.PREF_DPI, 0));
					if (packageDPI > 0) {
						// Density for this package is overridden, change density
						Object mDisplayInfo = XposedHelpers.getObjectField(param.thisObject, "mDisplayInfo");
						XposedHelpers.setIntField(mDisplayInfo, "logicalDensityDpi", packageDPI);
					}
				}
			});

		} catch (Throwable t) {
			XposedBridge.log(t);
		}
	}

	private static Locale getPackageSpecificLocale(XSharedPreferences prefs, String packageName) {
		String locale = prefs.getString(packageName + Common.PREF_LOCALE, null);
		if (locale == null || locale.isEmpty())
			return null;

		String[] localeParts = locale.split("_", 3);
		String language = localeParts[0];
		String region = (localeParts.length >= 2) ? localeParts[1] : "";
		String variant = (localeParts.length >= 3) ? localeParts[2] : "";
		return new Locale(language, region, variant);
	}
}
