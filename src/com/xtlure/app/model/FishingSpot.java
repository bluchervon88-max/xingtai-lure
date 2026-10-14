package com.xtlure.app.model;

import java.io.Serializable;

/**
 * 一个路亚钓点。
 *
 * 坐标约定：lat/lon 一律以 **WGS-84** 存储（即手机 GPS 原始值）。
 * 展示到高德/腾讯地图前转 GCJ-02，展示到百度地图前转 BD-09。
 * 统一存 WGS-84 可以避免以后换地图、换手机时产生累积偏移。
 */
public class FishingSpot implements Serializable {

    // ---- 水域类型 ----
    public static final int TYPE_UNKNOWN = 0;
    public static final int TYPE_RESERVOIR = 1;   // 水库
    public static final int TYPE_RIVER = 2;       // 河流
    public static final int TYPE_LAKE = 3;        // 湖泊
    public static final int TYPE_POND = 4;        // 池塘
    public static final int TYPE_PAYPOND = 5;     // 黑坑/收费塘
    public static final int TYPE_CANAL = 6;       // 渠道
    public static final int TYPE_PIT = 7;         // 沙坑/矿坑

    // ---- 作钓状态 ----
    public static final int STATUS_UNKNOWN = 0;
    public static final int STATUS_OPEN = 1;      // 可钓
    public static final int STATUS_BANNED = 2;    // 禁钓
    public static final int STATUS_FEE = 3;       // 收费
    public static final int STATUS_RESTRICTED = 4;// 限钓/需许可

    // ---- 来源可信度 ----
    public static final int CONF_UNVERIFIED = 0;  // 未核实/推断
    public static final int CONF_WEB = 1;         // 网络资料
    public static final int CONF_FIELD = 2;       // 本人实地确认

    private long id;
    private String name = "";
    private double lat;
    private double lon;
    private boolean hasCoord;
    private int waterType = TYPE_UNKNOWN;
    private int status = STATUS_UNKNOWN;
    private String district = "";       // 区县
    private String address = "";        // 详细位置描述
    private String fishSpecies = "";    // 逗号分隔
    private String lureTypes = "";      // 推荐拟饵，逗号分隔
    private String bestSeason = "";     // 最佳季节
    private String accessNote = "";     // 停车/步行/可达性
    private String note = "";           // 备注
    private String riskNote = "";       // 风险与合规提示
    private String closedSeason = "";   // 禁渔期/休渔规定说明
    private String sourceUrl = "";      // 来源
    private String feeNote = "";        // 费用情况（可能含冲突信息）
    private String evidence = "";       // 调研证据（含反向证据，说明为什么这么判断）
    private String sourceAlt = "";      // 备选来源，竖线分隔
    private String coordPrecision = ""; // 坐标精度：exact / approx
    private int seedVersion;            // 来自哪一版预置数据（0 = 用户自己加的）
    private int confidence = CONF_UNVERIFIED;
    private int rating;                 // 1-5 星，0 表示未评
    private boolean favorite;
    private long createdAt;
    private long updatedAt;

    public FishingSpot() {
    }

    // ---------------------------------------------------------- getters/setters

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getName() { return name == null ? "" : name; }
    public void setName(String name) { this.name = name; }

    public double getLat() { return lat; }
    public void setLat(double lat) { this.lat = lat; }

    public double getLon() { return lon; }
    public void setLon(double lon) { this.lon = lon; }

    public boolean isHasCoord() { return hasCoord; }
    public void setHasCoord(boolean hasCoord) { this.hasCoord = hasCoord; }

    public int getWaterType() { return waterType; }
    public void setWaterType(int waterType) { this.waterType = waterType; }

    public int getStatus() { return status; }
    public void setStatus(int status) { this.status = status; }

    public String getDistrict() { return district == null ? "" : district; }
    public void setDistrict(String district) { this.district = district; }

    public String getAddress() { return address == null ? "" : address; }
    public void setAddress(String address) { this.address = address; }

    public String getFishSpecies() { return fishSpecies == null ? "" : fishSpecies; }
    public void setFishSpecies(String fishSpecies) { this.fishSpecies = fishSpecies; }

