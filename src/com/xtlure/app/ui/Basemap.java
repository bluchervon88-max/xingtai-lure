package com.xtlure.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

import com.xtlure.app.data.BasemapData;

import java.io.InputStream;
import java.util.List;

/**
 * 离线矢量底图的**绘制层**。
 *
 * ════════════════════════════════════════════════════════════════════════
 * ★ 这个类为什么这么薄
 *
 *   它原来同时干两件事：载入解码 + Canvas 绘制。载入解码是纯逻辑，
 *   绘制才需要 Android。混在一起导致离线契约测试编不了它
 *   （测试只对 core/ 和 util/ 编译，而 Canvas 把整个 Android UI 依赖拖了进来）。
 *
 *   现在职责拆开了：
 *     {@link BasemapData}（data 包）  —— 载入 + 解码 + 外接框，纯逻辑，
 *                                        能在电脑上直接验证，有契约测试兜着
 *     本类（ui 包）                   —— 只负责把几何画到 Canvas 上
 *
 *   与 iOS 侧结构一致（那边也是 `Basemap.swift` 只有载入解码、
 *   绘制另做），两边对照着看不会有认知落差。
 *
 * ════════════════════════════════════════════════════════════════════════
 * ★ 底图数据从哪来、为什么不接商用地图 SDK
 *
 *   数据是把 OpenStreetMap 的真实地理数据（ODbL 许可）编译进 APK，
 *   本地矢量绘制。不接高德/百度/腾讯 SDK 的原因：
 *   那些 SDK 都要 API Key + INTERNET 权限 + 联网环境，
 *   而本 App 的卖点恰恰是"不申请网络权限、离线可用"。
 *   要导航时用「导航」功能跳到手机上的高德/百度即可，
 *   由对方 App 去联网，本 App 依旧不需要任何权限。
 *
 *   详细的数据格式说明见 {@link BasemapData} 的类注释。
 */
public class Basemap {

    /**
     * 几何类型别名。
     *
     * 定义留在绘制层、数据在数据层，是为了让 `SpotMapView` 和 `MapActivity`
     * 不必改类型名（它们用的一直是 `Basemap.Shape`）。
     * 别名而不是继承：Shape 是纯数据，没有需要覆写的行为，
     * 用继承只会让两个类绑死。
     */
    public static final class Shape extends BasemapData.Shape {
        /** 屏幕坐标外接框，用于剔除。每帧由 {@link #cull} 更新 */
        float minX, maxX, minY, maxY;
    }

    private final BasemapData data = new BasemapData();
    private boolean loaded;

    // ================================================================ 载入

    /**
     * 从 assets 载入全部底图数据。
     *
     * 解析约 5.3 万个顶点。解析失败**不影响主流程**（地图退化为只有钓点），
     * 原因写进返回字符串，调用方可决定是否记录。
     *
     * 真正的解码在 {@link BasemapData#load}，这里只负责"从 assets 开流"。
     */
    public void load(Context ctx) {
        if (loaded) {
            return;
        }
        final android.content.res.AssetManager am = ctx.getAssets();
        String err = data.load(new BasemapData.StreamOpener() {
            @Override
            public InputStream open(String name) throws Exception {
                return am.open(name);
            }
        });
        if (err != null) {
            // 底图缺失不该让地图整个不可用 —— 只是没有水系参照而已
            android.util.Log.e("Basemap", err);
        }
        loaded = true;
    }

    public boolean isLoaded() {
        return data.isLoaded();
    }

    public int vertexCount() {
        return data.vertexCount();
    }

    /** 底图覆盖的地理范围 {minLat, minLon, maxLat, maxLon} */
    public double[] bounds() {
        return data.bounds();
    }

    // ================================================================ 绘制

    /** 投影接口：把经纬度变成屏幕坐标。缩放平移由调用方的画布变换统一处理 */
    public interface Projector {
        float x(double lon);

        float y(double lat);
    }

    private final Path path = new Path();
    private float viewW = 1, viewH = 1;
    private float pad = 60;

