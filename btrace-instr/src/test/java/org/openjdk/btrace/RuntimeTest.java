/*
 * Copyright (c) 2014, 2015, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.openjdk.btrace;

import org.junit.Assert;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.StringTokenizer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author Jaroslav Bachorik
 */
@SuppressWarnings("ConstantConditions")
abstract public class RuntimeTest {
    private static String cp = null;
    protected static String java = null;
    private static String btraceExtPath = null;
    private static File projectRoot = null;
    /**
     * Display the otput from the test application
     */
    protected boolean debugTestApp = false;
    /**
     * Run BTrace in debug mode
     */
    protected boolean debugBTrace = false;
    /**
     * Run BTrace in unsafe mode
     */
    protected boolean isUnsafe = false;
    /**
     * Timeout in ms to wait for the expected BTrace output
     */
    protected long timeout = 10000L;
    /**
     * Track retransforming progress
     */
    protected boolean trackRetransforms = false;
    protected boolean attachDebugger = false;

    public static void setup() {
        URL url = BTraceFunctionalTests.class.getClassLoader().getResource("org/openjdk/btrace/instr/Instrumentor.class");
        try {
            File f = new File(url.toURI());
            while (f != null) {
                if (f.getName().equals("build") || f.getName().equals("out")) {
                    break;
                }
                f = f.getParentFile();
            }
            if (f != null) {
                projectRoot = new File(f.getAbsoluteFile() + "/../..");
                btraceExtPath = new File(projectRoot.getAbsolutePath() + "/btrace-dist/build/resources/main/libs/btrace-client.jar").getPath();
            }
            Assert.assertNotNull(projectRoot);
            Assert.assertNotNull(btraceExtPath);
        } catch (URISyntaxException e) {
            throw new Error(e);
        }
        String toolsjar = null;

        cp = System.getProperty("java.class.path");

        String javaHome = System.getenv("TEST_JAVA_HOME");
        if (javaHome == null) {
            javaHome = System.getProperty("java.home").replace("/jre", "");
        }
        java = javaHome;
        Path toolsJarPath = Paths.get(java, "lib", "tools.jar");
        if (Files.exists(toolsJarPath)) {
            toolsjar = toolsJarPath.toString();
        }
        btraceExtPath = btraceExtPath + File.pathSeparator + toolsjar;
        System.out.println("=== Using Java: " + java + ", toolsJar: " + toolsjar);
    }

    protected void reset() {
        debugTestApp = false;
        debugBTrace = false;
        isUnsafe = false;
        timeout = 10000L;
    }

    @SuppressWarnings("DefaultCharset")
    public void test(String testApp, final String testScript, int checkLines, ResultValidator v) throws Exception {
        test(testApp, testScript, null, checkLines, v);
    }

