package com.latticemc.lattice.bootstrap;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

/**
 * Build-time mixin applicator. Run after compileJava to apply mixin transformations to target classes.
 * Usage: java -cp &lt;classpath&gt; com.latticemc.lattice.bootstrap.MixinApplicator &lt;classesDir&gt;
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
        Map<String, byte[]> transformedClasses = new LinkedHashMap<>();

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
                    transformedClasses.put(className, result);
                    transformed++;
                    System.out.println("[MixinApplicator] Transformed: " + className);
                }
            }
        }

        Set<String> allMissingRefs = new LinkedHashSet<>();
        for (Map.Entry<String, byte[]> entry : transformedClasses.entrySet()) {
            collectMissingClassRefs(entry.getValue(), classesDir, allMissingRefs);
        }

        int generated = 0;
        for (String missingName : allMissingRefs) {
            Path innerFile = classesDir.resolve(missingName.replace('.', File.separatorChar) + ".class");
            if (Files.exists(innerFile)) continue;

            try {
                ClassNode node = new ClassNode();
                boolean ok = transformer.generateClass(env, missingName, node);
                if (ok && node.name != null) {
                    org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
                    node.accept(cw);
                    byte[] bytes = cw.toByteArray();
                    Files.createDirectories(innerFile.getParent());
                    Files.write(innerFile, bytes);
                    generated++;
                    System.out.println("[MixinApplicator] Generated synthetic class: " + missingName);
                } else {
                    System.err.println("[MixinApplicator] WARNING: generateClass returned false for: " + missingName);
                }
            } catch (Exception e) {
                System.err.println("[MixinApplicator] WARNING: Failed to generate " + missingName + ": " + e.getMessage());
            }
        }

        System.out.printf("[MixinApplicator] Done. Scanned %d, transformed %d, generated %d synthetic classes.%n",
                scanned, transformed, generated);
    }

    private static void collectMissingClassRefs(byte[] classBytes, Path classesDir, Set<String> result) {
        try {
            ClassReader cr = new ClassReader(classBytes);
            Set<String> allRefs = new LinkedHashSet<>();
            cr.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public void visitInnerClass(String name, String outerName, String innerName, int access) {
                    allRefs.add(name);
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitTypeInsn(int opcode, String type) {
                            allRefs.add(type);
                        }

                        @Override
                        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                            allRefs.add(owner);
                            addTypeRefsFromDescriptor(descriptor, allRefs);
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                            allRefs.add(owner);
                            addTypeRefsFromDescriptor(descriptor, allRefs);
                        }

                        @Override
                        public void visitLdcInsn(Object value) {
                            if (value instanceof Type t && t.getSort() == Type.OBJECT) {
                                allRefs.add(t.getInternalName());
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_FRAMES);

            for (String ref : allRefs) {
                if (ref.startsWith("net/minecraft/") && ref.contains("$")) {
                    Path refFile = classesDir.resolve(ref.replace('/', File.separatorChar) + ".class");
                    if (!Files.exists(refFile)) {
                        result.add(ref.replace('/', '.'));
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void addTypeRefsFromDescriptor(String descriptor, Set<String> out) {
        for (Type type : Type.getArgumentTypes(descriptor)) {
            if (type.getSort() == Type.OBJECT) out.add(type.getInternalName());
        }
        Type returnType = Type.getReturnType(descriptor);
        if (returnType.getSort() == Type.OBJECT) out.add(returnType.getInternalName());
    }
}
