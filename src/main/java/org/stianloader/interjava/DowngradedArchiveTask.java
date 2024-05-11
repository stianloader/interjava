package org.stianloader.interjava;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.gradle.api.JavaVersion;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RelativePath;
import org.gradle.api.internal.file.copy.CopyAction;
import org.gradle.api.internal.file.copy.CopyActionProcessingStream;
import org.gradle.api.internal.file.copy.FileCopyDetailsInternal;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.WorkResults;
import org.gradle.api.tasks.bundling.AbstractArchiveTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.stianloader.interjava.supertypes.ASMClassWrapperProvider;
import org.stianloader.interjava.supertypes.ClassWrapper;
import org.stianloader.interjava.supertypes.ClassWrapperPool;
import org.stianloader.interjava.supertypes.ReflectionClassWrapperProvider;

import xyz.wagyourtail.jvmdg.ClassDowngrader;
import xyz.wagyourtail.jvmdg.version.VersionProvider;

public abstract class DowngradedArchiveTask extends AbstractArchiveTask {

    @Input
    public abstract Property<Boolean> getIncludeExtraClasses();

    @Input
    public abstract Property<Integer> getTargetBytecodeVersion();

    @InputFiles
    public abstract ConfigurableFileCollection getCompileClassPath();

    public DowngradedArchiveTask() {
        this.getIncludeExtraClasses().convention(true);
        this.getTargetBytecodeVersion().convention(Opcodes.V1_7);

        this.getArchiveClassifier().convention(this.getProject().getProviders().provider(() -> {
            if (this.getTargetBytecodeVersion().get() == Opcodes.V1_1) {
                return "j1";
            } else if (this.getTargetBytecodeVersion().get() < 0) {
                return "downgraded";
            }

            return "j" + (this.getTargetBytecodeVersion().get() - (Opcodes.V1_2 - 2));
        }));

        this.getArchiveExtension().convention("jar");
        this.getDestinationDirectory().set(this.getProject().getLayout().getBuildDirectory().dir("libs"));
    }

