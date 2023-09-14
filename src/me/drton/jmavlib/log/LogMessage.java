package me.drton.jmavlib.log;

public interface LogMessage {
    String getLevelStr();
    String getMessage();
    long getTimestamp();
    String toString();
}
