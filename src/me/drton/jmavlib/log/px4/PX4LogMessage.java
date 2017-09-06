package me.drton.jmavlib.log.px4;

import java.util.List;

/**
 * User: ton Date: 03.06.13 Time: 16:18
 */
public class PX4LogMessage {
    public final PX4LogMessageDescription description;
    private final List<Object> data;
    private final List<byte[]> raw;

    public PX4LogMessage(PX4LogMessageDescription description, List<Object> data, List<byte[]> raw) {
        this.description = description;
        this.data = data;
        this.raw = raw;
    }

    public Object get(int idx) {
        return data.get(idx);
    }

    public byte[] getRaw(String field) {
        Integer idx = description.fieldsMap.get(field);
        return idx == null ? null : raw.get(idx);
    }

    public long getLong(int idx) {
        return (Long) data.get(idx);
    }

    public Object get(String field) {
        Integer idx = description.fieldsMap.get(field);
        return idx == null ? null : data.get(idx);
    }

    @Override
    public String toString() {
        return String.format("PX4LogMessage: type=%s, name=%s, data=%s", description.type, description.name, data);
    }
}