    @Override
    protected CopyAction createCopyAction() {
        return (CopyActionProcessingStream caps) -> {
            final Map<String, FileCopyDetailsInternal> fileDetails = new LinkedHashMap<>();
            final Map<String, byte[]> rawData = new HashMap<>();
            final Set<String> directories = new HashSet<>();
            final AtomicBoolean doneProcessing = new AtomicBoolean();

            caps.process((file) -> {
                if (doneProcessing.get()) {
                    throw new IllegalStateException("processFile called after the process call. This hints towards synchronization issues");
                }

                if (fileDetails.putIfAbsent(file.getRelativePath().getPathString(), file) != null) {
                    throw new IllegalStateException("Duplicate entry: '" + file.getRelativePath().getPathString() + "' (Note: duplicate file handling strategies are not yet implemented)");
                }

                if (!file.isDirectory()) {
                    long len = file.getSize();
                    ByteArrayOutputStream baos;
                    if (len <= 0 || len > (1 << 28)) { // We surmise that the provided length cannot be trusted here (1 << 28 = 256MiB)
                        baos = new ByteArrayOutputStream();
                    } else {
                        baos = new ByteArrayOutputStream((int) len);
                    }

                    file.copyTo(baos);
                    rawData.put(file.getRelativePath().getPathString(), baos.toByteArray());
                }

                RelativePath ownerDir = file.getRelativePath().getParent();
                while (ownerDir != null && directories.add(ownerDir.getPathString())) {
                    ownerDir = ownerDir.getParent();
                }
            });

            doneProcessing.set(true);

            CachingClassNodeLookup nodeLookup = new CachingClassNodeLookup(rawData);

            Class<?> runtimeClass;
            try {
                runtimeClass = this.getClass().getClassLoader().loadClass("xyz.wagyourtail.jvmdg.exc.MissingStubError");
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Unable to obtain the URL of the jvmdowngrader-java-api jar. Is the dependency correctly set on the classpath?", e);
            }
            URL sourceURL = runtimeClass.getProtectionDomain().getCodeSource().getLocation();
            System.setProperty("jvmdg.java-api", sourceURL.getPath());

            ClassDowngrader downgrader = ClassDowngrader.downgradeTo(this.getTargetBytecodeVersion().get());
            downgrader.getVersionProviderFor(this.getTargetBytecodeVersion().get());

            final Set<ClassNode> extra = new HashSet<>();

            ClassWrapperPool wrapperPool = new ClassWrapperPool();
            wrapperPool.addProvider(new ASMClassWrapperProvider() {
                @Override
                @Nullable
                public ClassNode getNode(@NotNull String name) {
                    return nodeLookup.apply(name);
                }
            });
            Map<String, ClassWrapper> compileClasspathWrappers = new HashMap<>();
            for (File f : this.getCompileClassPath().getFiles()) {
                if (f.isDirectory()
                        || !f.getName().endsWith(".class")
                        || f.getName().endsWith("module-info.class")
                        || f.getPath().contains("META-INF/versions/")) {
                    continue;
                }
                try (InputStream is = Files.newInputStream(f.toPath())) {
                    ClassReader reader = new ClassReader(is);
                    String name = reader.getClassName();
                    String superName = reader.getSuperName();
                    String[] interfaces = reader.getInterfaces();
                    boolean isItf = (reader.getAccess() & Opcodes.ACC_INTERFACE) != 0;
                    ClassWrapper wrapper = new ClassWrapper(name, superName, interfaces, isItf, wrapperPool);
                    if (compileClasspathWrappers.putIfAbsent(name, wrapper) != null) {
                        this.getLogger().warn("Duplicate class '{}' on provided compile-time classpath (one of them is provided by '{}').", name, f.getPath());
                    }
                } catch (Exception e) {
                    this.getLogger().warn("Unexpected exception raised while analyzing compile classpath");
                }
            }
            wrapperPool.addProvider((name, pool) -> compileClasspathWrappers.get(name));
            wrapperPool.addProvider(new ReflectionClassWrapperProvider(ClassLoader.getSystemClassLoader()));

            try (OutputStream rawOut = Files.newOutputStream(this.getArchiveFile().get().getAsFile().toPath(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    ZipOutputStream zipOut = new ZipOutputStream(rawOut)) {
                for (Map.Entry<String, FileCopyDetailsInternal> fileMapEntry : fileDetails.entrySet()) {
                    String path = fileMapEntry.getKey();
                    FileCopyDetailsInternal file = fileMapEntry.getValue();

                    if (file.isDirectory() && (file.isIncludeEmptyDirs() || directories.contains(path))) {
                        final ZipEntry entry = new ZipEntry(path + "/");
                        zipOut.putNextEntry(entry);
                        zipOut.closeEntry();
                    } else {
                        final ZipEntry entry = new ZipEntry(path);
                        zipOut.putNextEntry(entry);

                        if (!path.endsWith(".class") || path.contains("META-INF/versions/")) {
                            zipOut.write(rawData.get(path));
                            zipOut.closeEntry();
                            continue;
                        }

                        final byte[] result;
                        try {
                            String className = path.substring(0, path.length() - 6);
                            ClassNode node = nodeLookup.apply(className);
                            for (VersionProvider downgradeProvider : downgrader.versionProviders(node.version)) {
                                downgradeProvider.downgrade(node, extra, true, nodeLookup::apply);
                            }
                            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
                                @Override
                                protected String getCommonSuperClass(String type1, String type2) {
                                    if (type1 == null || type2 == null) {
                                        throw new NullPointerException("One of the child classes is null: " + (type1 == null) + ", " + (type2 == null));
                                    }
                                    return wrapperPool.getCommonSuperClass(wrapperPool.get(type1), wrapperPool.get(type2)).getName();
                                }
                            };
                            node.accept(writer);
                            result = writer.toByteArray();
                        } catch (Throwable e2) {
                            throw new RuntimeException("Failed to transform '" + path + "'", e2);
                        }

                        zipOut.write(result);
                        zipOut.closeEntry();
                    }
                }

                includeExtraClasses:
                if (this.getIncludeExtraClasses().get()) {
                    if (extra.isEmpty()) {
                        this.getLogger().info("Including no extra classes.");
                        break includeExtraClasses;
                    }
                    this.getLogger().warn("Extra classes is a rarely used function - there may be dragons.");

                    // Filter out duplicates (a Set<ClassNode> is pretty useless in my opinion)
                    // also ensure consistent order of zip entries by ordering classes by their name
                    TreeSet<ClassNode> extraNodes = new TreeSet<>((n1, n2) -> {
                        return n1.name.compareTo(n2.name);
                    });
                    for (ClassNode extraNode : extra) {
                        this.getLogger().info("Including extra class {}", extraNode.name);
                        if (!extraNodes.add(extraNode)) {
                            this.getLogger().warn("Ignoring duplicate extra classnode '{}'", extraNode.name);
                            continue;
                        }
                    }

                    for (final ClassNode extraNode : extraNodes) {
                        final String classPath = extraNode.name + ".class";
                        final Path dest = Paths.get(classPath);
                        Path directory = dest.getParent();
                        Queue<String> directoryQueue = Collections.asLifoQueue(new ArrayDeque<>());

                        while (directory != null && !directories.contains(directory.toString())) {
                            directoryQueue.add(directory.toString());
                            directories.add(directory.toString());
                            directory = directory.getParent();
                        }

                        while (!directoryQueue.isEmpty()) {
                            final ZipEntry entry = new ZipEntry(directoryQueue.poll() + "/");
                            zipOut.putNextEntry(entry);
                            zipOut.closeEntry();
                        }

                        zipOut.putNextEntry(new ZipEntry(classPath));
                        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
                            @Override
                            protected String getCommonSuperClass(String type1, String type2) {
                                if (type1 == null || type2 == null) {
                                    throw new NullPointerException("One of the child classes is null: " + (type1 == null) + ", " + (type2 == null));
                                }
                                return wrapperPool.getCommonSuperClass(wrapperPool.get(type1), wrapperPool.get(type2)).getName();
                            }
                        };
                        extraNode.accept(writer);
                        zipOut.write(writer.toByteArray());
                        zipOut.closeEntry();
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            return WorkResults.didWork(true);
        };
    }

    public void setTargetVersion(JavaVersion version) {
        this.setTargetVersion(version.ordinal() - JavaVersion.VERSION_1_1.ordinal() + 1);
    }

    public void setTargetVersion(int version) {
        if (version < 2) {
            if (version <= 0) {
                throw new IllegalStateException("Unsupported version: " + version);
            } else {
                // version == 1
                this.getTargetBytecodeVersion().set(Opcodes.V1_1);
            }
        }

        this.getTargetBytecodeVersion().set(version + (Opcodes.V1_2 - 2));
    }
}
