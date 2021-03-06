/*
 * Copyright (c) 2017-2019 superblaubeere27, Sam Sun, MarcoMC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package me.superblaubeere27.jobf;

import com.google.common.io.ByteStreams;
import me.superblaubeere27.IPreClassProcessor;
import me.superblaubeere27.jobf.processors.*;
import me.superblaubeere27.jobf.processors.flowObfuscation.FlowObfuscator;
import me.superblaubeere27.jobf.processors.name.ClassWrapper;
import me.superblaubeere27.jobf.processors.name.INameObfuscationProcessor;
import me.superblaubeere27.jobf.processors.name.InnerClassRemover;
import me.superblaubeere27.jobf.processors.name.NameObfuscation;
import me.superblaubeere27.jobf.processors.optimizer.Optimizer;
import me.superblaubeere27.jobf.processors.packager.Packager;
import me.superblaubeere27.jobf.utils.ClassTree;
import me.superblaubeere27.jobf.utils.MissingClassException;
import me.superblaubeere27.jobf.utils.Utils;
import me.superblaubeere27.jobf.utils.script.JObfScript;
import me.superblaubeere27.jobf.utils.values.Configuration;
import me.superblaubeere27.jobf.utils.values.ValueManager;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ModifiedClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class JObfImpl {
    public static final JObfImpl INSTANCE = new JObfImpl();
    public static List<IClassProcessor> processors;
    public static HashMap<String, ClassNode> classes = new HashMap<>();
    public static HashMap<String, byte[]> files = new HashMap<>();
    private static List<IPreClassProcessor> preProcessors;
    public JObfScript script;
    private boolean mainClassChanged;
    private List<INameObfuscationProcessor> nameObfuscationProcessors = new ArrayList<>();
    private String mainClass;
    private Map<String, ClassWrapper> classPath = new HashMap<>();
    private Map<String, ClassTree> hierarchy = new HashMap<>();
    private Set<ClassWrapper> libraryClassnodes = new HashSet<>();
    private List<File> libraryFiles;
    private int computeMode;
    private boolean invokeDynamic;
    private JObfSettings settings = new JObfSettings();
    private int threadCount = Math.max(1, Runtime.getRuntime().availableProcessors());


    public JObfImpl() {
        processors = new ArrayList<>();

        ValueManager.registerClass(settings);

        addProcessors();
    }

    public static HashMap<String, ClassNode> getClasses() {
        return classes;
    }

    public String getMainClass() {
        return mainClass;
    }

    private void setMainClass(String mainClass) {
        this.mainClass = mainClass;
    }

    public ClassTree getTree(String ref) {
        if (!hierarchy.containsKey(ref)) {
            ClassWrapper wrapper = classPath.get(ref);
            buildHierarchy(wrapper, null);
        }

        return hierarchy.get(ref);
    }

    public void buildHierarchy(ClassWrapper classWrapper, ClassWrapper sub) {
        if (hierarchy.get(classWrapper.classNode.name) == null) {
            ClassTree tree = new ClassTree(classWrapper);
            if (classWrapper.classNode.superName != null) {
                tree.parentClasses.add(classWrapper.classNode.superName);
                ClassWrapper superClass = classPath.get(classWrapper.classNode.superName);
                if (superClass == null)
                    throw new MissingClassException(classWrapper.classNode.superName + " is missing in the classPath.");
                buildHierarchy(superClass, classWrapper);
            }
            if (classWrapper.classNode.interfaces != null && !classWrapper.classNode.interfaces.isEmpty()) {
                for (String s : classWrapper.classNode.interfaces) {
                    tree.parentClasses.add(s);
                    ClassWrapper interfaceClass = classPath.get(s);
                    if (interfaceClass == null)
                        throw new MissingClassException(s + " is missing in the classPath.");
                    buildHierarchy(interfaceClass, classWrapper);
                }
            }
            hierarchy.put(classWrapper.classNode.name, tree);
        }
        if (sub != null) {
            hierarchy.get(classWrapper.classNode.name).subClasses.add(sub.classNode.name);
        }
    }

    private Map<String, ClassWrapper> loadClasspathFile(File file) throws IOException {
        Map<String, ClassWrapper> map = new HashMap<>();

        ZipFile zipIn = new ZipFile(file);
        Enumeration<? extends ZipEntry> entries = zipIn.entries();
        while (entries.hasMoreElements()) {
            ZipEntry ent = entries.nextElement();
            if (ent.getName().endsWith(".class")) {
                byte[] bytes = ByteStreams.toByteArray(zipIn.getInputStream(ent));

                ClassReader reader = new ClassReader(bytes);
                ClassNode node = new ClassNode();
                reader.accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                map.put(node.name, new ClassWrapper(node, true, bytes));
            }
        }
        zipIn.close();

        return map;
    }

    private void loadClasspath() throws IOException {
        if (libraryFiles != null) {
            int i = 0;
            for (File file : libraryFiles) {
                if (file.isFile()) {
                    JObf.log.info("Loading " + file.getAbsolutePath() + " (" + (i++ * 100 / libraryFiles.size()) + "%)");
                    classPath.putAll(loadClasspathFile(file));
                } else {
                    Files.walk(file.toPath()).map(Path::toFile).filter(f -> f.getName().endsWith(".jar")).forEach(f -> {
                        try {
                            classPath.putAll(loadClasspathFile(f));
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    });
                }
            }
        }
        libraryClassnodes.addAll(classPath.values());
    }

    public Map<String, ClassWrapper> getClassPath() {
        return classPath;
    }

    public boolean isLibrary(ClassNode classNode) {
        return libraryClassnodes.stream().anyMatch(e -> e.classNode.name.equals(classNode.name));
    }

    private void addProcessors() {
        processors.add(new StaticInitializionProcessor(this));

        processors.add(new HWIDProtection(this));
        processors.add(new Optimizer());
        processors.add(new InlineProcessor(this));
        processors.add(new InvokeDynamic());

        processors.add(new StringEncryptionProcessor(this));
        processors.add(new NumberObfuscationProcessor(this));
        processors.add(new FlowObfuscator(this));
        processors.add(new HideMembers(this));
        processors.add(new LineNumberRemover(this));
        processors.add(new ShuffleMembersProcessor(this));


        nameObfuscationProcessors.add(new NameObfuscation());
        nameObfuscationProcessors.add(new InnerClassRemover());
        processors.add(new CrasherProcessor(this));
        processors.add(new ReferenceProxy(this));

        preProcessors = new ArrayList<>();

        for (IClassProcessor processor : processors) {
            ValueManager.registerClass(processor);
        }
        for (IPreClassProcessor processor : preProcessors) {
            ValueManager.registerClass(processor);
        }
        for (INameObfuscationProcessor processor : nameObfuscationProcessors) {
            ValueManager.registerClass(processor);
        }
    }

    public void setScript(JObfScript script) {
        this.script = script;
    }

    public void processJar(Configuration config) throws IOException {
        ZipInputStream inJar = null;
        ZipOutputStream outJar = null;

        libraryFiles = new ArrayList<>();

        classes = new HashMap<>();
        libraryClassnodes = new HashSet<>();
        classPath = new HashMap<>();
        files = new HashMap<>();
        hierarchy = new HashMap<>();

        try {
            script = new JObfScript(config.getScript() == null ? "" : config.getScript());
        } catch (Exception e) {
            JObf.log.severe("Failed to load script");
            e.printStackTrace();
            return;
        }

        for (String s : config.getLibraries()) libraryFiles.add(new File(s));

        try {
            JObf.log.info("Loading classpath...");
            loadClasspath();
            try {
                inJar = new ZipInputStream(new BufferedInputStream(new FileInputStream(config.getInput())));
            } catch (FileNotFoundException e) {
                throw new FileNotFoundException("Could not open input file: " + e.getMessage());
            }

            try {
                OutputStream out = (config.getOutput() == null ? new ByteArrayOutputStream() : new FileOutputStream(config.getOutput()));
                outJar = new ZipOutputStream(new BufferedOutputStream(out));
            } catch (FileNotFoundException e) {
                throw new FileNotFoundException("Could not open output file: " + e.getMessage());
            }
            setMainClass(null);

            long startTime = System.currentTimeMillis();

            JObf.log.info("Reading input...");

            while (true) {
                ZipEntry entry = inJar.getNextEntry();

                if (entry == null) {
                    break;
                }

                if (entry.isDirectory()) {
                    outJar.putNextEntry(entry);
                    continue;
                }

                byte[] data = new byte[4096];
                ByteArrayOutputStream entryBuffer = new ByteArrayOutputStream();

                int len;
                do {
                    len = inJar.read(data);
                    if (len > 0) {
                        entryBuffer.write(data, 0, len);
                    }
                } while (len != -1);

                byte[] entryData = entryBuffer.toByteArray();

                String entryName = entry.getName();

                if (entryName.endsWith(".class")) {
                    try {
                        ClassReader cr = new ClassReader(entryData);
                        ClassNode cn = new ClassNode();


                        //ca = new LineInjectorAdaptor(ASM4, cn);

                        cr.accept(cn, 0);
                        classes.put(entryName, cn);
                    } catch (Exception e) {
                        JObf.log.warning("Failed to read class " + entryName);
                        e.printStackTrace();
                        files.put(entryName, entryData);
                    }

                } else {
                    if (entryName.equals("META-INF/MANIFEST.MF")) {
                        setMainClass(Utils.getMainClass(new String(entryData, StandardCharsets.UTF_8)));
                    }

                    files.put(entryName, entryData);
                }
            }

            for (Map.Entry<String, ClassNode> stringClassNodeEntry : classes.entrySet()) {
                classPath.put(stringClassNodeEntry.getKey().replace(".class", ""), new ClassWrapper(stringClassNodeEntry.getValue(), false, null));
            }
            for (ClassNode value : classes.values()) {
                libraryClassnodes.add(new ClassWrapper(value, false, null));
            }

//            if (nameobf) {
            for (INameObfuscationProcessor nameObfuscationProcessor : nameObfuscationProcessors) {
                nameObfuscationProcessor.transformPost(this, classes);
            }
            for (IPreClassProcessor preProcessor : preProcessors) {
                preProcessor.process(classes.values());
            }
//            }

            AtomicInteger processed = new AtomicInteger();

            if (Packager.INSTANCE.isEnabled()) {
                Packager.INSTANCE.init();
            }

            JObf.log.info("... Finished after " + Utils.formatTime(System.currentTimeMillis() - startTime));

            startTime = System.currentTimeMillis();


            JObf.log.info("Transforming with " + threadCount + " threads...");

            final LinkedList<Map.Entry<String, ClassNode>> classQueue = new LinkedList<>(classes.entrySet());

            HashMap<String, byte[]> toWrite = new HashMap<>();

            List<Thread> threads = new ArrayList<>();

            for (int i = 0; i < threadCount; i++) {
//                ZipOutputStream finalOutJar = outJar;


                Thread t = new Thread(() -> {
                    try {
                        while (true) {
                            Map.Entry<String, ClassNode> stringClassNodeEntry;

                            synchronized (classQueue) {
                                stringClassNodeEntry = classQueue.poll();
                            }

                            if (stringClassNodeEntry == null) break;

                            ProcessorCallback callback = new ProcessorCallback();

                            String entryName = stringClassNodeEntry.getKey();
                            byte[] entryData;
                            ClassNode cn = stringClassNodeEntry.getValue();

                            try {
                                try {

                                    computeMode = ModifiedClassWriter.COMPUTE_MAXS;


                                    if (script == null || script.isObfuscatorEnabled(cn)) {
                                        JObf.log.log(Level.FINE, String.format("[%s] (%s/%s), Processing %s", Thread.currentThread().getName(), processed, classes.size(), entryName));

                                        for (IClassProcessor proc : processors) {
                                            try {
                                                proc.process(callback, cn);
                                            } catch (Exception e) {
                                                e.printStackTrace();
                                            }
                                        }
                                    } else {
                                        JObf.log.log(Level.FINE, String.format("[%s] (%s/%s), Skipping %s", Thread.currentThread().getName(), processed, classes.size(), entryName));
                                    }

                                    if (callback.isForceComputeFrames())
                                        cn.methods.forEach(method -> Arrays.stream(method.instructions.toArray()).filter(abstractInsnNode -> abstractInsnNode instanceof FrameNode).forEach(abstractInsnNode -> method.instructions.remove(abstractInsnNode)));


                                    int mode = computeMode
                                            | (callback.isForceComputeFrames() ? ModifiedClassWriter.COMPUTE_FRAMES : 0);

                                    JObf.log.log(Level.FINE, String.format("[%s] (%s/%s), Writing (computeMode = %s) %s", Thread.currentThread().getName(), processed, classes.size(), mode, entryName));

                                    ModifiedClassWriter writer = new ModifiedClassWriter(
//                                            computeMode
                                            ModifiedClassWriter.COMPUTE_FRAMES
                                    );
                                    cn.accept(writer);

                                    entryData = writer.toByteArray();
                                } catch (Throwable e) {
//                                    if (e instanceof) {
//
//                                    }
                                    ModifiedClassWriter writer = new ModifiedClassWriter(ModifiedClassWriter.COMPUTE_MAXS
                                            //                            | ModifiedClassWriter.COMPUTE_FRAMES
                                    );
                                    cn.accept(writer);

                                    entryData = writer.toByteArray();
                                }
                                try {
                                    if (Packager.INSTANCE.isEnabled()) {
                                        entryName = Packager.INSTANCE.encryptName(entryName.replace(".class", ""));
                                        entryData = Packager.INSTANCE.encryptClass(entryData);
                                    }
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }


//                                synchronized (finalOutJar) {
//                                    ZipEntry newEntry = new ZipEntry(entryName);
//                                    finalOutJar.putNextEntry(newEntry);
//                                    finalOutJar.write(entryData);
//                                }

                                synchronized (toWrite) {
                                    toWrite.put(entryName, entryData);
                                }
                                //                    JObfImpl.log.log(Level.FINE, String.format("Processed %s (+%.2f KB)", entryName, (entryData.length - entryBuffer.size()) / 1024.0));
                            } catch (Exception e) {
                                e.printStackTrace();
                            }

                            //                JObfImpl.log.log(Level.FINE, "Processed " + entryBuffer.size() + " -> " + entryData.length);

                            processed.getAndIncrement();
                        }
                    } finally {
                        synchronized (threads) {
                            threads.remove(Thread.currentThread());
                        }
                    }
                });

                t.setName("Thread-" + i);
                t.setContextClassLoader(ObfuscatorClassLoader.INSTANCE);

                t.start();

                synchronized (threads) {
                    threads.add(t);
                }
            }


            while (true) {
                synchronized (threads) {
                    if (threads.isEmpty()) break;

                    threads.stream().filter(thread -> thread == null || !thread.isAlive()).collect(Collectors.toList()).forEach(threads::remove);
                }

                Thread.sleep(100);
            }

            JObf.log.info("... Finished after " + Utils.formatTime(System.currentTimeMillis() - startTime));

            startTime = System.currentTimeMillis();

            JObf.log.info("Writing classes...");

            for (Map.Entry<String, byte[]> stringEntry : toWrite.entrySet()) {
                ZipEntry newEntry = new ZipEntry(stringEntry.getKey());
                outJar.putNextEntry(newEntry);
                outJar.write(stringEntry.getValue());
            }

            JObf.log.info("... Finished after " + Utils.formatTime(System.currentTimeMillis() - startTime));

            startTime = System.currentTimeMillis();

            JObf.log.info("Writing resources...");

            for (Map.Entry<String, byte[]> stringEntry : files.entrySet()) {
                String entryName = stringEntry.getKey();
                byte[] entryData = stringEntry.getValue();

                if (entryName.equals("META-INF/MANIFEST.MF")) {
                    if (Packager.INSTANCE.isEnabled()) {
                        entryData = Utils.replaceMainClass(new String(entryData, StandardCharsets.UTF_8), Packager.INSTANCE.getDecryptionClassName()).getBytes(StandardCharsets.UTF_8);
                    } else if (mainClassChanged) {
                        entryData = Utils.replaceMainClass(new String(entryData, StandardCharsets.UTF_8), mainClass).getBytes(StandardCharsets.UTF_8);
                        JObf.log.log(Level.FINE, "Replaced Main-Class with " + mainClass);
                    }

                    JObf.log.log(Level.FINE, "Processed MANIFEST.MF");
                }
                JObf.log.log(Level.FINE, "Copying " + entryName);

                ZipEntry newEntry = new ZipEntry(entryName);
                outJar.putNextEntry(newEntry);
                outJar.write(entryData);
            }

            JObf.log.info("... Finished after " + Utils.formatTime(System.currentTimeMillis() - startTime));

            startTime = System.currentTimeMillis();

            if (Packager.INSTANCE.isEnabled()) {
                JObf.log.info("Packaging...");
                byte[] decryptorData = Packager.INSTANCE.generateEncryptionClass();
                outJar.putNextEntry(new ZipEntry(Packager.INSTANCE.getDecryptionClassName() + ".class"));
                outJar.write(decryptorData);
                outJar.closeEntry();
                JObf.log.info("... Finished after " + Utils.formatTime(System.currentTimeMillis() - startTime));
            }
        } catch (InterruptedException ignored) {
        } finally {
            classPath.clear();
            classes.clear();
            libraryFiles.clear();
            libraryClassnodes.clear();
            files.clear();
            hierarchy.clear();

            if (outJar != null) {
                try {
                    JObf.log.info("Finishing...");
                    outJar.flush();
                    outJar.close();
                    JObf.log.info(">>> Processing completed. If you found a bug / if the output is invalid please open an issue at https://github.com/superblaubeere27/obfuscator/issues");
                } catch (Exception e) {
                    // ignore
                }
            }

            if (inJar != null) {
                try {
                    inJar.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }
    }

    public void setWorkDone() {
        boolean workDone = true;
    }


    public void setThreadCount(int threadCount) {
        this.threadCount = threadCount;
    }
}
