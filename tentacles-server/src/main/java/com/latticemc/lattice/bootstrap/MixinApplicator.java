package com.latticemc.lattice.bootstrap;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

/**
 * Build-time mixin applicator. Run after compileJava to apply mixin transformations to target classes.
 * Usage: java -cp <classpath> com.latticemc.lattice.bootstrap.MixinApplicator <classesDir>
 */
public final class MixinApplicator {

    private MixinApplicator() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: MixinApplicator <classesDir>");
            System.exit(1);
        }

        Path classesDir = Path.of(args[0]);
        if (!Files.isDirectory(classesDir)) {
            System.err.println("[MixinApplicator] Classes directory does not exist: " + classesDir);
            System.exit(1);
        }

        MixinBootstrap.init();
        MixinEnvironment env = MixinEnvironment.getDefaultEnvironment();
        env.setSide(MixinEnvironment.Side.SERVER);
        Mixins.addConfiguration("mixin.lattice.json",
            (org.spongepowered.asm.mixin.extensibility.IMixinConfigSource) null);

        IMixinTransformer transformer = (IMixinTransformer) env.getActiveTransformer();
        if (transformer == null) {
            System.err.println("[MixinApplicator] ERROR: No active transformer after bootstrap!");
            System.exit(1);
        }

        int transformed = 0;
        int scanned = 0;

        try (var stream = Files.walk(classesDir)) {
            for (Path classFile : stream.filter(p -> p.toString().endsWith(".class")).toList()) {
                scanned++;
                String relativePath = classesDir.relativize(classFile).toString();
                String className = relativePath
                    .replace(File.separatorChar, '.')
                    .replace('/', '.')
                    .replaceAll("\\.class$", "");

                if (className.startsWith("com.latticemc.lattice.mixin.")) continue;

                byte[] original = Files.readAllBytes(classFile);
                byte[] result = transformer.transformClass(env, className, original);

                if (result != null && result != original) {
                    Files.write(classFile, result);
                    transformed++;
                    System.out.println("[MixinApplicator] Transformed: " + className);
                }
            }
        }

        System.out.printf("[MixinApplicator] Done. Scanned %d classes, transformed %d.%n", scanned, transformed);
    }
}