    public String getLureTypes() { return lureTypes == null ? "" : lureTypes; }
    public void setLureTypes(String lureTypes) { this.lureTypes = lureTypes; }

    public String getBestSeason() { return bestSeason == null ? "" : bestSeason; }
    public void setBestSeason(String bestSeason) { this.bestSeason = bestSeason; }

    public String getAccessNote() { return accessNote == null ? "" : accessNote; }
    public void setAccessNote(String accessNote) { this.accessNote = accessNote; }

    public String getNote() { return note == null ? "" : note; }
    public void setNote(String note) { this.note = note; }

    public String getRiskNote() { return riskNote == null ? "" : riskNote; }
    public void setRiskNote(String riskNote) { this.riskNote = riskNote; }

    public String getClosedSeason() { return closedSeason == null ? "" : closedSeason; }
    public void setClosedSeason(String closedSeason) { this.closedSeason = closedSeason; }

    public String getFeeNote() { return feeNote == null ? "" : feeNote; }
    public void setFeeNote(String feeNote) { this.feeNote = feeNote; }

    public String getEvidence() { return evidence == null ? "" : evidence; }
    public void setEvidence(String evidence) { this.evidence = evidence; }

    public String getSourceAlt() { return sourceAlt == null ? "" : sourceAlt; }
    public void setSourceAlt(String sourceAlt) { this.sourceAlt = sourceAlt; }

    public String getCoordPrecision() { return coordPrecision == null ? "" : coordPrecision; }
    public void setCoordPrecision(String coordPrecision) { this.coordPrecision = coordPrecision; }

    public int getSeedVersion() { return seedVersion; }
    public void setSeedVersion(int seedVersion) { this.seedVersion = seedVersion; }

    /** 坐标是否经过实测核对（而不是估算） */
    public boolean isCoordExact() {
        return "exact".equalsIgnoreCase(coordPrecision)
                || confidence == CONF_FIELD;
    }

    /** 坐标精度的人话说法 */
    public String coordPrecisionText() {
        if (confidence == CONF_FIELD) {
            return "你已实地用 GPS 校正";
        }
        if ("exact".equalsIgnoreCase(coordPrecision)) {
            return "已按公开地图数据核对，误差约几十米";
        }
        return "估算值，误差可能到公里级，请现场用 GPS 校正";
    }

    /** 是否被用户自己核实过（那就绝不能再用预置数据覆盖） */
    public boolean isUserVerified() {
        return confidence == CONF_FIELD;
    }

    public String getSourceUrl() { return sourceUrl == null ? "" : sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }

    public int getConfidence() { return confidence; }
    public void setConfidence(int confidence) { this.confidence = confidence; }

    public int getRating() { return rating; }
    public void setRating(int rating) { this.rating = rating; }

