package me.drton.jmavlib.log.px4;

import me.drton.jmavlib.log.ulog.MessageFormat;

import java.nio.ByteBuffer;

public class MavlinkLog {
    public final String message;
    public final long timestamp;
    public final int logLevel;

    public MavlinkLog(int logLevel, String message, long timestamp) {
        this.logLevel = logLevel;
        this.message = message;
        this.timestamp = timestamp;
    }

    public String getLevelStr() {
        switch (logLevel) {
        case 0:
            return "EMERG";
        case 1:
            return "ALERT";
        case 2:
            return "CRIT";
        case 3:
            return "ERROR";
        case 4:
            return "WARNING";
        case 5:
            return "NOTICE";
        case 6:
            return "INFO";
        case 7:
            return "DEBUG";
        }
        return "(unknown)";
    }

    @Override
    public String toString() {
        return String.format("LOG: time=%s, level=%s, message=%s, value=%s", timestamp, getLevelStr(), message);
    }
}