    public void setViewport(int w, int h) {
        this.viewW = Math.max(1, w);
        this.viewH = Math.max(1, h);
    }

    /**
     * 绘制底图。
     *
     * 图层顺序（从下往上）：河道线 -> 水体面 -> 行政区界 -> 名称标注。
     *
     * @param waterColor  水体填充色
     * @param riverColor  河道线色
     * @param borderMine  邢台本市行政区界
     * @param borderOther 周边地市界（画得更淡）
     */
    public void draw(Canvas canvas, Projector proj, Paint fill, Paint stroke, Paint textPaint,
                     int waterColor, int riverColor, int borderMine, int borderOther,
                     boolean showLabels, float density) {
        if (!loaded || !data.isLoaded()) {
            return;
        }

        // ---- 河道（最底层：河在陆地之上、水体之下）
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(1f, 1.2f * density));
        stroke.setColor(riverColor);
        stroke.setPathEffect(null);
        for (BasemapData.Shape s : data.river()) {
            if (!cull(s, proj)) {
                continue;
            }
            buildPath(s, proj, false);
            canvas.drawPath(path, stroke);
        }

        // ---- 水体（面）
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(waterColor);
        for (BasemapData.Shape s : data.water()) {
            if (!cull(s, proj)) {
                continue;
            }
            buildPath(s, proj, true);
            canvas.drawPath(path, fill);
        }

        // ---- 行政区界（虚线）
        stroke.setStrokeWidth(Math.max(1f, 1f * density));
        for (BasemapData.Shape s : data.boundary()) {
            if (!cull(s, proj)) {
                continue;
            }
            buildPath(s, proj, true);
            stroke.setColor(s.local ? borderMine : borderOther);
            canvas.drawPath(path, stroke);
        }

        // ---- 名称标注
        if (showLabels) {
            textPaint.setStyle(Paint.Style.FILL);
            textPaint.setTextAlign(Paint.Align.CENTER);
            // 水体名（只标有名字的）
            textPaint.setColor(waterColor);
            for (BasemapData.Shape s : data.water()) {
                if (s.name.isEmpty() || !cull(s, proj)) {
                    continue;
                }
                canvas.drawText(s.name, proj.x(s.labelLon), proj.y(s.labelLat), textPaint);
            }
            // 区县名：只标本市的，避免周边地市把画面挤满
            textPaint.setColor(borderMine);
            for (BasemapData.Shape s : data.boundary()) {
                if (!s.local || s.name.isEmpty() || !cull(s, proj)) {
                    continue;
                }
                canvas.drawText(s.name, proj.x(s.labelLon), proj.y(s.labelLat), textPaint);
            }
        }
    }

    private void buildPath(BasemapData.Shape s, Projector proj, boolean close) {
        path.reset();
        int n = s.lats.length;
        path.moveTo(proj.x(s.lons[0]), proj.y(s.lats[0]));
        for (int i = 1; i < n; i++) {
            path.lineTo(proj.x(s.lons[i]), proj.y(s.lats[i]));
        }
        if (close) {
            path.close();
        }
    }

    /**
     * 屏幕外剔除。
     *
     * 底图有 889 个几何，逐个绘制的话每帧要建近千条 Path。
     * 先算外接框，完全在屏幕外的直接跳过 —— 缩放到市级视野时能省掉大半。
     *
     * ★ 注意这里用的是**屏幕坐标**外接框，所以每帧都要按当前投影重算
     *   （缩放平移一变，屏幕外接框就变了）。
     *   经纬度外接框由 {@link BasemapData#lonLatBox} 提供，
     *   那个与投影无关、可以缓存 —— 但本方法需要的是屏幕框，两者不能混用。
     */
    private boolean cull(BasemapData.Shape s, Projector proj) {
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < s.lats.length; i++) {
            float x = proj.x(s.lons[i]);
            float y = proj.y(s.lats[i]);
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
        }
        return maxX >= -pad && minX <= viewW + pad && maxY >= -pad && minY <= viewH + pad;
    }
}
