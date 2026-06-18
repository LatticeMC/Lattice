package com.latticemc.lattice.bootstrap;

import java.io.InputStream;
import java.net.URL;
import java.util.Collection;
import java.util.Collections;
import org.spongepowered.asm.launch.platform.container.ContainerHandleVirtual;
import org.spongepowered.asm.launch.platform.container.IContainerHandle;
import org.spongepowered.asm.logging.ILogger;
import org.spongepowered.asm.logging.LoggerAdapterDefault;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.service.IClassBytecodeProvider;
import org.spongepowered.asm.service.IClassProvider;
import org.spongepowered.asm.service.IClassTracker;
import org.spongepowered.asm.service.IMixinAuditTrail;
import org.spongepowered.asm.service.ITransformer;
import org.spongepowered.asm.service.ITransformerProvider;
import org.spongepowered.asm.service.MixinServiceAbstract;
import org.objectweb.asm.tree.ClassNode;

public final class MixinServiceStandalone extends MixinServiceAbstract {

    private static final MixinServiceStandalone INSTANCE = new MixinServiceStandalone();

    public static MixinServiceStandalone getInstance() {
        return INSTANCE;
    }

    private MixinServiceStandalone() {}

    @Override
    public String getName() {
        return "Standalone";
    }

    @Override
    public boolean isValid() {
        return true;
    }

    @Override
    public IClassProvider getClassProvider() {
        return new IClassProvider() {
            @Override
            public URL[] getClassPath() {
                return new URL[0];
            }

            @Override
            public Class<?> findClass(String name) throws ClassNotFoundException {
                return Class.forName(name, false, Thread.currentThread().getContextClassLoader());
            }

            @Override
            public Class<?> findClass(String name, boolean initialize) throws ClassNotFoundException {
                return Class.forName(name, initialize, Thread.currentThread().getContextClassLoader());
            }

            @Override
            public Class<?> findAgentClass(String name, boolean initialize) throws ClassNotFoundException {
                return Class.forName(name, initialize, Thread.currentThread().getContextClassLoader());
            }
        };
    }

    @Override
    public IClassBytecodeProvider getBytecodeProvider() {
        return new IClassBytecodeProvider() {
            @Override
            public ClassNode getClassNode(String name) throws ClassNotFoundException {
                return getClassNode(name, false, 0);
            }

            @Override
            public ClassNode getClassNode(String name, boolean load) throws ClassNotFoundException {
                return getClassNode(name, load, 0);
            }

            @Override
            public ClassNode getClassNode(String name, boolean load, int flags) throws ClassNotFoundException {
                ClassLoader cl = Thread.currentThread().getContextClassLoader();
                String resourcePath = name.replace('.', '/') + ".class";
                try (InputStream is = cl.getResourceAsStream(resourcePath)) {
                    if (is == null) {
                        throw new ClassNotFoundException(name);
                    }
                    ClassNode node = new ClassNode();
                    org.objectweb.asm.ClassReader reader = new org.objectweb.asm.ClassReader(is);
                    reader.accept(node, flags);
                    return node;
                } catch (ClassNotFoundException cnfe) {
                    throw cnfe;
                } catch (Exception e) {
                    throw new ClassNotFoundException(name, e);
                }
            }
        };
    }

    @Override
    public ITransformerProvider getTransformerProvider() {
        return new ITransformerProvider() {
            @Override
            public Collection<ITransformer> getTransformers() {
                return Collections.emptyList();
            }

            @Override
            public Collection<ITransformer> getDelegatedTransformers() {
                return Collections.emptyList();
            }

            @Override
            public void addTransformerExclusion(String exclusion) {}
        };
    }

    @Override
    public IClassTracker getClassTracker() {
        return new IClassTracker() {
            @Override
            public void registerInvalidClass(String className) {}

            @Override
            public boolean isClassLoaded(String className) {
                return false;
            }

            @Override
            public String getClassRestrictions(String className) {
                return "";
            }
        };
    }

    @Override
    public IMixinAuditTrail getAuditTrail() {
        return null;
    }

    @Override
    public Collection<String> getPlatformAgents() {
        return Collections.emptyList();
    }

    @Override
    public IContainerHandle getPrimaryContainer() {
        return new ContainerHandleVirtual("Standalone");
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        return cl != null ? cl.getResourceAsStream(name) : ClassLoader.getSystemResourceAsStream(name);
    }

    @Override
    public MixinEnvironment.CompatibilityLevel getMinCompatibilityLevel() {
        return MixinEnvironment.CompatibilityLevel.JAVA_8;
    }

    @Override
    public MixinEnvironment.CompatibilityLevel getMaxCompatibilityLevel() {
        return MixinEnvironment.CompatibilityLevel.JAVA_21;
    }

    @Override
    protected ILogger createLogger(String name) {
        return new LoggerAdapterDefault(name);
    }
}
