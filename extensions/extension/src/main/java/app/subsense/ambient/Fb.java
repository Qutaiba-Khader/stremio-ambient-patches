package app.subsense.ambient;

import java.util.ArrayList;
import java.util.List;

/**
 * HyperHDR's flatbuffer messages (hyperhdr_request.fbs), written by hand so the extension needs no library:
 *
 *   table Register { origin:string (required); priority:int; }
 *   table RawImage { data:[ubyte]; width:int = -1; height:int = -1; }
 *   union ImageType { RawImage, NV12Image }
 *   table Image { data:ImageType (required); duration:int = -1; }
 *   table Clear { priority:int; }
 *   union Command { Color, Image, Clear, Register }
 *   table Request { command:Command (required); }
 *
 * A union takes two slots (its type byte, then the table). Layout: everything a table points to comes after it, every
 * vtable just before its table, so all offsets are positive; tables and vectors are 4-aligned. On the wire every
 * message is preceded by its length (4 bytes, big-endian).
 */
final class Fb {
    private Fb() {}

    static final int COMMAND_IMAGE = 2, COMMAND_CLEAR = 3, COMMAND_REGISTER = 4;

    /** A table field: a byte, an int, or a reference to a table / byte vector / string written after it. */
    private static final class Field {
        final int slot, size; // size 1 or 4
        final int value;
        final Object ref;     // Table, byte[] (vector) or String

        Field(int slot, int size, int value, Object ref) {
            this.slot = slot;
            this.size = size;
            this.value = value;
            this.ref = ref;
        }
    }

    private static final class Table {
        final List<Field> fields = new ArrayList<>();

        Table b(int slot, int v) { fields.add(new Field(slot, 1, v, null)); return this; }
        Table i(int slot, int v) { fields.add(new Field(slot, 4, v, null)); return this; }
        Table ref(int slot, Object r) { fields.add(new Field(slot, 4, 0, r)); return this; }
    }

    private static final class Out {
        byte[] buf = new byte[256];
        int len;

        void ensure(int n) {
            if (len + n > buf.length) buf = java.util.Arrays.copyOf(buf, Math.max(buf.length * 2, len + n));
        }

        void pad(int align) { while (len % align != 0) u8(0); }
        void u8(int v) { ensure(1); buf[len++] = (byte) v; }
        void u16(int v) { u8(v); u8(v >>> 8); }
        void i32(int v) { u16(v); u16(v >>> 16); }

        void putI32(int at, int v) {
            buf[at] = (byte) v;
            buf[at + 1] = (byte) (v >>> 8);
            buf[at + 2] = (byte) (v >>> 16);
            buf[at + 3] = (byte) (v >>> 24);
        }
    }

    /** Writes a table (vtable first) and then what it points to; returns the table's position. */
    private static int write(Out o, Table t) {
        int slots = 0;
        for (Field f : t.fields) slots = Math.max(slots, f.slot + 1);
        // inline layout: soffset, then the 4-byte fields, then the bytes (no padding needed between them)
        int[] at = new int[slots];
        int size = 4;
        for (Field f : t.fields) if (f.size == 4) { at[f.slot] = size; size += 4; }
        for (Field f : t.fields) if (f.size == 1) { at[f.slot] = size; size += 1; }
        int inline = size;
        int vtSize = 4 + 2 * slots;
        // the vtable is 2-aligned; padding before it so that the table after it is 4-aligned
        o.pad(2);
        if ((o.len + vtSize) % 4 != 0) o.u16(0);
        int vt = o.len;
        o.u16(vtSize);
        o.u16(inline);
        for (int s = 0; s < slots; s++) o.u16(at[s]);
        int table = o.len;
        o.i32(table - vt);
        for (Field f : t.fields) if (f.size == 4) o.i32(f.value);
        for (Field f : t.fields) if (f.size == 1) o.u8(f.value);
        for (Field f : t.fields) {
            if (f.ref == null) continue;
            int target;
            if (f.ref instanceof Table) {
                target = write(o, (Table) f.ref);
            } else {
                byte[] data = f.ref instanceof String ? utf8((String) f.ref) : (byte[]) f.ref;
                o.pad(4);
                target = o.len;
                o.i32(data.length);
                o.ensure(data.length + 1);
                System.arraycopy(data, 0, o.buf, o.len, data.length);
                o.len += data.length;
                if (f.ref instanceof String) o.u8(0);
            }
            int field = table + at[f.slot];
            o.putI32(field, target - field);
        }
        return table;
    }

    private static byte[] utf8(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** A Request with one command, framed for the wire. */
    private static byte[] request(int type, Table command) {
        Out o = new Out();
        o.i32(0); // root offset, set below
        int root = write(o, new Table().b(0, type).ref(1, command));
        o.putI32(0, root);
        o.pad(4);
        byte[] out = new byte[o.len + 4];
        out[0] = (byte) (o.len >>> 24);
        out[1] = (byte) (o.len >>> 16);
        out[2] = (byte) (o.len >>> 8);
        out[3] = (byte) o.len;
        System.arraycopy(o.buf, 0, out, 4, o.len);
        return out;
    }

    static byte[] register(String origin, int priority) {
        return request(COMMAND_REGISTER, new Table().ref(0, origin).i(1, priority));
    }

    static byte[] image(byte[] rgb, int w, int h, int durationMs) {
        Table raw = new Table().ref(0, rgb).i(1, w).i(2, h);
        return request(COMMAND_IMAGE, new Table().b(0, 1).ref(1, raw).i(2, durationMs));
    }

    static byte[] clear(int priority) {
        return request(COMMAND_CLEAR, new Table().i(0, priority));
    }
}
