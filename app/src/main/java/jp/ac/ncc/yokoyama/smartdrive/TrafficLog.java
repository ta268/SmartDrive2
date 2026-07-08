package jp.ac.ncc.yokoyama.smartdrive;

import java.io.Serializable;

public class TrafficLog implements Serializable {
    private String timestamp;
    private String eventNameJa;
    private float x;
    private float y;
    private float z;
    private double latitude;
    private double longitude;
    private String address;

    public TrafficLog(String timestamp, String eventNameJa, float x, float y, float z, double latitude, double longitude, String address) {
        this.timestamp = timestamp;
        this.eventNameJa = eventNameJa;
        this.x = x;
        this.y = y;
        this.z = z;
        this.latitude = latitude;
        this.longitude = longitude;
        this.address = address;
    }

    public String getTimestamp() { return timestamp; }
    public String getEventNameJa() { return eventNameJa; }
    public float getX() { return x; }
    public float getY() { return y; }
    public float getZ() { return z; }
    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
}
