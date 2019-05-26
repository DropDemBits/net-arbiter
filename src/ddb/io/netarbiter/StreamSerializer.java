package ddb.io.netarbiter;

// All data is written in network order (big-endian) relative to little-endian x86
public class StreamSerializer {

    public static boolean appendInt (byte buffer[], int offset, int data) {
        if (offset + Integer.BYTES > buffer.length)
            return false;

        buffer [offset + 3] = (byte) ((data >> 0) & 0xFF);
        buffer [offset + 2] = (byte) ((data >> 8) & 0xFF);
        buffer [offset + 1] = (byte) ((data >> 16) & 0xFF);
        buffer [offset + 0] = (byte) ((data >> 24) & 0xFF);

        return true;
    }

    public static int getInt (byte buffer[], int offset) {
        if (offset + Integer.BYTES > buffer.length)
            return 0;

        return    buffer[offset + 3] << 0
                | buffer[offset + 2] << 8
                | buffer[offset + 1] << 16
                | buffer[offset + 0] << 24;
    }

    public static int getSize (Object data) {
        Class<?> clz = data.getClass();

        if (!clz.isPrimitive() && clz != String.class)
            return 0;

        if (clz == Integer.TYPE) return Integer.BYTES;

        // Void type or any unhandled primative type
        return 0;
    }

}
