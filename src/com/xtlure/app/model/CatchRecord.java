package com.xtlure.app.model;

import java.io.Serializable;

/** 一条作钓/渔获记录。 */
public class CatchRecord implements Serializable {

    private long id;
    private long spotId;            // 关联钓点，-1 表示未关联
    private String spotName = "";   // 冗余存名字，防止钓点被删后记录失去可读性
    private long time;              // 作钓时间戳
    private String targetFish = ""; // 目标鱼
    private String caughtFish = ""; // 实际钓获（空 = 空军）
    private int count;              // 尾数
    private String sizeNote = "";   // 体长/重量描述
    private String lureUsed = "";   // 用饵
    private String method = "";     // 钓法
    private double lat;
    private double lon;
    private boolean hasCoord;
    private String weather = "";    // 天气（手填）
    private String wind = "";       // 风向风力（手填）
    private String waterNote = "";  // 水情：涨水/落水/浑浊/清澈
    private String pressure = "";   // 气压，例如 1008 或 1008hPa
    private String airTemp = "";    // 气温
    private String waterTemp = "";  // 水温
    private String note = "";
    private int rating;             // 本次体验 1-5
    private long createdAt;

    public CatchRecord() {
    }

    public boolean isBlank() {
        return count <= 0 && (caughtFish == null || caughtFish.trim().isEmpty());
    }

    // ---------------------------------------------------------- getters/setters

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public long getSpotId() { return spotId; }
    public void setSpotId(long spotId) { this.spotId = spotId; }

    public String getSpotName() { return spotName == null ? "" : spotName; }
    public void setSpotName(String spotName) { this.spotName = spotName; }

    public long getTime() { return time; }
    public void setTime(long time) { this.time = time; }

    public String getTargetFish() { return targetFish == null ? "" : targetFish; }
    public void setTargetFish(String targetFish) { this.targetFish = targetFish; }

    public String getCaughtFish() { return caughtFish == null ? "" : caughtFish; }
    public void setCaughtFish(String caughtFish) { this.caughtFish = caughtFish; }

    public int getCount() { return count; }
    public void setCount(int count) { this.count = count; }

    public String getSizeNote() { return sizeNote == null ? "" : sizeNote; }
    public void setSizeNote(String sizeNote) { this.sizeNote = sizeNote; }

    public String getLureUsed() { return lureUsed == null ? "" : lureUsed; }
    public void setLureUsed(String lureUsed) { this.lureUsed = lureUsed; }

    public String getMethod() { return method == null ? "" : method; }
    public void setMethod(String method) { this.method = method; }

    public double getLat() { return lat; }
    public void setLat(double lat) { this.lat = lat; }

    public double getLon() { return lon; }
    public void setLon(double lon) { this.lon = lon; }

    public boolean isHasCoord() { return hasCoord; }
    public void setHasCoord(boolean hasCoord) { this.hasCoord = hasCoord; }

    public String getWeather() { return weather == null ? "" : weather; }
    public void setWeather(String weather) { this.weather = weather; }

    public String getWind() { return wind == null ? "" : wind; }
    public void setWind(String wind) { this.wind = wind; }

    public String getWaterNote() { return waterNote == null ? "" : waterNote; }
    public void setWaterNote(String waterNote) { this.waterNote = waterNote; }

    public String getPressure() { return pressure == null ? "" : pressure; }
    public void setPressure(String pressure) { this.pressure = pressure; }

    public String getAirTemp() { return airTemp == null ? "" : airTemp; }
    public void setAirTemp(String airTemp) { this.airTemp = airTemp; }

    public String getWaterTemp() { return waterTemp == null ? "" : waterTemp; }
    public void setWaterTemp(String waterTemp) { this.waterTemp = waterTemp; }

    /** 环境信息拼成一行，卡片与详情共用 */
    public String environmentLine() {
        StringBuilder sb = new StringBuilder();
        appendIf(sb, getWeather());
        appendIf(sb, getWind());
        if (!getPressure().isEmpty()) {
            appendIf(sb, "气压 " + getPressure());
        }
        if (!getAirTemp().isEmpty()) {
            appendIf(sb, "气温 " + getAirTemp());
        }
        if (!getWaterTemp().isEmpty()) {
            appendIf(sb, "水温 " + getWaterTemp());
        }
        appendIf(sb, getWaterNote());
        return sb.toString();
    }

    private static void appendIf(StringBuilder sb, String v) {
        if (v == null || v.isEmpty()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(" · ");
        }
        sb.append(v);
    }

    public String getNote() { return note == null ? "" : note; }
    public void setNote(String note) { this.note = note; }

    public int getRating() { return rating; }
    public void setRating(int rating) { this.rating = rating; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public String searchBlob() {
        return (spotName + " " + targetFish + " " + caughtFish + " " + lureUsed + " "
                + method + " " + note + " " + waterNote).toLowerCase();
    }
}
