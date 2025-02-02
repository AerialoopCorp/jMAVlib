package me.drton.jmavlib.log.px4;

import me.drton.jmavlib.log.BinaryLogReader;
import me.drton.jmavlib.log.FormatErrorException;
import me.drton.jmavlib.log.LogMessage;

import java.io.EOFException;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * User: ton Date: 03.06.13 Time: 14:18
 */
public class PX4LogReader extends BinaryLogReader {
    private static final int HEADER_LEN = 3;
    private static final byte HEADER_HEAD1 = (byte) 0xA3;
    private static final byte HEADER_HEAD2 = (byte) 0x95;

    private long dataStart = 0;
    private boolean formatPX4 = false;
    private Map<Integer, PX4LogMessageDescription> messageDescriptions
        = new HashMap<Integer, PX4LogMessageDescription>();
    private Map<String, String> fieldsList = null;
    private long time = 0;
    private PX4LogMessage lastMsg = null;
    private PX4LogMessage debugMsg = null;
    private long sizeUpdates = -1;
    private long sizeMicroseconds = -1;
    private long startMicroseconds = -1;
    private long utcTimeReference = -1;
    private Map<String, Object> version = new HashMap<String, Object>();
    private Map<String, Object> parameters = new HashMap<String, Object>();
    private List<Exception> errors = new ArrayList<Exception>();
    private String tsName = null;
    private boolean tsMicros;
    private List<LogMessage> logs = new ArrayList<LogMessage>();
    private boolean rememberFormats;

    private static Set<String> hideMsgs = new HashSet<String>();
    private static Map<String, String> formatNames = new HashMap<String, String>();

    static {
        hideMsgs.add("PARM");
        hideMsgs.add("FMT");
        hideMsgs.add("TIME");
        hideMsgs.add("VER");

        formatNames.put("b", "int8");
        formatNames.put("B", "uint8");
        formatNames.put("L", "int32 * 1e-7 (lat/lon)");
        formatNames.put("i", "int32");
        formatNames.put("I", "uint32");
        formatNames.put("q", "int64");
        formatNames.put("Q", "uint64");
        formatNames.put("f", "float");
        formatNames.put("d", "double");
        formatNames.put("c", "int16 * 1e-2");
        formatNames.put("C", "uint16 * 1e-2");
        formatNames.put("e", "int32 * 1e-2");
        formatNames.put("E", "uint32 * 1e-2");
        formatNames.put("n", "char[4]");
        formatNames.put("N", "char[16]");
        formatNames.put("Z", "char[64]");
        formatNames.put("M", "uint8 (mode)");
        formatNames.put("h", "int16");
        formatNames.put("H", "uint16");
    }

    public PX4LogReader(String fileName, boolean rememberFormats) throws IOException, FormatErrorException {
        super(fileName);
        this.rememberFormats = rememberFormats;
        readFormats();
        updateStatistics();
    }

    @Override
    public String getFormat() {
        return formatPX4 ? "PX4" : "APM";
    }

    @Override
    public String getSystemName() {
        return getFormat();
    }

    @Override
    public long getSizeUpdates() {
        return sizeUpdates;
    }

    @Override
    public long getStartMicroseconds() {
        return startMicroseconds;
    }

    @Override
    public long getSizeMicroseconds() {
        return sizeMicroseconds;
    }

    @Override
    public long getUTCTimeReferenceMicroseconds() {
        return utcTimeReference;
    }

    @Override
    public Map<String, Object> getVersion() {
        return version;
    }

    @Override
    public Map<String, Object> getParameters() {
        return parameters;
    }

