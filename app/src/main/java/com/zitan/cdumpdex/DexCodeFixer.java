package com.zitan.cdumpdex;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Adler32;

public class DexCodeFixer {
    public static class Result {
        public int dexFiles;
        public int fixedFiles;
        public int applied;
        public int skipped;
        public int lengthMismatch;
    }

    private static class Record {
        int methodIdx;
        byte[] code;
    }

    public static Result fixRecursively(File dir) throws Exception {
        return fixRecursively(dir, false);
    }

    public static Result fixRecursively(File dir, boolean forceMismatch) throws Exception {
        Result total = new Result();
        fixRecursivelyInternal(dir, total, forceMismatch);
        return total;
    }

    private static void fixRecursivelyInternal(File dir, Result total, boolean forceMismatch) throws Exception {
        Result current = fixDirectory(dir, forceMismatch);
        total.dexFiles += current.dexFiles;
        total.fixedFiles += current.fixedFiles;
        total.applied += current.applied;
        total.skipped += current.skipped;
        total.lengthMismatch += current.lengthMismatch;
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory() && !"fix".equals(child.getName()) && !"final".equals(child.getName())) {
                fixRecursivelyInternal(child, total, forceMismatch);
            }
        }
    }

    public static Result fixDirectory(File dir) throws Exception {
        return fixDirectory(dir, false);
    }

    public static Result fixDirectory(File dir, boolean forceMismatch) throws Exception {
        Result result = new Result();
        File[] files = dir.listFiles();
        if (files == null) {
            return result;
        }
        File fixDir = new File(dir, "fix");
        File finalDir = new File(dir, "final");
        if (!fixDir.exists()) {
            fixDir.mkdirs();
        }
        if (!finalDir.exists()) {
            finalDir.mkdirs();
        }
        for (File dexFile : files) {
            String name = dexFile.getName();
            if (!dexFile.isFile() || !name.endsWith(".dex")) {
                continue;
            }
            result.dexFiles++;
            String base = name.substring(0, name.length() - 4);
            File jsonFile = new File(dir, base + "_code.json");
            File finalFile = new File(finalDir, base + ".dex");
            if (!jsonFile.exists()) {
                copy(dexFile, finalFile);
                continue;
            }
            byte[] dex = readAll(dexFile);
            List<Record> records = readRecords(jsonFile);
            Map<Integer, Integer> methodCodeOff = buildMethodCodeOffMap(dex);
            for (Record record : records) {
                Integer codeOffObj = methodCodeOff.get(record.methodIdx);
                if (codeOffObj == null) {
                    result.skipped++;
                    continue;
                }
                int codeOff = codeOffObj;
                if (codeOff <= 0 || codeOff + 16 > dex.length) {
                    result.skipped++;
                    continue;
                }
                int insnsUnits = readU32(dex, codeOff + 12);
                int expected = insnsUnits * 2;
                int insnsStart = codeOff + 16;
                int insnsEnd = insnsStart + expected;
                if (expected < 0 || insnsEnd > dex.length) {
                    result.skipped++;
                    continue;
                }
                if (record.code.length != expected) {
                    result.lengthMismatch++;
                    if (!forceMismatch) {
                        result.skipped++;
                        continue;
                    }
                    // 强制模式：截断或补零
                    int writeLen = Math.min(expected, record.code.length);
                    System.arraycopy(record.code, 0, dex, insnsStart, writeLen);
                    if (writeLen < expected) {
                        for (int i = writeLen; i < expected; i++) dex[insnsStart + i] = 0;
                    }
                    result.applied++;
                    continue;
                }
                System.arraycopy(record.code, 0, dex, insnsStart, expected);
                result.applied++;
            }
            recalcHeader(dex);
            File fixedFile = new File(fixDir, base + "_fix.dex");
            writeAll(fixedFile, dex);
            copy(fixedFile, finalFile);
            result.fixedFiles++;
        }
        return result;
    }

    private static Map<Integer, Integer> buildMethodCodeOffMap(byte[] dex) throws Exception {
        Map<Integer, Integer> result = new HashMap<>();
        if (dex.length < 112 || dex[0] != 'd' || dex[1] != 'e' || dex[2] != 'x' || dex[3] != '\n') {
            return result;
        }
        int classDefsSize = readU32(dex, 96);
        int classDefsOff = readU32(dex, 100);
        for (int i = 0; i < classDefsSize; i++) {
            int classDefOff = classDefsOff + i * 32;
            if (classDefOff < 0 || classDefOff + 32 > dex.length) {
                continue;
            }
            int classDataOff = readU32(dex, classDefOff + 24);
            if (classDataOff == 0 || classDataOff >= dex.length) {
                continue;
            }
            IntRef pos = new IntRef(classDataOff);
            int staticFields = readUleb128(dex, pos);
            int instanceFields = readUleb128(dex, pos);
            int directMethods = readUleb128(dex, pos);
            int virtualMethods = readUleb128(dex, pos);
            skipFields(dex, pos, staticFields);
            skipFields(dex, pos, instanceFields);
            readMethods(dex, pos, directMethods, result);
            readMethods(dex, pos, virtualMethods, result);
        }
        return result;
    }

    private static void skipFields(byte[] dex, IntRef pos, int count) throws Exception {
        for (int i = 0; i < count; i++) {
            readUleb128(dex, pos);
            readUleb128(dex, pos);
        }
    }

    private static void readMethods(byte[] dex, IntRef pos, int count, Map<Integer, Integer> out) throws Exception {
        int methodIdx = 0;
        for (int i = 0; i < count; i++) {
            methodIdx += readUleb128(dex, pos);
            readUleb128(dex, pos);
            int codeOff = readUleb128(dex, pos);
            out.put(methodIdx, codeOff);
        }
    }

    private static List<Record> readRecords(File file) throws Exception {
        JSONArray array = new JSONArray(new String(readAll(file), StandardCharsets.UTF_8));
        List<Record> records = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.getJSONObject(i);
            Record record = new Record();
            record.methodIdx = obj.getInt("method_idx");
            record.code = hexToBytes(obj.getString("code"));
            records.add(record);
        }
        return records;
    }

    private static int readUleb128(byte[] data, IntRef pos) throws Exception {
        int result = 0;
        int shift = 0;
        for (int i = 0; i < 5; i++) {
            if (pos.value >= data.length) {
                throw new Exception("ULEB128 out of bounds");
            }
            int b = data[pos.value++] & 0xff;
            result |= (b & 0x7f) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
            shift += 7;
        }
        throw new Exception("invalid ULEB128");
    }

    private static void recalcHeader(byte[] dex) throws Exception {
        if (dex.length < 32) {
            return;
        }
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] sig = sha1.digest(slice(dex, 32, dex.length - 32));
        System.arraycopy(sig, 0, dex, 12, 20);
        Adler32 adler32 = new Adler32();
        adler32.update(dex, 12, dex.length - 12);
        putU32(dex, 8, (int) adler32.getValue());
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static int readU32(byte[] data, int off) {
        return ByteBuffer.wrap(data, off, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static void putU32(byte[] data, int off, int value) {
        data[off] = (byte) value;
        data[off + 1] = (byte) (value >> 8);
        data[off + 2] = (byte) (value >> 16);
        data[off + 3] = (byte) (value >> 24);
    }

    private static byte[] slice(byte[] data, int off, int len) {
        byte[] out = new byte[len];
        System.arraycopy(data, off, out, 0, len);
        return out;
    }

    private static byte[] readAll(File file) throws Exception {
        FileInputStream in = new FileInputStream(file);
        byte[] data = new byte[(int) file.length()];
        int off = 0;
        while (off < data.length) {
            int n = in.read(data, off, data.length - off);
            if (n < 0) {
                break;
            }
            off += n;
        }
        in.close();
        return data;
    }

    private static void writeAll(File file, byte[] data) throws Exception {
        FileOutputStream out = new FileOutputStream(file);
        out.write(data);
        out.close();
    }

    private static void copy(File src, File dst) throws Exception {
        writeAll(dst, readAll(src));
    }

    private static class IntRef {
        int value;

        IntRef(int value) {
            this.value = value;
        }
    }
}
