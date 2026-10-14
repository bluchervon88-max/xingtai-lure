package com.xtlure.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import com.xtlure.app.model.FishingSpot;
import com.xtlure.app.util.ScreenFit;
import com.xtlure.app.util.Ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 钓点分布图。
 *
 * ★ 结构（这一版重写的重点）
 *   投影与视野的数学全部交给 {@link GeoViewport} —— 一个不引用 Android 的纯计算对象。
 *   本类只持有三样**最原始**的状态：当前缩放、视野中心经纬度；
 *   视口像素尺寸在每次绘制时从 getWidth()/getHeight() 现取。
 *   所有比例、可见范围、屏幕坐标都由这三样即时算出，**不缓存任何派生量**。
 *
 *   之前把比例缓存在字段里、由多个入口分别更新，一旦某个入口漏更新，
 *   比例就会停留在错的值上 —— 真机上表现为"放大到 32× 才勉强能用"，
 *   而 adb 读到的界面上限值往往是几个不同时刻的混合，根本定位不了。
 *   现在同一个函数在任何时刻都给出同一个答案，与调用顺序无关。
 *
 * ★ 底图
 *   用 {@link Basemap} 渲染真实 OpenStreetMap 矢量数据（水系 / 区县界 / 河道）。
 *   不接商用地图 SDK 的原因见 Basemap 的注释。
 *
 * ★ 聚类
 *   按屏幕网格分桶，格子边长固定在屏幕上（CELL_DP），所以聚合圆始终是同一个
 *   物理大小、放大后自然散开。只对可见点分桶 —— 画框外的点若参与，
 *   会被压到边缘格子里与可见点并成一团（离线校验实测出"同格内两点相距 59 km"）。
 */
public class SpotMapView extends View {

    /** 点击某个点的回调 */
    public interface OnSpotTap {
        void onTap(FishingSpot spot);
    }

    /** 点击空白处的回调 */
    public interface OnMapTap {
        void onMapTap();
    }

    /** 一个屏幕上的聚合点 */
    private static class Cluster {
        final List<FishingSpot> members = new ArrayList<>();
        float x, y;

        int color() {
            int[] count = new int[8];
            for (FishingSpot s : members) {
                int t = s.getWaterType();
                if (s.getStatus() == FishingSpot.STATUS_BANNED) {
                    t = 7;
                }
                if (t >= 0 && t < count.length) {
                    count[t]++;
                }
            }
            int best = 0;
            for (int i = 1; i < count.length; i++) {
                if (count[i] > count[best]) {
                    best = i;
                }
            }
            return best == 7 ? Ui.DANGER : Ui.waterTypeColor(best);
        }

        boolean hasFavorite() {
            for (FishingSpot s : members) {
                if (s.isFavorite()) {
                    return true;
                }
            }
            return false;
        }
    }

    /** 网格边长（dp）：聚合圆在屏幕上的目标间距 */
    private static final float CELL_DP = 30f;
    /** 绘图区内边距（dp）：留出经纬刻度与比例尺的位置 */
    private static final float PAD_LEFT_DP = 16f;
    private static final float PAD_TOP_DP = 14f;
    private static final float PAD_RIGHT_DP = 12f;
    private static final float PAD_BOTTOM_DP = 30f;

    private final List<FishingSpot> spots = new ArrayList<>();
    private final List<Cluster> clusters = new ArrayList<>();
    private double userLat = Double.NaN;
    private double userLon = Double.NaN;
    private OnSpotTap onSpotTap;
    private OnMapTap onMapTap;
    private Runnable onViewChanged;

    private Basemap basemap;
    private boolean showBasemap = true;
    private double[] baseBounds;

    // ---- 唯一的三个原始状态
    private float zoom = 1f;
    private double centerLat = 37.225;
    private double centerLon = 114.775;
    /** 数据变化后需要重新把视野对准数据 */
    private boolean needFit = true;
    /** 上次绘制后是否还没通知界面刷新统计 */
    private boolean statDirty = true;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mapText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bgRect = new RectF();

    private float lastTouchDist = 0f;
    private float downX, downY;
    private boolean moved;

