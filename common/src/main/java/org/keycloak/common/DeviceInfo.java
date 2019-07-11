package org.keycloak.common;

import ua_parser.Client;
import ua_parser.Parser;

import java.io.IOException;

public class DeviceInfo {
    public static String ID = "DEVICE_INFO";
    private String device;
    private String browser;
    private String browserVersion;
    private String os;
    private String osVersion;
    private String userAgent;

    public DeviceInfo() {
    }

    public DeviceInfo(String device, String browser, String browserVersion, String os, String osVersion, String userAgent) {
        this.device = device;
        this.browser = browser;
        this.browserVersion = browserVersion;
        this.os = os;
        this.osVersion = osVersion;
        this.userAgent = userAgent;
    }

    public static DeviceInfo create(String userAgent) {
        try {
            Parser parser = new Parser();
            if(userAgent == null)
                return new DeviceInfo();
            Client client = parser.parse(userAgent);

            String device = client.device.family;
            String browser = client.userAgent.family;
            String browserVersion = client.userAgent.major;
            if (client.userAgent.minor != null)
                browserVersion += "." + client.userAgent.minor;
            if (client.userAgent.patch != null)
                browserVersion += "." + client.userAgent.patch;
            String os = client.os.family;
            String osVersion = client.os.major;
            if (client.os.minor != null)
                osVersion += "." + client.os.minor;
            if (client.os.patch != null)
                osVersion += "." + client.os.patch;
            if (client.os.patchMinor != null)
                osVersion += "." + client.os.patchMinor;

            return new DeviceInfo(device, browser, browserVersion, os, osVersion, userAgent);
        } catch (IOException e) {
            return new DeviceInfo();
        }
    }

    public String getDevice() {
        return device;
    }

    public void setDevice(String device) {
        this.device = device;
    }

    public String getBrowser() {
        return browser;
    }

    public void setBrowser(String browser) {
        this.browser = browser;
    }

    public String getBrowserVersion() {
        return browserVersion;
    }

    public void setBrowserVersion(String browserVersion) {
        this.browserVersion = browserVersion;
    }

    public String getOs() {
        return os;
    }

    public void setOs(String os) {
        this.os = os;
    }

    public String getOsVersion() {
        return osVersion;
    }

    public void setOsVersion(String osVersion) {
        this.osVersion = osVersion;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public String toString() {
        return userAgent;
    }
}