    @SuppressWarnings("DefaultCharset")
    public void test(String testApp, final String testScript, String[] cmdArgs, int checkLines, ResultValidator v) throws Exception {
        List<String> args = new ArrayList<>(Arrays.asList(
                java + "/bin/java",
                "-cp",
                cp
        ));
        if (attachDebugger) {
            args.add("-agentlib:jdwp=transport=dt_socket,server=y,address=8000");
        }
        args.add("-XX:+AllowRedefinitionToAddDeleteMethods");
        args.add("-XX:+IgnoreUnrecognizedVMOptions");
        args.add(testApp);

        ProcessBuilder pb = new ProcessBuilder(args);
        pb.environment().remove("JAVA_TOOL_OPTIONS");

        Process p = pb.start();
        final PrintWriter pw = new PrintWriter(p.getOutputStream());

        final StringBuilder stdout = new StringBuilder();
        final StringBuilder stderr = new StringBuilder();
        final AtomicInteger ret = new AtomicInteger(-1);

        final BufferedReader stdoutReader = new BufferedReader(new InputStreamReader(p.getInputStream()));

        final CountDownLatch testAppLatch = new CountDownLatch(1);
        final AtomicReference<String> pidStringRef = new AtomicReference<>();

        Thread outT = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String l;
                    while ((l = stdoutReader.readLine()) != null) {
                        if (l.startsWith("ready:")) {
                            pidStringRef.set(l.split("\\:")[1]);
                            testAppLatch.countDown();
                        }
                        if (debugTestApp) {
                            System.out.println("[traced app] " + l);
                        }
                    }


                } catch (Exception e) {
                    e.printStackTrace(System.err);
                }
            }
        }, "STDOUT Reader");
        outT.setDaemon(true);

        final BufferedReader stderrReader = new BufferedReader(new InputStreamReader(p.getErrorStream()));

        Thread errT = new Thread(new Runnable() {

            @Override
            public void run() {
                try {
                    String l = null;
                    while ((l = stderrReader.readLine()) != null) {
                        if (!l.contains("Server VM warning")) {
                            testAppLatch.countDown();
                        }
                        if (debugTestApp) {
                            System.err.println("[traced app] " + l);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace(System.err);
                }
            }
        }, "STDERR Reader");
        errT.setDaemon(true);

        outT.start();
        errT.start();

        testAppLatch.await();
        String pid = pidStringRef.get();
        if (pid != null) {
            System.out.println("Target process ready: " + pid);

            Process client = attach(pid, testScript, cmdArgs, checkLines, stdout, stderr);

            System.out.println("Detached.");
            pw.println("done");
            pw.flush();

            ret.set(client.waitFor());

            outT.join();
            errT.join();
        }

        v.validate(stdout.toString(), stderr.toString(), ret.get());
    }

    private File locateTrace(final String trace) {
        Path start = Paths.get(projectRoot.getAbsolutePath(), "btrace-instr/src");
        final AtomicReference<Path> tracePath = new AtomicReference<>();
        try {
            Files.walkFileTree(start, new FileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (file.toString().endsWith(trace)) {
                        tracePath.set(file);
                        return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                    return FileVisitResult.TERMINATE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
        }
        return tracePath.get() != null ? tracePath.get().toFile() : null;
    }

    private Process attach(String pid, String trace, String[] cmdArgs, final int checkLines, final StringBuilder stdout, final StringBuilder stderr) throws Exception {
        File traceFile = locateTrace(trace);
        List<String> argVals = new ArrayList<>(Arrays.asList(
                java + "/bin/java",
                "-Dcom.sun.btrace.unsafe=" + isUnsafe,
                "-Dcom.sun.btrace.debug=" + debugBTrace,
                "-Dcom.sun.btrace.trackRetransforms=" + trackRetransforms,
                "-cp",
                btraceExtPath,
                "org.openjdk.btrace.client.Main",
                "-d", "/tmp/btrace-test",
                "-pd", traceFile.getParentFile().getAbsolutePath(),
                pid,
                traceFile.getAbsolutePath()
        ));
        if (cmdArgs != null) {
            argVals.addAll(Arrays.asList(cmdArgs));
        }
        final ProcessBuilder pb = new ProcessBuilder(argVals);

        pb.environment().remove("JAVA_TOOL_OPTIONS");
        final Process p = pb.start();

        final CountDownLatch l = new CountDownLatch(checkLines);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BufferedReader br = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8));

                    String line = null;
                    while ((line = br.readLine()) != null) {
                        System.out.println("[btrace err] " + line);
                        if (line.contains("Server VM warning")) {
                            // skip JVM generated warnings
                            continue;
                        }
                        stderr.append(line).append('\n');
                        if (line.contains("Exception") || line.contains("Error")) {
                            for (int i = 0; i < checkLines; i++) {
                                l.countDown();
                            }
                        }
                    }
                } catch (Exception e) {
                    for (int i = 0; i < checkLines; i++) {
                        l.countDown();
                    }
                    throw new Error(e);
                }
            }
        }, "Stderr Reader").start();

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                    String line = null;
                    while ((line = br.readLine()) != null) {
                        stdout.append(line).append('\n');
                        System.out.println("[btrace out] " + line);
                        if (!(debugBTrace && line.contains("DEBUG:"))) {
                            l.countDown();
                        }
                    }
                } catch (Exception e) {
                    for (int i = 0; i < checkLines; i++) {
                        l.countDown();
                    }
                    throw new Error(e);
                }
            }
        }, "Stdout Reader").start();

        l.await(timeout, TimeUnit.MILLISECONDS);

        return p;
    }

    protected interface ResultValidator {
        void validate(String stdout, String stderr, int retcode);
    }
}
