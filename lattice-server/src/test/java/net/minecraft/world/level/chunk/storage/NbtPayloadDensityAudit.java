package net.minecraft.world.level.chunk.storage;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;
import net.minecraft.nbt.CompoundTag;

public final class NbtPayloadDensityAudit {
    private static final Pattern REGION_NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("Specify chunk region files explicitly");
        System.out.printf("NBT density audit java=%s compressor=JDK-zlib-level-6 mode=read-only parity=structural-only%n",
                System.getProperty("java.version"));
        System.out.println("kind,region,chunk-x,chunk-z,policy,sections,air-fields,plains-fields,omitted-field-bytes,"
                + "nbt-bytes,compressed-bytes,sectors,external,external-bytes,status,data-version");
        for (String argument : args) audit(Path.of(argument).toRealPath());
        System.out.printf("nbt-density-complete regions=%d structural-parity=passed production-reader=not-tested%n", args.length);
    }

    static void audit(Path path) throws Exception {
        var name = REGION_NAME.matcher(path.getFileName().toString());
        if (!name.matches()) throw new IllegalArgumentException("Not a region name: " + path);
        int regionX = Integer.parseInt(name.group(1)), regionZ = Integer.parseInt(name.group(2));
        String beforeHash = hash(path);
        long fileBytes = Files.size(path);
        long[][] totals = new long[NbtPayloadDensityFixture.Policy.values().length][4];
        int chunks = 0;
        try (var region = new RandomAccessFile(path.toFile(), "r")) {
            if (fileBytes < 8192 || fileBytes % 4096 != 0) throw new IOException("Invalid region size: " + path);
            int[] locations = new int[1024];
            for (int i = 0; i < locations.length; i++) locations[i] = region.readInt();
            var used = new java.util.BitSet();
            used.set(0, 2);
            for (int index = 0; index < locations.length; index++) {
                int location = locations[index];
                if (location == 0) continue;
                int sector = location >>> 8, count = location & 255;
                long start = sector * 4096L;
                if (sector < 2 || count == 0 || start + count * 4096L > fileBytes) {
                    throw new IOException("Invalid location: " + path + " index=" + index);
                }
                int overlap = used.nextSetBit(sector);
                if (overlap >= 0 && overlap < sector + count) throw new IOException("Overlapping sectors: " + path);
                used.set(sector, sector + count);
                region.seek(start);
                int length = region.readInt();
                int type = region.readUnsignedByte();
                if ((type & 127) != 2) throw new IOException("Expected zlib type 2, got " + type + " at " + path);
                int x = Math.addExact(Math.multiplyExact(regionX, 32), index % 32);
                int z = Math.addExact(Math.multiplyExact(regionZ, 32), index / 32);
                byte[] compressed;
                boolean external = (type & 128) != 0;
                if (external) {
                    if (length != 1 || count != 1) throw new IOException("Invalid external stub: " + path);
                    Path payload = path.resolveSibling("c." + x + "." + z + ".mcc");
                    String payloadHash = hash(payload);
                    compressed = Files.readAllBytes(payload);
                    if (!payloadHash.equals(hash(payload))) throw new IOException("External payload changed: " + payload);
                    System.out.printf("source-external,path=%s,sha256=%s%n", payload, payloadHash);
                } else {
                    if (length <= 1 || length + 4L > count * 4096L) throw new IOException("Invalid payload length: " + path);
                    compressed = new byte[length - 1];
                    region.readFully(compressed);
                }
                byte[] raw = NbtPayloadDensityFixture.inflate(compressed);
                CompoundTag original = NbtPayloadDensityFixture.read(raw);
                if (original.getInt("DataVersion").orElse(0) < 2842
                        || original.getInt("xPos").orElseThrow() != x || original.getInt("zPos").orElseThrow() != z) {
                    throw new IOException("Unsupported schema or coordinate mismatch: " + path + " index=" + index);
                }
                String identity = csv(path.toString());
                String status = csv(original.getString("Status").orElseThrow());
                int version = original.getInt("DataVersion").orElseThrow();
                System.out.printf(Locale.ROOT, "chunk,%s,%d,%d,stored,%d,0,0,0,%d,%d,%d,%s,%d,%s,%d%n",
                        identity, x, z, original.getList("sections").orElseThrow().size(),
                        raw.length, compressed.length, count, external, external ? compressed.length : 0, status, version);
                for (var policy : NbtPayloadDensityFixture.Policy.values()) {
                    var candidate = NbtPayloadDensityFixture.candidate(original, policy);
                    byte[] encoded = NbtPayloadDensityFixture.serialize(candidate.tag());
                    byte[] zipped = NbtPayloadDensityFixture.compress(encoded);
                    CompoundTag decoded = NbtPayloadDensityFixture.read(NbtPayloadDensityFixture.inflate(zipped));
                    if (!decoded.equals(candidate.tag())) throw new AssertionError("Compression round-trip changed NBT");
                    NbtPayloadDensityFixture.assertRestored(original, decoded);
                    var layout = NbtPayloadDensityFixture.layout(zipped.length);
                    System.out.printf(Locale.ROOT, "chunk,%s,%d,%d,%s,%d,%d,%d,%d,%d,%d,%d,%s,%d,%s,%d%n",
                            identity, x, z, policy, original.getList("sections").orElseThrow().size(),
                            candidate.airFields(), candidate.plainsFields(), candidate.omittedFieldBytes(),
                            encoded.length, zipped.length, layout.sectors(), layout.external(), layout.externalBytes(), status, version);
                    long[] total = totals[policy.ordinal()];
                    total[0] += encoded.length;
                    total[1] += zipped.length;
                    total[2] += layout.sectors();
                    total[3] += layout.externalBytes();
                }
                chunks++;
            }
        }
        if (chunks == 0) throw new IOException("No chunk entries: " + path);
        if (!beforeHash.equals(hash(path))) throw new IOException("Source region changed: " + path);
        System.out.printf("source,path=%s,sha256=%s,file-bytes=%d,chunks=%d%n", path, beforeHash, fileBytes, chunks);
        for (var policy : NbtPayloadDensityFixture.Policy.values()) {
            long[] total = totals[policy.ordinal()];
            System.out.printf("region-summary,path=%s,policy=%s,nbt-bytes=%d,compressed-bytes=%d,"
                    + "compact-region-bytes=%d,external-bytes=%d%n", path, policy,
                    total[0], total[1], 8192L + total[2] * 4096L, total[3]);
        }
    }

    private static String hash(Path path) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536];
            for (int count; (count = input.read(buffer)) != -1;) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String csv(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
