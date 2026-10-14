package com.xtlure.app.util;

import com.xtlure.app.model.CatchRecord;
import com.xtlure.app.model.FishingSpot;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * JSON 导出。
 *
 * 两个产物：
 *   1) 通用备份 JSON —— 纯手写序列化，不依赖任何 JSON 库，导入导出都不会因版本变化失效。
 *   2) GeoJSON —— 可以直接丢进 QGIS / geojson.io / 高德开放平台查看全部钓点，
 *      也是以后做热力图、分享给钓友的通用格式。
 *
 * 注意：GeoJSON 里坐标用 WGS-84（国际标准），转换由使用方按需处理。
 */
public final class Exporter {

    private Exporter() {
    }

    public static final int FORMAT_VERSION = 1;

    // ------------------------------------------------------------ 备份 JSON

    public static String toBackupJson(List<FishingSpot> spots, List<CatchRecord> catches) {
        StringBuilder sb = new StringBuilder(8192);
        sb.append("{\n");
        sb.append("  \"format\": \"xtlure-backup\",\n");
        sb.append("  \"formatVersion\": ").append(FORMAT_VERSION).append(",\n");
        sb.append("  \"app\": \"邢台路亚\",\n");
        sb.append("  \"exportedAt\": \"").append(iso(System.currentTimeMillis())).append("\",\n");
        sb.append("  \"note\": \"坐标字段 lat/lon 均为 WGS-84（GPS 原始值）。展示到高德/腾讯需转 GCJ-02，百度需转 BD-09。\",\n");

        sb.append("  \"spots\": [\n");
        for (int i = 0; i < spots.size(); i++) {
            appendSpot(sb, spots.get(i));
            sb.append(i < spots.size() - 1 ? ",\n" : "\n");
        }
        sb.append("  ],\n");

        sb.append("  \"catches\": [\n");
        for (int i = 0; i < catches.size(); i++) {
            appendCatch(sb, catches.get(i));
            sb.append(i < catches.size() - 1 ? ",\n" : "\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static void appendSpot(StringBuilder sb, FishingSpot s) {
        sb.append("    {");
        sb.append("\"id\":").append(s.getId()).append(',');
        sb.append("\"name\":").append(str(s.getName())).append(',');
        // 坐标用扁平字段，与 assets/spots_seed.json 完全一致的格式，
        // 这样"导出 -> 重新导入"能无损往返（Seed.fromMap 读的就是这个格式）。
        sb.append("\"hasCoord\":").append(s.isHasCoord()).append(',');
        sb.append("\"lat\":").append(num(s.getLat())).append(',');
        sb.append("\"lon\":").append(num(s.getLon())).append(',');
        sb.append("\"waterType\":").append(s.getWaterType()).append(',');
        sb.append("\"waterTypeName\":").append(str(FishingSpot.waterTypeName(s.getWaterType()))).append(',');
        sb.append("\"status\":").append(s.getStatus()).append(',');
        sb.append("\"statusName\":").append(str(FishingSpot.statusName(s.getStatus()))).append(',');
        sb.append("\"district\":").append(str(s.getDistrict())).append(',');
        sb.append("\"address\":").append(str(s.getAddress())).append(',');
        sb.append("\"fishSpecies\":").append(str(s.getFishSpecies())).append(',');
        sb.append("\"lureTypes\":").append(str(s.getLureTypes())).append(',');
        sb.append("\"bestSeason\":").append(str(s.getBestSeason())).append(',');
        sb.append("\"accessNote\":").append(str(s.getAccessNote())).append(',');
        sb.append("\"note\":").append(str(s.getNote())).append(',');
        sb.append("\"riskNote\":").append(str(s.getRiskNote())).append(',');
        sb.append("\"closedSeason\":").append(str(s.getClosedSeason())).append(',');
        sb.append("\"fee\":").append(str(s.getFeeNote())).append(',');
        sb.append("\"evidence\":").append(str(s.getEvidence())).append(',');
        sb.append("\"sourceAlt\":").append(str(s.getSourceAlt())).append(',');
        sb.append("\"coordPrecision\":").append(str(s.getCoordPrecision())).append(',');
        sb.append("\"source\":").append(str(s.getSourceUrl())).append(',');
        sb.append("\"confidence\":").append(s.getConfidence()).append(',');
        sb.append("\"confidenceName\":").append(str(FishingSpot.confidenceName(s.getConfidence()))).append(',');
        sb.append("\"rating\":").append(s.getRating()).append(',');
        sb.append("\"favorite\":").append(s.isFavorite());
        sb.append('}');
    }

    private static void appendCatch(StringBuilder sb, CatchRecord r) {
        sb.append("    {");
        sb.append("\"id\":").append(r.getId()).append(',');
        sb.append("\"spotId\":").append(r.getSpotId()).append(',');
        sb.append("\"spotName\":").append(str(r.getSpotName())).append(',');
        sb.append("\"time\":").append(r.getTime()).append(',');
        sb.append("\"timeText\":").append(str(iso(r.getTime()))).append(',');
        sb.append("\"targetFish\":").append(str(r.getTargetFish())).append(',');
        sb.append("\"caughtFish\":").append(str(r.getCaughtFish())).append(',');
        sb.append("\"count\":").append(r.getCount()).append(',');
        sb.append("\"sizeNote\":").append(str(r.getSizeNote())).append(',');
        sb.append("\"lureUsed\":").append(str(r.getLureUsed())).append(',');
        sb.append("\"method\":").append(str(r.getMethod())).append(',');
        sb.append("\"weather\":").append(str(r.getWeather())).append(',');
        sb.append("\"wind\":").append(str(r.getWind())).append(',');
        sb.append("\"pressure\":").append(str(r.getPressure())).append(',');
        sb.append("\"airTemp\":").append(str(r.getAirTemp())).append(',');
        sb.append("\"waterTemp\":").append(str(r.getWaterTemp())).append(',');
        sb.append("\"waterNote\":").append(str(r.getWaterNote())).append(',');
        sb.append("\"note\":").append(str(r.getNote())).append(',');
        sb.append("\"rating\":").append(r.getRating());
        sb.append('}');
    }

    // ------------------------------------------------------------ GeoJSON

    /**
     * 生成 GeoJSON FeatureCollection。
     * 属性里额外附带 GCJ-02 坐标，方便直接贴到高德用。
     */
    public static String toGeoJson(List<FishingSpot> spots) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("{\n");
        sb.append("  \"type\": \"FeatureCollection\",\n");
        sb.append("  \"name\": \"邢台路亚钓点\",\n");
        sb.append("  \"crs\": {\"type\":\"name\",\"properties\":{\"name\":\"urn:ogc:def:crs:OGC:1.3:CRS84\"}},\n");
        sb.append("  \"features\": [\n");

        boolean first = true;
        for (FishingSpot s : spots) {
            if (!s.isHasCoord()) {
                continue;
            }
            if (!first) {
                sb.append(",\n");
            }
            first = false;
            double[] g = com.xtlure.app.core.CoordinateTransform.wgs84ToGcj02(s.getLat(), s.getLon());
            sb.append("    {\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
                    .append(num(s.getLon())).append(',').append(num(s.getLat())).append("]},");
            sb.append("\"properties\":{");
            sb.append("\"name\":").append(str(s.getName())).append(',');
            sb.append("\"waterType\":").append(str(FishingSpot.waterTypeName(s.getWaterType()))).append(',');
            sb.append("\"status\":").append(str(FishingSpot.statusName(s.getStatus()))).append(',');
            sb.append("\"district\":").append(str(s.getDistrict())).append(',');
            sb.append("\"fish\":").append(str(s.getFishSpecies())).append(',');
            sb.append("\"lure\":").append(str(s.getLureTypes())).append(',');
            sb.append("\"confidence\":").append(str(FishingSpot.confidenceName(s.getConfidence()))).append(',');
            sb.append("\"gcj02Lat\":").append(num(g[0])).append(',');
            sb.append("\"gcj02Lon\":").append(num(g[1]));
            sb.append("}}");
        }
        sb.append("\n  ]\n}\n");
        return sb.toString();
    }

    // ------------------------------------------------------------ 清单 Markdown

    /**
     * 生成人可读的 Markdown 清单，方便打印、发群里、或贴到笔记软件。
     */
    public static String toMarkdown(List<FishingSpot> spots) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("# 邢台路亚钓点清单\n\n");
        sb.append("导出时间：").append(iso(System.currentTimeMillis())).append("\n\n");

        // 按区县分组
        java.util.LinkedHashMap<String, List<FishingSpot>> byDistrict = new java.util.LinkedHashMap<>();
        for (FishingSpot s : spots) {
            String d = s.getDistrict().isEmpty() ? "未标区县" : s.getDistrict();
            List<FishingSpot> list = byDistrict.get(d);
            if (list == null) {
                list = new java.util.ArrayList<>();
                byDistrict.put(d, list);
            }
            list.add(s);
        }

        sb.append("共 ").append(spots.size()).append(" 个钓点，分布在 ")
                .append(byDistrict.size()).append(" 个区县。\n\n");
        sb.append("> 坐标均以 WGS-84（GPS 原始值）记录。粘贴到高德/腾讯地图前需转 GCJ-02，")
                .append("百度需转 BD-09；本 App「复制坐标」会自动给出三种。\n\n");

        for (java.util.Map.Entry<String, List<FishingSpot>> e : byDistrict.entrySet()) {
            sb.append("## ").append(e.getKey()).append("\n\n");
            for (FishingSpot s : e.getValue()) {
                sb.append("### ").append(s.getName()).append("\n\n");
                sb.append("- 水域类型：").append(FishingSpot.waterTypeName(s.getWaterType())).append('\n');
                sb.append("- 作钓状态：").append(FishingSpot.statusName(s.getStatus())).append('\n');
                if (s.isHasCoord()) {
                    double[] g = com.xtlure.app.core.CoordinateTransform.wgs84ToGcj02(
                            s.getLat(), s.getLon());
                    sb.append(String.format(Locale.CHINA,
                            "- 坐标：WGS84 %.5f, %.5f ／ GCJ02 %.5f, %.5f%n",
                            s.getLat(), s.getLon(), g[0], g[1]));
                } else {
                    sb.append("- 坐标：未记录\n");
                }
                if (!s.getAddress().isEmpty()) {
                    sb.append("- 位置：").append(s.getAddress()).append('\n');
                }
                if (!s.getFishSpecies().isEmpty()) {
                    sb.append("- 对象鱼：").append(s.getFishSpecies()).append('\n');
                }
                if (!s.getLureTypes().isEmpty()) {
                    sb.append("- 推荐拟饵：").append(s.getLureTypes()).append('\n');
                }
                if (!s.getBestSeason().isEmpty()) {
                    sb.append("- 最佳季节：").append(s.getBestSeason()).append('\n');
                }
                if (!s.getAccessNote().isEmpty()) {
                    sb.append("- 到达方式：").append(s.getAccessNote()).append('\n');
                }
                if (!s.getNote().isEmpty()) {
                    sb.append("- 备注：").append(s.getNote()).append('\n');
                }
                if (!s.getRiskNote().isEmpty()) {
                    sb.append("- ⚠ 风险：").append(s.getRiskNote()).append('\n');
                }
                if (!s.getClosedSeason().isEmpty()) {
                    sb.append("- ⚖ 禁渔期：").append(s.getClosedSeason()).append('\n');
                }
                if (!s.getFeeNote().isEmpty()) {
                    sb.append("- 💰 费用：").append(s.getFeeNote()).append('\n');
                }
                sb.append("- 资料可信度：").append(FishingSpot.confidenceName(s.getConfidence()))
                        .append("（").append(s.coordPrecisionText()).append("）").append('\n');
                if (!s.getSourceUrl().isEmpty()) {
                    sb.append("- 来源：").append(s.getSourceUrl()).append('\n');
                }
                sb.append('\n');
            }
        }

        sb.append("---\n\n");
        sb.append("数据由「邢台路亚」App 导出。坐标与鱼情仅供参考，")
                .append("出钓前请自行核实当地禁渔期与水域管理规定。\n");
        return sb.toString();
    }

    // ------------------------------------------------------------ 简易美化打印

    /** 把压缩 JSON 按缩进展开，便于人眼查看（不支持字符串内的花括号，够用即可） */
    public static String pretty(String json) {
        StringBuilder out = new StringBuilder(json.length() + json.length() / 2);
        int indent = 0;
        boolean inStr = false;
        for (int i = 0; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (ch == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
                inStr = !inStr;
            }
            if (inStr) {
                out.append(ch);
                continue;
            }
            switch (ch) {
                case '{':
                case '[':
                    out.append(ch).append('\n');
                    indent++;
                    pad(out, indent);
                    break;
                case '}':
                case ']':
                    out.append('\n');
                    indent = Math.max(0, indent - 1);
                    pad(out, indent);
                    out.append(ch);
                    break;
                case ',':
                    out.append(ch).append('\n');
                    pad(out, indent);
                    break;
                case ':':
                    out.append(": ");
                    break;
                case ' ':
                case '\n':
                case '\t':
                case '\r':
                    break;
                default:
                    out.append(ch);
            }
        }
        return out.toString();
    }

    private static void pad(StringBuilder sb, int n) {
        for (int i = 0; i < n; i++) {
            sb.append("  ");
        }
    }

    // ------------------------------------------------------------ 小工具

    private static String iso(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date(ms));
    }

    private static String num(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            return String.valueOf((long) d);
        }
        return String.format(Locale.US, "%.6f", d).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static String str(String s) {
        if (s == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format(Locale.US, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