    public SpotMapView(Context c) {
        super(c);
        text.setTextSize(Ui.dp(c, 9));
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(1f, Ui.dp(c, 0.8f)));
        fill.setStyle(Paint.Style.FILL);
        mapText.setTextSize(Ui.dp(c, 10));
        mapText.setAntiAlias(true);
    }

    // ================================================================ 数据

    public void setBasemap(Basemap b) {
        this.basemap = b;
        this.baseBounds = (b != null && b.isLoaded()) ? b.bounds() : null;
        invalidate();
    }

    public void setShowBasemap(boolean show) {
        this.showBasemap = show;
        invalidate();
    }

    public boolean isShowBasemap() {
        return showBasemap;
    }

    public void setOnViewChanged(Runnable r) {
        this.onViewChanged = r;
    }

    public void setData(List<FishingSpot> list) {
        spots.clear();
        if (list != null) {
            for (FishingSpot s : list) {
                if (s.isHasCoord()) {
                    spots.add(s);
                }
            }
        }
        needFit = true;
        statDirty = true;
        invalidate();
    }

    public void setUserLocation(double lat, double lon) {
        this.userLat = lat;
        this.userLon = lon;
        invalidate();
    }

    public void setOnSpotTap(OnSpotTap l) {
        this.onSpotTap = l;
    }

    public void setOnMapTap(OnMapTap l) {
        this.onMapTap = l;
    }

    // ================================================================ 状态查询

    public int coordCount() {
        return spots.size();
    }

    public int clusterCount() {
        return clusters.size();
    }

    public int totalSpotCount() {
        return spots.size();
    }

    public int visibleSpotCount() {
        int n = 0;
        for (Cluster c : clusters) {
            n += c.members.size();
        }
        return n;
    }

    public float currentZoom() {
        return zoom;
    }

    public String viewportText() {
        return viewport().describe();
    }

    public void resetView() {
        needFit = true;
        statDirty = true;
        invalidate();
    }

    public void zoomBy(float factor) {
        applyZoom(zoom * factor);
    }

    // ================================================================ 视野

    /**
     * 当前视口对应的投影。
     *
     * ★ 每次调用都现算，不缓存 —— 这是本类不再出比例问题的关键。
     */
    private GeoViewport viewport() {
        return new GeoViewport(plotWidth(), plotHeight(), zoom, centerLat, centerLon);
    }

    private float padLeft() {
        return Ui.dp(getContext(), PAD_LEFT_DP);
    }

    private float padTop() {
        return Ui.dp(getContext(), PAD_TOP_DP);
    }

    private float plotWidth() {
        return Math.max(1f, getWidth() - Ui.dp(getContext(), PAD_LEFT_DP + PAD_RIGHT_DP));
    }

    private float plotHeight() {
        return Math.max(1f, getHeight() - Ui.dp(getContext(), PAD_TOP_DP + PAD_BOTTOM_DP));
    }

    /** 控件坐标 -> 绘图区坐标 */
    private float toMapX(float viewX) {
        return viewX - padLeft();
    }

    private float toMapY(float viewY) {
        return viewY - padTop();
    }

    /** 把视野对准数据 */
    private void fitToData() {
        double minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
        for (FishingSpot s : spots) {
            minLat = Math.min(minLat, s.getLat());
            maxLat = Math.max(maxLat, s.getLat());
            minLon = Math.min(minLon, s.getLon());
            maxLon = Math.max(maxLon, s.getLon());
        }
        if (minLat > maxLat) {
            if (baseBounds != null) {
                minLat = baseBounds[0]; minLon = baseBounds[1];
                maxLat = baseBounds[2]; maxLon = baseBounds[3];
            } else {
                minLat = 36.90; maxLat = 37.60;
                minLon = 113.90; maxLon = 115.10;
            }
        }
        centerLat = (minLat + maxLat) / 2;
        centerLon = (minLon + maxLon) / 2;
        zoom = 1f;
        needFit = false;
    }

    private void applyZoom(float targetZoom) {
        applyZoom(targetZoom, getWidth() / 2f, getHeight() / 2f);
    }

    /** 以给定控件坐标为锚点缩放 */
    private void applyZoom(float targetZoom, float anchorViewX, float anchorViewY) {
        float newZoom = Math.max(GeoViewport.MIN_ZOOM,
                Math.min(GeoViewport.MAX_ZOOM, targetZoom));
        if (Math.abs(newZoom - zoom) < 1e-4f) {
            return;
        }
        GeoViewport vp = viewport();
        GeoViewport next = clamp(vp.zoomAround(newZoom,
                toMapX(anchorViewX), toMapY(anchorViewY)));
        zoom = next.zoom;
        centerLat = next.centerLat;
        centerLon = next.centerLon;
        statDirty = true;
        invalidate();
    }

    private void panBy(float dxPx, float dyPx) {
        GeoViewport vp = viewport();
        GeoViewport next = clamp(vp.withCenter(
                vp.centerLat + dyPx / vp.pxPerLatDeg(),
                vp.centerLon - dxPx / vp.pxPerLonDeg()));
        centerLat = next.centerLat;
        centerLon = next.centerLon;
        invalidate();
    }

    private GeoViewport clamp(GeoViewport vp) {
        double bMinLat, bMinLon, bMaxLat, bMaxLon;
        if (baseBounds != null) {
            bMinLat = baseBounds[0]; bMinLon = baseBounds[1];
            bMaxLat = baseBounds[2]; bMaxLon = baseBounds[3];
        } else {
            bMinLat = 36.30; bMaxLat = 38.32;
            bMinLon = 113.06; bMaxLon = 116.28;
        }
        return vp.clampCenter(bMinLat, bMinLon, bMaxLat, bMaxLon);
    }

    // ================================================================ 绘制

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final int w = getWidth();
        final int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;     // 还没测量，什么都不做（绝不使用假尺寸）
        }
        if (needFit) {
            fitToData();
        }

        final GeoViewport vp = viewport();

        bgRect.set(0, 0, w, h);
        fill.setColor(Ui.SURFACE_2);
        canvas.drawRoundRect(bgRect, Ui.dp(getContext(), 10), Ui.dp(getContext(), 10), fill);

        final int save = canvas.save();
        // 投影以绘图区左上角为原点，这里把画布平移过去
        canvas.translate(padLeft(), padTop());

        if (showBasemap && basemap != null && basemap.isLoaded()) {
            basemap.setViewport((int) plotWidth(), (int) plotHeight());
            basemap.draw(canvas, new Basemap.Projector() {
                        @Override
                        public float x(double lon) {
                            return vp.x(lon);
                        }

                        @Override
                        public float y(double lat) {
                            return vp.y(lat);
                        }
                    }, fill, stroke, mapText,
                    Ui.blend(Ui.INFO, Ui.SURFACE_2, 0.50f),
                    Ui.blend(Ui.INFO, Ui.SURFACE_2, 0.30f),
                    Ui.blend(Ui.TEXT_FAINT, Ui.SURFACE_2, 0.50f),
                    Ui.blend(Ui.TEXT_FAINT, Ui.SURFACE_2, 0.20f),
                    zoom >= 2f, getResources().getDisplayMetrics().density);
        }

        buildClusters(vp);
        drawClusters(canvas);
        drawUser(canvas, vp);
        drawGraticule(canvas, vp);
        drawScaleBar(canvas, vp);

        canvas.restoreToCount(save);

        if (spots.isEmpty()) {
            text.setColor(Ui.TEXT_FAINT);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(Ui.dp(getContext(), 12));
            canvas.drawText("还没有带坐标的钓点", w / 2f, h / 2f, text);
        }

        // 聚类完成后才通知界面刷新统计（回调要读 clusterCount，顺序不能反）
        if (statDirty) {
            statDirty = false;
            if (onViewChanged != null) {
                onViewChanged.run();
            }
        }
    }

    /** 网格聚类：格子边长固定在屏幕上，只对可见点分桶 */
    private void buildClusters(GeoViewport vp) {
        clusters.clear();
        final float cell = Ui.dp(getContext(), CELL_DP);
        final float pw = plotWidth();
        final float ph = plotHeight();
        if (pw <= 1 || ph <= 1 || cell <= 1) {
            return;
        }
        final int cols = Math.max(1, (int) Math.floor(pw / cell));
        final int rows = Math.max(1, (int) Math.floor(ph / cell));
        final float cw = pw / cols;
        final float ch = ph / rows;

        final List<List<FishingSpot>> buckets = new ArrayList<>(cols * rows);
        final float[] cx = new float[cols * rows];
        final float[] cy = new float[cols * rows];
        for (int i = 0; i < cols * rows; i++) {
            buckets.add(null);
        }

        for (FishingSpot s : spots) {
            final float x = vp.x(s.getLon());
            final float y = vp.y(s.getLat());
            // 剔除画框外的点（外扩一格，避免边界点被误剔）
            if (x < -cell || x > pw + cell || y < -cell || y > ph + cell) {
                continue;
            }
            final int col = Math.max(0, Math.min(cols - 1, (int) (x / cw)));
            final int row = Math.max(0, Math.min(rows - 1, (int) (y / ch)));
            final int idx = row * cols + col;

            List<FishingSpot> bucket = buckets.get(idx);
            if (bucket == null) {
                bucket = new ArrayList<>();
                buckets.set(idx, bucket);
            }
            bucket.add(s);
            final int n = bucket.size();
            cx[idx] += (x - cx[idx]) / n;
            cy[idx] += (y - cy[idx]) / n;
        }

        for (int i = 0; i < buckets.size(); i++) {
            List<FishingSpot> bucket = buckets.get(i);
            if (bucket == null) {
                continue;
            }
            Cluster c = new Cluster();
            c.x = cx[i];
            c.y = cy[i];
            c.members.addAll(bucket);
            clusters.add(c);
        }

        Collections.sort(clusters, new Comparator<Cluster>() {
            @Override
            public int compare(Cluster a, Cluster b) {
                return b.members.size() - a.members.size();
            }
        });
    }

    private float clusterRadius(int n) {
        return Ui.dp(getContext(), 5.5f) + Math.min(n - 1, 6) * Ui.dp(getContext(), 1.3f);
    }

    private void drawClusters(Canvas canvas) {
        for (Cluster c : clusters) {
            float r = clusterRadius(c.members.size());
            fill.setColor(Ui.blend(c.color(), Ui.SURFACE_2, 0.20f));
            canvas.drawCircle(c.x, c.y, r * 1.9f, fill);
        }
        for (Cluster c : clusters) {
            final int n = c.members.size();
            final int color = c.color();
            final float r = clusterRadius(n);

            fill.setColor(Ui.blend(color, Ui.SURFACE_2, 0.92f));
            canvas.drawCircle(c.x, c.y, r, fill);
            stroke.setColor(Ui.blend(color, Ui.SURFACE_2, 0.55f));
            stroke.setStrokeWidth(Ui.dp(getContext(), 1.5f));
            canvas.drawCircle(c.x, c.y, r, stroke);

            if (c.hasFavorite()) {
                stroke.setColor(Ui.FAV);
                canvas.drawCircle(c.x, c.y, r + Ui.dp(getContext(), 2.5f), stroke);
            }
            if (n > 1) {
                text.setColor(Ui.SURFACE_2);
                text.setTextAlign(Paint.Align.CENTER);
                text.setFakeBoldText(true);
                text.setTextSize(Ui.dp(getContext(), n > 9 ? 8.5f : 9.5f));
                canvas.drawText(String.valueOf(n), c.x, c.y + Ui.dp(getContext(), 3.2f), text);
                text.setFakeBoldText(false);
            }
        }
    }

    private void drawUser(Canvas canvas, GeoViewport vp) {
        if (Double.isNaN(userLat)) {
            return;
        }
        float ux = vp.x(userLon);
        float uy = vp.y(userLat);
        if (ux < -40 || ux > plotWidth() + 40 || uy < -40 || uy > plotHeight() + 40) {
            return;
        }
        fill.setColor(Ui.blend(Ui.INFO, Ui.SURFACE_2, 0.18f));
        canvas.drawCircle(ux, uy, Ui.dp(getContext(), 10), fill);
        stroke.setColor(Ui.INFO);
        stroke.setStrokeWidth(Ui.dp(getContext(), 2f));
        canvas.drawCircle(ux, uy, Ui.dp(getContext(), 6), stroke);
        fill.setColor(Ui.INFO);
        canvas.drawCircle(ux, uy, Ui.dp(getContext(), 2.8f), fill);

        text.setColor(Ui.INFO);
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(Ui.dp(getContext(), 10));
        canvas.drawText("我", ux + Ui.dp(getContext(), 9), uy + Ui.dp(getContext(), 3.5f), text);
    }

    private void drawGraticule(Canvas canvas, GeoViewport vp) {
        text.setColor(Ui.TEXT_FAINT);
        text.setTextSize(Ui.dp(getContext(), 9));
        final float pw = plotWidth();
        final float ph = plotHeight();

        text.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(String.format(Locale.CHINA, "%.2f°E", vp.minLon()), 0,
                ph + Ui.dp(getContext(), 14), text);
        text.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(String.format(Locale.CHINA, "%.2f°E", vp.maxLon()), pw,
                ph + Ui.dp(getContext(), 14), text);
        text.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(String.format(Locale.CHINA, "%.2f°N", vp.maxLat()), 0,
                Ui.dp(getContext(), 10), text);
        canvas.drawText(String.format(Locale.CHINA, "%.2f°N", vp.minLat()), 0,
                ph - Ui.dp(getContext(), 2), text);
    }

    /** 比例尺：投影已做 cos 修正，只需按一个方向换算 */
    private void drawScaleBar(Canvas canvas, GeoViewport vp) {
        final double metersPerPx = vp.metersPerPixel();
        if (metersPerPx <= 0) {
            return;
        }
        final double[] candidates = {500, 1000, 2000, 5000, 10000, 20000, 50000, 100000};
        double chosen = candidates[candidates.length - 1];
        for (double cand : candidates) {
            if (cand / metersPerPx < plotWidth() * 0.4) {
                chosen = cand;
                break;
            }
        }
        final float barPx = (float) (chosen / metersPerPx);
        final float y0 = Ui.dp(getContext(), 12);

        stroke.setColor(Ui.TEXT_FAINT);
        stroke.setStrokeWidth(Ui.dp(getContext(), 1.6f));
        canvas.drawLine(0, y0, barPx, y0, stroke);
        canvas.drawLine(0, y0 - Ui.dp(getContext(), 4), 0, y0 + Ui.dp(getContext(), 4), stroke);
        canvas.drawLine(barPx, y0 - Ui.dp(getContext(), 4), barPx,
                y0 + Ui.dp(getContext(), 4), stroke);

        text.setColor(Ui.TEXT_FAINT);
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(Ui.dp(getContext(), 9));
        canvas.drawText(chosen >= 1000
                        ? String.format(Locale.CHINA, "%.0f km", chosen / 1000)
                        : String.format(Locale.CHINA, "%.0f m", chosen),
                barPx + Ui.dp(getContext(), 5), y0 + Ui.dp(getContext(), 3), text);

        text.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(String.format(Locale.CHINA, "%.2f×", zoom), plotWidth(),
                y0 + Ui.dp(getContext(), 3), text);
    }

    // ================================================================ 交互

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                moved = false;
                lastTouchDist = 0f;
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (event.getPointerCount() >= 2) {
                    lastTouchDist = distance(event);
                    moved = true;
                }
                return true;

            case MotionEvent.ACTION_MOVE: {
                if (event.getPointerCount() >= 2) {
                    float d = distance(event);
                    if (lastTouchDist > 0 && d > 0) {
                        float fx = (event.getX(0) + event.getX(1)) / 2f;
                        float fy = (event.getY(0) + event.getY(1)) / 2f;
                        applyZoom(zoom * (d / lastTouchDist), fx, fy);
                    }
                    lastTouchDist = d;
                    moved = true;
                } else {
                    float dx = event.getX() - downX;
                    float dy = event.getY() - downY;
                    if (Math.hypot(dx, dy) > Ui.dp(getContext(), 4)) {
                        moved = true;
                    }
                    if (moved) {
                        panBy(dx, dy);
                    }
                    downX = event.getX();
                    downY = event.getY();
                }
                return true;
            }

            case MotionEvent.ACTION_UP: {
                if (moved) {
                    return true;
                }
                // clusters 里的 x/y 是绘图区坐标，触摸点要减掉内边距
                final float tx = toMapX(event.getX());
                final float ty = toMapY(event.getY());
                final float minTolerance = ScreenFit.get().touchMin() * 0.45f;

                Cluster best = null;
                float bestDist = Float.MAX_VALUE;
                for (Cluster c : clusters) {
                    float hit = Math.max(clusterRadius(c.members.size())
                            + Ui.dp(getContext(), 8), minTolerance);
                    float d = (float) Math.hypot(c.x - tx, c.y - ty);
                    if (d <= hit && d < bestDist) {
                        bestDist = d;
                        best = c;
                    }
                }
                if (best != null && onSpotTap != null) {
                    GeoViewport vp = viewport();
                    FishingSpot pick = best.members.get(0);
                    float pd = Float.MAX_VALUE;
                    for (FishingSpot s : best.members) {
                        float d = (float) Math.hypot(vp.x(s.getLon()) - tx, vp.y(s.getLat()) - ty);
                        if (d < pd) {
                            pd = d;
                            pick = s;
                        }
                    }
                    onSpotTap.onTap(pick);
                } else if (onMapTap != null) {
                    onMapTap.onMapTap();
                }
                return true;
            }

            case MotionEvent.ACTION_CANCEL:
                moved = true;
                return true;

            default:
                return super.onTouchEvent(event);
        }
    }

    private float distance(MotionEvent e) {
        if (e.getPointerCount() < 2) {
            return 0f;
        }
        return (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
    }
}
