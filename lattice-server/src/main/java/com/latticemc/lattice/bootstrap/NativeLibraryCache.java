package com.latticemc.lattice.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Writes native code into a private, one-shot extraction directory. */
final class NativeLibraryCache {
    static final long MAX_NATIVE_BYTES = 256L * 1024L * 1024L;
    private static final long MAX_ACL_OUTPUT_BYTES = 64L * 1024L;
    private static final int COPY_BUFFER_SIZE = 16 * 1024;

    private NativeLibraryCache() {}

    static Path extract(Path parent, String libFile, InputStream in) throws IOException {
        return extract(parent, libFile, in, null);
    }

    static Path extract(Path parent, String libFile, InputStream in, String expectedDigest) throws IOException {
        validateLibraryName(libFile);
        if (in == null) {
            throw new NullPointerException("in");
        }
        if (expectedDigest != null && !LatticeNativeLoader.isSha256(expectedDigest)) {
            throw new IOException("invalid trusted SHA-256 digest for " + libFile);
        }

        final Path absolute = parent.toAbsolutePath().normalize();
        final Path existing = nearestExisting(absolute);
        final UserPrincipal user = currentUser(absolute);
        validateAncestors(existing.toRealPath(), user);
        Files.createDirectories(absolute);
        final Path resolved = absolute.toRealPath();
        validateAncestors(resolved, user);

        final FileAttribute<?> permissions = privateDirectoryAttribute(resolved, user);
        final Path directory = Files.createTempDirectory(resolved, "lattice-native-", permissions);
        final Path target = directory.resolve(libFile);
        try {
            validateCreatedDirectory(directory, user);
            final String actualDigest = copyBounded(in, target);
            if (expectedDigest != null && !actualDigest.equalsIgnoreCase(expectedDigest)) {
                throw new IOException("SHA-256 mismatch for " + libFile + ": trusted "
                        + expectedDigest.toLowerCase(Locale.ROOT) + ", got " + actualDigest);
            }
            directory.toFile().deleteOnExit();
            target.toFile().deleteOnExit();
            return target;
        } catch (IOException | RuntimeException failure) {
            try {
                Files.deleteIfExists(target);
                Files.deleteIfExists(directory);
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private static void validateLibraryName(String libFile) throws IOException {
        if (libFile == null || libFile.isEmpty() || libFile.equals(".") || libFile.equals("..")
                || libFile.indexOf('/') >= 0 || libFile.indexOf('\\') >= 0
                || !Path.of(libFile).getFileName().toString().equals(libFile)) {
            throw new IOException("Native library name must be a filename");
        }
    }

    private static UserPrincipal currentUser(Path path) throws IOException {
        return path.getFileSystem().getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
    }

    private static Path nearestExisting(Path path) throws IOException {
        Path existing = path;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new IOException("Cannot find an existing native extraction ancestor: " + path);
        }
        return existing;
    }

    private static FileAttribute<?> privateDirectoryAttribute(Path path, UserPrincipal user) throws IOException {
        final Set<String> views = path.getFileSystem().supportedFileAttributeViews();
        if (views.contains("posix")) {
            return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
        }
        if (views.contains("acl")) {
            final List<AclEntry> acl = List.of(AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(user)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                    .setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT)
                    .build());
            return new FileAttribute<List<AclEntry>>() {
                @Override
                public String name() { return "acl:acl"; }

                @Override
                public List<AclEntry> value() { return acl; }
            };
        }
        throw new IOException("Native extraction requires POSIX permissions or ACLs");
    }

    private static void validateCreatedDirectory(Path directory, UserPrincipal user) throws IOException {
        final Set<String> views = directory.getFileSystem().supportedFileAttributeViews();
        if (views.contains("acl")) {
            final Set<UserPrincipal> trusted = trustedWindowsPrincipals(directory, user);
            final AclFileAttributeView view = Files.getFileAttributeView(directory, AclFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (view == null) {
                throw new IOException("Cannot inspect native extraction ACL: " + directory);
            }
            rejectDangerousAclEntries(view.getAcl(), trusted, "Unsafe inherited native extraction ACL: " + directory);
        }
    }

    private static String copyBounded(InputStream in, Path target) throws IOException {
        try (OutputStream out = Files.newOutputStream(target)) {
            final MessageDigest digest = sha256();
            final byte[] buffer = new byte[COPY_BUFFER_SIZE];
            long total = 0;
            while (true) {
                final int read = in.read(buffer);
                if (read < 0) break;
                if (read == 0) continue;
                total += read;
                if (total > MAX_NATIVE_BYTES) {
                    throw new IOException("native library exceeds " + MAX_NATIVE_BYTES + " bytes");
                }
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
            }
            return hex(digest.digest());
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String hex(byte[] bytes) {
        final StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }

    // Darwin's NIO provider exposes POSIX mode bits but may hide extended ACLs.
    private static void validateMacAcl(Path directory) throws IOException {
        final ProcessBuilder builder = new ProcessBuilder("/bin/ls", "-lde", directory.toString());
        builder.environment().put("LC_ALL", "C");
        final Process process = builder.redirectErrorStream(true).start();
        final String output;
        try (InputStream stream = process.getInputStream()) {
            output = readBounded(stream, MAX_ACL_OUTPUT_BYTES);
        } catch (IOException failure) {
            process.destroyForcibly();
            throw new IOException("Cannot inspect native extraction ACL: " + directory, failure);
        }
        try {
            if (process.waitFor() != 0) {
                throw new IOException("Cannot inspect native extraction ACL: " + directory);
            }
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while checking native extraction ACL", interrupted);
        }
        for (String line : output.lines().skip(1).toList()) {
            if (!line.matches("\\s*\\d+: .* (allow|deny) .*")) {
                throw new IOException("Unrecognized native extraction ACL: " + directory);
            }
            if (line.contains(" allow ")) {
                String permissions = line.substring(line.indexOf(" allow ") + 7);
                for (String permission : permissions.split(",")) {
                    switch (permission.trim()) {
                        case "read", "list", "search", "execute", "readattr", "readextattr", "readsecurity",
                                "file_inherit", "directory_inherit", "limit_inherit", "only_inherit", "inherited":
                            break;
                        default:
                            throw new IOException("Writable native extraction ACL: " + directory);
                    }
                }
            }
        }
    }

    private static String readBounded(InputStream in, long limit) throws IOException {
        final StringBuilder output = new StringBuilder();
        final byte[] buffer = new byte[COPY_BUFFER_SIZE];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) >= 0) {
            if (read == 0) continue;
            total += read;
            if (total > limit) throw new IOException("native extraction ACL output exceeds " + limit + " bytes");
            output.append(new String(buffer, 0, read, java.nio.charset.StandardCharsets.UTF_8));
        }
        return output.toString();
    }

    private static Set<UserPrincipal> trustedWindowsPrincipals(Path path, UserPrincipal user) throws IOException {
        final Set<UserPrincipal> trusted = new HashSet<>();
        trusted.add(user);
        final Path root = path.getRoot();
        if (root != null) {
            final AclFileAttributeView rootView = Files.getFileAttributeView(root, AclFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (rootView == null) {
                throw new IOException("Cannot inspect native extraction filesystem ACL: " + root);
            }
            trusted.add(rootView.getOwner());
            for (AclEntry entry : rootView.getAcl()) {
                if (entry.type() == AclEntryType.ALLOW
                        && entry.permissions().contains(AclEntryPermission.WRITE_ACL)
                        && entry.permissions().contains(AclEntryPermission.WRITE_OWNER)
                        && entry.permissions().contains(AclEntryPermission.DELETE_CHILD)) {
                    trusted.add(entry.principal());
                }
            }
        }
        trusted.remove(null);
        return trusted;
    }

    private static void rejectDangerousAclEntries(List<AclEntry> acl, Set<UserPrincipal> trusted, String message)
            throws IOException {
        for (AclEntry entry : acl) {
            if (entry.type() == AclEntryType.ALLOW && !trusted.contains(entry.principal())
                    && !entry.flags().contains(AclEntryFlag.INHERIT_ONLY)
                    && entry.permissions().stream().anyMatch(NativeLibraryCache::dangerousPermission)) {
                throw new IOException(message);
            }
        }
    }

    private static boolean dangerousPermission(AclEntryPermission permission) {
        return permission == AclEntryPermission.WRITE_DATA
                || permission == AclEntryPermission.APPEND_DATA
                || permission == AclEntryPermission.DELETE
                || permission == AclEntryPermission.DELETE_CHILD
                || permission == AclEntryPermission.WRITE_ACL
                || permission == AclEntryPermission.WRITE_OWNER;
    }

    static void validateAncestors(Path path, UserPrincipal user) throws IOException {
        final Set<String> views = path.getFileSystem().supportedFileAttributeViews();
        final List<Path> ancestors = new ArrayList<>();
        for (Path directory = path; directory != null; directory = directory.getParent()) ancestors.add(directory);
        Collections.reverse(ancestors);
        if (views.contains("posix") && views.contains("unix")) {
            final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            final boolean mac = os.contains("mac") || os.contains("darwin");
            for (Path directory : ancestors) {
                if (mac) validateMacAcl(directory);
                final int uid = (int) Files.getAttribute(directory, "unix:uid", LinkOption.NOFOLLOW_LINKS);
                final int mode = (int) Files.getAttribute(directory, "unix:mode", LinkOption.NOFOLLOW_LINKS);
                if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                        || (uid != 0 && !Files.getOwner(directory, LinkOption.NOFOLLOW_LINKS).equals(user))
                        || ((mode & 0022) != 0 && (mode & 01000) == 0)) {
                    throw new IOException("Unsafe native extraction ancestor: " + directory);
                }
            }
            return;
        }
        if (views.contains("acl")) {
            final Set<UserPrincipal> trusted = trustedWindowsPrincipals(path, user);
            for (Path directory : ancestors) {
                final AclFileAttributeView view = Files.getFileAttributeView(directory, AclFileAttributeView.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || view == null
                        || !trusted.contains(view.getOwner())) {
                    throw new IOException("Unsafe native extraction ancestor: " + directory);
                }
            }
            return;
        }
        throw new IOException("Cannot verify native extraction directory permissions");
    }
}