    public boolean isFavorite() { return favorite; }
    public void setFavorite(boolean favorite) { this.favorite = favorite; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    // ---------------------------------------------------------- 展示辅助

    public static String waterTypeName(int type) {
        switch (type) {
            case TYPE_RESERVOIR: return "水库";
            case TYPE_RIVER:     return "河流";
            case TYPE_LAKE:      return "湖泊";
            case TYPE_POND:      return "池塘";
            case TYPE_PAYPOND:   return "黑坑";
            case TYPE_CANAL:     return "渠道";
            case TYPE_PIT:       return "沙坑";
            default:             return "未分类";
        }
    }

    public static String statusName(int s) {
        switch (s) {
            case STATUS_OPEN:       return "可钓";
            case STATUS_BANNED:     return "禁钓";
            case STATUS_FEE:        return "收费";
            case STATUS_RESTRICTED: return "限钓";
            default:                return "待确认";
        }
    }

    public static String confidenceName(int c) {
        switch (c) {
            case CONF_FIELD: return "实地确认";
            case CONF_WEB:   return "网络资料";
            default:         return "未核实";
        }
    }

    // ---- 河北省海河流域禁渔期：5月16日12时 — 7月31日12时 ----
    // 依据邢台市及各区县农业农村局通告（如邢台市信都区 2025 年休禁渔制度通告）。
    //
    // 注意措辞：通告原文的禁止类型是「除钓具之外的所有作业方式」，
    // 即钓具垂钓本身被豁免；严打对象是「以休闲垂钓为幌子的一人多杆、多钩线钓、锚鱼」。
    // 网上流传的「一人一杆一线一钩」并未找到官方条文出处，因此不作为规定引用。
    public static final int CLOSED_MONTH_FROM = 5;
    public static final int CLOSED_DAY_FROM = 16;
    public static final int CLOSED_MONTH_TO = 7;
    public static final int CLOSED_DAY_TO = 31;
    public static final String CLOSED_SEASON_TEXT = "5月16日12时—7月31日12时";

    /** 通告原文关于禁止作业类型的表述（应原样引用，不要改写成其他说法） */
    public static final String CLOSED_SEASON_RULE =
            "通告原文：禁止使用除钓具以外的所有采捕工具（撒网、拉网、粘网、地笼、"
                    + "锚鱼设备等）；严打以休闲垂钓为幌子的一人多杆、多钩线钓、锚鱼行为。";

    /** 天然水域是否处于禁渔期（人工养殖塘不适用） */
    public static boolean inClosedSeason(java.util.Calendar c) {
        int month = c.get(java.util.Calendar.MONTH) + 1;
        int day = c.get(java.util.Calendar.DAY_OF_MONTH);
        int md = month * 100 + day;
        return md >= (CLOSED_MONTH_FROM * 100 + CLOSED_DAY_FROM)
                && md <= (CLOSED_MONTH_TO * 100 + CLOSED_DAY_TO);
    }

    public static boolean inClosedSeasonNow() {
        return inClosedSeason(java.util.Calendar.getInstance());
    }

    /** 这个钓点是否受禁渔期约束（黑坑/养殖塘不受约束） */
    public boolean subjectToClosedSeason() {
        return waterType != TYPE_PAYPOND && waterType != TYPE_POND;
    }

    /** 当前是否正处于这个钓点的禁渔期 */
    public boolean isClosedNow() {
        return subjectToClosedSeason() && inClosedSeasonNow();
    }

    /** 距离禁渔期开始还有多少天；已进入返回 0；已过则返回 -1 */
    public static int daysUntilClosedSeason(java.util.Calendar c) {
        int md = (c.get(java.util.Calendar.MONTH) + 1) * 100 + c.get(java.util.Calendar.DAY_OF_MONTH);
        int start = CLOSED_MONTH_FROM * 100 + CLOSED_DAY_FROM;
        if (md >= start && md <= CLOSED_MONTH_TO * 100 + CLOSED_DAY_TO) {
            return 0;
        }
        java.util.Calendar t = (java.util.Calendar) c.clone();
        t.set(java.util.Calendar.MONTH, CLOSED_MONTH_FROM - 1);
        t.set(java.util.Calendar.DAY_OF_MONTH, CLOSED_DAY_FROM);
        if (md > CLOSED_MONTH_TO * 100 + CLOSED_DAY_TO) {
            t.add(java.util.Calendar.YEAR, 1);
        }
        long diff = t.getTimeInMillis() - c.getTimeInMillis();
        return (int) Math.ceil(diff / 86400000.0);
    }

    /** 水域类型对应的图标，让列表一眼能分辨 */
    public static String waterTypeIcon(int type) {
        switch (type) {
            case TYPE_RESERVOIR: return "🏞";
            case TYPE_RIVER:     return "🌊";
            case TYPE_LAKE:      return "💧";
            case TYPE_POND:      return "🪷";
            case TYPE_PAYPOND:   return "🎫";
            case TYPE_CANAL:     return "🚱";
            case TYPE_PIT:       return "⛏";
            default:             return "📍";
        }
    }

    public String coordText() {
        if (!hasCoord) {
            return "无坐标";
        }
        return String.format(java.util.Locale.CHINA, "%.5f, %.5f", lat, lon);
    }

    /** 用于搜索的合并文本 */
    public String searchBlob() {
        return (name + " " + district + " " + address + " " + fishSpecies + " "
                + lureTypes + " " + note + " " + riskNote + " "
                + waterTypeName(waterType)).toLowerCase();
    }

    @Override
    public String toString() {
        return "FishingSpot{" + name + " " + district + " " + waterTypeName(waterType) + "}";
    }
}
