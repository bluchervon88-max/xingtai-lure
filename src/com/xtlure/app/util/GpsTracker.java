package com.xtlure.app.util;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * GPS 定位。只使用系统 LocationManager，不引入任何第三方定位 SDK。
 *
 * 设计要点：
 * - 优先 GPS_PROVIDER（纯卫星定位，野外无信号也能用），退化到 NETWORK_PROVIDER。
 * - 一次请求只取一个点就注销监听，省电。
 * - 超时保护：默认 25 秒拿不到就回调失败，避免界面一直转圈。
 */
public class GpsTracker {

    public interface Callback {
        void onLocation(double lat, double lon, float accuracyMeters, String provider);

        void onFail(String reason);
    }

    /** 权限请求码 */
    public static final int REQ_LOCATION = 1001;

    private final Context ctx;
    private LocationManager lm;
    private LocationListener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable timeoutTask;
    private boolean done;

    public GpsTracker(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    // ---------------------------------------------------------- 权限

    public static boolean hasPermission(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 在 Activity 中请求定位权限 */
    public static void requestPermission(Activity a) {
        List<String> perms = new ArrayList<>();
        if (a.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (a.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (!perms.isEmpty()) {
            a.requestPermissions(perms.toArray(new String[0]), REQ_LOCATION);
        }
    }

    /** 系统定位总开关是否打开（Android 9+ 用 isLocationEnabled） */
    public boolean isLocationServiceEnabled() {
        try {
            LocationManager m = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            if (m == null) {
                return false;
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                return m.isLocationEnabled();
            }
            return m.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || m.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------- 取点

    /**
     * 请求一次定位。回调在主线程。
     *
     * @param timeoutMs 超时毫秒
     */
    public void requestOnce(final Callback cb, long timeoutMs) {
        cancel();
        done = false;

        if (!hasPermission(ctx)) {
            cb.onFail("没有定位权限");
            return;
        }
        if (!isLocationServiceEnabled()) {
            cb.onFail("手机定位服务未开启");
            return;
        }

        lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) {
            cb.onFail("定位服务不可用");
            return;
        }

        // 先看看有没有刚缓存的点，有就立刻用，体验最好
        Location cached = bestLastKnown();
        if (cached != null && System.currentTimeMillis() - cached.getTime() < 120000) {
            done = true;
            cb.onLocation(cached.getLatitude(), cached.getLongitude(),
                    cached.getAccuracy(), cached.getProvider() + "（缓存）");
            return;
        }

        listener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                if (done || location == null) {
                    return;
                }
                done = true;
                double lat = location.getLatitude();
                double lon = location.getLongitude();
                float acc = location.getAccuracy();
                String prov = location.getProvider();
                cancel();
                cb.onLocation(lat, lon, acc, prov);
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
            }

            @Override
            public void onProviderEnabled(String provider) {
            }

            @Override
            public void onProviderDisabled(String provider) {
            }
        };

        boolean registered = false;
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, listener,
                        Looper.getMainLooper());
                registered = true;
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                // 网络定位作为兜底，精度低但快
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0, 0, listener,
                        Looper.getMainLooper());
                registered = true;
            }
        } catch (SecurityException e) {
            cb.onFail("定位权限被拒绝");
            return;
        } catch (Exception e) {
            cb.onFail("启动定位失败: " + e.getMessage());
            return;
        }

        if (!registered) {
            // 没有任何 provider 可用，退回缓存点或报错
            if (cached != null) {
                done = true;
                cb.onLocation(cached.getLatitude(), cached.getLongitude(),
                        cached.getAccuracy(), cached.getProvider() + "（旧缓存）");
            } else {
                cb.onFail("没有可用的定位源，请到空旷处再试");
            }
            return;
        }

        timeoutTask = new Runnable() {
            @Override
            public void run() {
                if (done) {
                    return;
                }
                done = true;
                Location last = bestLastKnown();
                cancel();
                if (last != null) {
                    cb.onLocation(last.getLatitude(), last.getLongitude(),
                            last.getAccuracy(), last.getProvider() + "（超时取旧点）");
                } else {
                    cb.onFail("定位超时。到室外空旷处、避开高楼遮挡后再试一次");
                }
            }
        };
        handler.postDelayed(timeoutTask, timeoutMs);
    }

    private Location bestLastKnown() {
        if (lm == null) {
            lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        }
        if (lm == null) {
            return null;
        }
        Location best = null;
        try {
            for (String p : new String[]{LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
                if (!lm.isProviderEnabled(p)) {
                    continue;
                }
                Location l = lm.getLastKnownLocation(p);
                if (l == null) {
                    continue;
                }
                if (best == null || l.getTime() > best.getTime()) {
                    best = l;
                }
            }
        } catch (Exception ignored) {
        }
        return best;
    }

    /** 注销监听，务必在界面销毁时调用 */
    public void cancel() {
        if (timeoutTask != null) {
            handler.removeCallbacks(timeoutTask);
            timeoutTask = null;
        }
        if (lm != null && listener != null) {
            try {
                lm.removeUpdates(listener);
            } catch (Exception ignored) {
            }
        }
        listener = null;
    }

    public static void toast(Context c, String msg) {
        Toast.makeText(c, msg, Toast.LENGTH_SHORT).show();
    }
}