    private void updateStatistics() throws IOException, FormatErrorException {
        seek(0);
        long packetsNum = 0;
        long timeStart = -1;
        long timeEnd = -1;
        boolean parseVersion = true;
        StringBuilder versionStr = new StringBuilder();
        while (true) {
            PX4LogMessage msg;
            try {
                msg = readMessage();
            } catch (EOFException e) {
                break;
            }

            // Time range
            if (formatPX4) {
                if ("TIME".equals(msg.description.name)) {
                    long t = msg.getLong(0);
                    time = t;

                    if (timeStart < 0) {
                        timeStart = t;
                    }

                    if (t > timeEnd) {
                        // on a rare occasion t was suddenly smaller and that screws up the plotting
                        timeEnd = t;
                    }
                }
            } else {
                long t = getAPMTimestamp(msg);
                if (t > 0) {
                    if (timeStart < 0) {
                        timeStart = t;
                    }
                    if (t > timeEnd) {
                        timeEnd = t;
                    }
                }
            }
            packetsNum++;

            // Version
            if (formatPX4) {
                if ("VER".equals(msg.description.name)) {
                    String fw = (String) msg.get("FwGit");
                    if (fw != null) {
                        version.put("FW", fw);
                    }
                    String hw = (String) msg.get("Arch");
                    if (hw != null) {
                        version.put("HW", hw);
                    }
                    byte[] uidRaw = msg.getRaw("UID");
                    if (uidRaw != null) {
                        StringBuffer uid = new StringBuffer(24);
                        for(int i = 0; i < 12; i++) {
                            uid.append(String.format("%02x", uidRaw[i]));
                        }
                        version.put("UID", uid);
                    }
                    Long cts = (Long)msg.get("CTS");
                    if (cts != null) {
                        version.put("CTS", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(cts * (long)1e3)));
                    }
                    String tag = (String) msg.get("Tag");
                    if (tag != null) {
                        version.put("Tag", tag);
                    }
                }
            } else {
                if ("MSG".equals(msg.description.name)) {
                    String s = (String) msg.get("Message");
                    if (parseVersion && (s.startsWith("Ardu") || s.startsWith("PX4"))) {
                        if (versionStr.length() > 0) {
                            versionStr.append("; ");
                        }
                        versionStr.append(s);
                    } else {
                        parseVersion = false;
                    }
                }
            }

            // Parameters
            if ("PARM".equals(msg.description.name)) {
                Object value = msg.get("Value");
                Number type = (Number)msg.get("Type");
                if (type != null && type.intValue() == 1) {
                    value = ((Number)msg.get("Value")).intValue();
                }
                parameters.put((String) msg.get("Name"), value);
            }

            if ("GPS".equals(msg.description.name)) {
                if (utcTimeReference < 0) {
                    try {
                        if (formatPX4) {
                            int fix = ((Number) msg.get("Fix")).intValue();
                            long gpsT = ((Number) msg.get("GPSTime")).longValue();
                            if (fix >= 3 && gpsT > 0) {
                                utcTimeReference = gpsT - timeEnd;
                            }
                        } else {
                            int fix = ((Number) msg.get("Status")).intValue();
                            int week = ((Number) msg.get("Week")).intValue();
                            long ms = ((Number) msg.get(tsName)).longValue();
                            if (tsMicros) {
                                ms = ms / 1000;
                            }
                            if (fix >= 3 && (week > 0 || ms > 0)) {
                                long leapSeconds = 16;
                                long gpsT = ((315964800L + week * 7L * 24L * 3600L - leapSeconds) * 1000 + ms) * 1000L;
                                utcTimeReference = gpsT - timeEnd;
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            }

            if ("TXT".equals(msg.description.name)) {
                try {
                    logs.add(new MavlinkLog(((Number)msg.get("Severity")).intValue(), (String)msg.get("Text"), time - timeStart));
                } catch (Exception ignored) {
                }
            }
        }
        startMicroseconds = timeStart;
        sizeUpdates = packetsNum;
        sizeMicroseconds = timeEnd - timeStart;
        if (!formatPX4) {
            version.put("FW", versionStr.toString());
        }
        seek(0);
    }

    @Override
    public boolean seek(long seekTime) throws IOException {
        position(dataStart);
        lastMsg = null;
        if (seekTime == 0) {      // Seek to start of log
            time = 0;
            return true;
        }
        // Seek to specified timestamp without parsing all messages
        try {
            while (true) {
                long pos = position();
                int msgType = readHeader();
                PX4LogMessageDescription messageDescription = messageDescriptions.get(msgType);
                if (messageDescription == null) {
                    errors.add(new FormatErrorException(pos, "Unknown message type: " + msgType));
                    continue;
                }
                int bodyLen = messageDescription.length - HEADER_LEN;
                try {
                    fillBuffer(bodyLen);
                } catch (EOFException e) {
                    errors.add(new FormatErrorException(pos, "Unexpected end of file"));
                    return false;
                }
                if (formatPX4) {
                    if ("TIME".equals(messageDescription.name)) {
                        PX4LogMessage msg = messageDescription.parseMessage(buffer);
                        long t = msg.getLong(0);
                        if (t > seekTime) {
                            // Time found
                            time = t;
                            position(pos);
                            return true;
                        }
                    } else {
                        // Skip the message
                        buffer.position(buffer.position() + bodyLen);
                    }
                } else {
                    Integer idx = messageDescription.fieldsMap.get(tsName);
                    if (idx != null && idx == 0) {
                        PX4LogMessage msg = messageDescription.parseMessage(buffer);
                        long t = msg.getLong(idx);
                        if (!tsMicros) {
                            t *= 1000;
                        }
                        if (t > seekTime) {
                            // Time found
                            time = t;
                            position(pos);
                            return true;
                        }
                    } else {
                        // Skip the message
                        buffer.position(buffer.position() + bodyLen);
                    }
                }
            }
        } catch (EOFException e) {
            return false;
        }
    }

    // return ts in micros
    private long getAPMTimestamp(PX4LogMessage msg) {
        if (null == tsName) {
            // detect APM's timestamp format on first timestamp seen
            if (null != msg.description.fieldsMap.get("TimeUS")) {
                // new format, timestamps in micros
                tsMicros = true;
                tsName = "TimeUS";
            } else if (null != msg.description.fieldsMap.get("TimeMS")) {
                // old format, timestamps in millis
                tsMicros = false;
                tsName = "TimeMS";
            } else {
                return 0;
            }
        }

        Integer idx = msg.description.fieldsMap.get(tsName);
        if (idx != null && idx == 0) {
            return tsMicros ? msg.getLong(idx) : (msg.getLong(idx) * 1000);
        }
        return 0;
    }

    private void applyMsg(Map<String, Object> update, PX4LogMessage msg) {
        String[] fields = msg.description.fields;

        int num = -1;
        if ("ESC".equals(msg.description.name)) {
            Object val = msg.get("esc_num");
            num = (Integer)msg.get("N");
        }

        // Store all param updates per time slot in a map
        Map<String, Object> param = new HashMap<String, Object>();

        if ("PARM".equals(msg.description.name)) {
            List<Map<String, Object>> store = (List<Map<String, Object>>)update.get(msg.description.name);
            if (store == null) {
                store = new ArrayList<Map<String, Object>>();
                update.put(msg.description.name, store);
            }

            store.add(param);
        }

        for (int i = 0; i < fields.length; i++) {
            String field = fields[i];
            if (!formatPX4) {
                if (i == 0 && tsName.equals(field)) {
                    continue;   // Don't apply timestamp field
                }
            }

            if (num >= 0) {
                update.put(msg.description.name + "." + field + num, msg.get(i));
            } else {
                update.put(msg.description.name + "." + field, msg.get(i));
            }

            if ("PARM".equals(msg.description.name)) {
                param.put(msg.description.name + "." + field, msg.get(i));
            }
        }
    }

    @Override
    public long readUpdate(Map<String, Object> update) throws IOException, FormatErrorException {
        long t = time;
        if (lastMsg != null) {
            applyMsg(update, lastMsg);
            lastMsg = null;
        }
        while (true) {
            PX4LogMessage msg = readMessage();
            if (null == msg) {
                continue;
            }

            if (formatPX4) {
                // PX4 log has TIME message
                if ("TIME".equals(msg.description.name)) {
                    time = msg.getLong(0);
                    if (t == 0) {
                        // The first TIME message
                        t = time;
                        continue;
                    }
                    break;
                }
            } else {
                // APM log doesn't have TIME message
                long ts = getAPMTimestamp(msg);
                if (ts > 0) {
                    time = ts;
                    if (t == 0) {
                        // The first message with timestamp
                        t = time;
                    } else {
                        if (time > t) {
                            // Timestamp changed, leave the message for future
                            lastMsg = msg;
                            break;
                        }
                    }
                }
            }

            applyMsg(update, msg);
        }
        return t;
    }

    @Override
    public Map<String, String> getFields() {
        return fieldsList;
    }

    private void readFormats() throws IOException, FormatErrorException {
        fieldsList = new HashMap<String, String>();

        if (!rememberFormats) {
            // clear static storage as long as we don't need it
            PX4LogMessageDescription.messageDescriptions.clear();

        } else {
            // return with previous formats
            formatPX4 = PX4LogMessageDescription.messageDescriptions.containsKey(PX4LogMessageDescription.TIME.type);
            messageDescriptions.putAll(PX4LogMessageDescription.messageDescriptions);
            return;
        }

        try {
            while (true) {
                if (fillBuffer() < 0) {
                    break;
                }
                while (true) {
                    if (buffer.remaining() < PX4LogMessageDescription.FORMAT.length) {
                        break;
                    }
                    buffer.mark();
                    int msgType = readHeader();     // Don't try to handle errors in formats
                    if (msgType == PX4LogMessageDescription.FORMAT.type) {
                        // Message description
                        try {
                            PX4LogMessageDescription msgDescr = new PX4LogMessageDescription(buffer);
                            messageDescriptions.put(msgDescr.type, msgDescr);

                            // add formats to static storage
                            if (!PX4LogMessageDescription.messageDescriptions.containsKey(msgDescr.type)) {
                                PX4LogMessageDescription.messageDescriptions.put(msgDescr.type, msgDescr);
                            }

                            if ("TIME".equals(msgDescr.name)) {
                                formatPX4 = true;
                            }

                            if (!hideMsgs.contains(msgDescr.name)) {
                                for (int i = 0; i < msgDescr.fields.length; i++) {
                                    String field = msgDescr.fields[i];
                                    String format = formatNames.get(Character.toString(msgDescr.format.charAt(i)));
                                    if (i != 0 || !("TimeMS".equals(field) || "TimeUS".equals(field))) {
                                        fieldsList.put(msgDescr.name + "." + field, format);
                                        //System.out.println(msgDescr.name);
                                    }
                                }
                            }
                        }
                        catch(Exception e) {
                            e.printStackTrace();
                        }
                    } else {
                        // Data message
                        if (formatPX4) {
                            // If it's PX4 log then all formats are read
                            buffer.reset();
                            dataStart = position();
                            return;
                        } else {
                            // APM may have format messages in the middle of log
                            // Skip the message
                            PX4LogMessageDescription messageDescription = messageDescriptions.get(msgType);
                            if (messageDescription == null) {
                                buffer.reset();
                                throw new FormatErrorException(buffer.position(), String.format("Unknown message type while reading formats: ", msgType));
                            }
                            int bodyLen = messageDescription.length - HEADER_LEN;
                            buffer.position(buffer.position() + bodyLen);
                        }
                    }
                }
            }
        } catch (EOFException ignored) {
        }
    }

    private int readHeader() throws IOException {
        long syncErr = -1;
        while (true) {
            fillBuffer(3);
            int p = buffer.position();
            if (buffer.get() != HEADER_HEAD1 || buffer.get() != HEADER_HEAD2) {
                buffer.position(p + 1);
                if (syncErr < 0) {
                    syncErr = position() - 1;
                }
                continue;
            }
            if (syncErr >= 0) {
                if (debugMsg != null) {
                    errors.add(new FormatErrorException(syncErr, String.format("Bad message header after %s", debugMsg.description)));
                } else {
                    errors.add(new FormatErrorException(syncErr, "Bad message header"));
                }
            }
            return buffer.get() & 0xFF;
        }
    }

    /**
     * Read next message from log
     *
     * @return log message
     * @throws IOException  on IO error
     * @throws EOFException on end of stream
     */
    public PX4LogMessage readMessage() throws IOException {
        while (true) {
            int msgType = readHeader();
            long pos = position();
            PX4LogMessageDescription messageDescription = messageDescriptions.get(msgType);
            if (messageDescription == null) {
                errors.add(new FormatErrorException(pos, "Unknown message type: " + msgType));
                continue;
            }
            try {
                fillBuffer(messageDescription.length - HEADER_LEN);
            } catch (EOFException e) {
                errors.add(new FormatErrorException(pos, "Unexpected end of file"));
                throw e;
            }
            debugMsg = messageDescription.parseMessage(buffer);
            return debugMsg;
        }
    }

    @Override
    public List<Exception> getErrors() {
        return errors;
    }

    public void clearErrors() {
        errors.clear();
    }

    public List<LogMessage> getMessages() {
        return logs;
    }

    public static void main(String[] args) throws Exception {
        PX4LogReader reader = new PX4LogReader("test.bin", false);
        long tStart = System.currentTimeMillis();
        while (true) {
            try {
                PX4LogMessage msg = reader.readMessage();
            } catch (EOFException e) {
                break;
            }
        }
        long tEnd = System.currentTimeMillis();
        System.out.println(tEnd - tStart);
        for (Exception e : reader.getErrors()) {
            System.out.println(e.getMessage());
        }
        reader.close();
    }
}
