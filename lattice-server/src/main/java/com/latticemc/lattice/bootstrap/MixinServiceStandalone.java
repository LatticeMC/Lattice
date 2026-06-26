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
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.mixin.transformer.IMixinTransformerFactory;
import org.spongepowered.asm.service.IClassBytecodeProvider;
import org.spongepowered.asm.service.IClassProvider;
import org.spongepowered.asm.service.IClassTracker;
import org.spongepowered.asm.service.IMixinAuditTrail;
import org.spongepowered.asm.service.IMixinInternal;
import org.spongepowered.asm.service.ITransformer;
import org.spongepowered.asm.service.ITransformerProvider;
import org.spongepowered.asm.service.MixinServiceAbstract;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

public final class MixinServiceStandalone extends MixinServiceAbstract {

    public MixinServiceStandalone() {}

    @Override
    public void offer(IMixinInternal internal) {
        super.offer(internal);
        if (internal instanceof IMixinTransformerFactory factory) {
            try {
                IMixinTransformer transformer = factory.createTransformer();
                MixinEnvironment.getDefaultEnvironment().setActiveTransformer(transformer);
            } catch (Exception e) {
                throw new RuntimeException("Failed to create mixin transformer", e);
            }
        }
    }

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
            @SuppressWarnings("deprecation")
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
                return getClassNode(name, true);
            }

            @Override
            public ClassNode getClassNode(String name, boolean runTransformers) throws ClassNotFoundException {
                return getClassNode(name, runTransformers, 0);
            }

            @Override
            public ClassNode getClassNode(String name, boolean runTransformers, int readerFlags) throws ClassNotFoundException {
                String resourceName = name.replace('.', '/') + ".class";
                ClassLoader cl = Thread.currentThread().getContextClassLoader();
                try (InputStream is = cl != null ? cl.getResourceAsStream(resourceName) : ClassLoader.getSystemResourceAsStream(resourceName)) {
                    if (is == null) throw new ClassNotFoundException(name);
                    ClassReader reader = new ClassReader(is);
                    ClassNode node = new ClassNode();
                    reader.accept(node, readerFlags);
                    return node;
                } catch (ClassNotFoundException e) {
                    throw e;
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
            public void addTransformerExclusion(String name) {}

            @Override
            public Collection<ITransformer> getTransformers() {
                return Collections.emptyList();
            }

            @Override
            public Collection<ITransformer> getDelegatedTransformers() {
                return Collections.emptyList();
            }
        };
    }

    @Override
    public IClassTracker getClassTracker() {
        return null;
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
    public MixinEnvironment.Phase getInitialPhase() {
        return MixinEnvironment.Phase.DEFAULT;
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
