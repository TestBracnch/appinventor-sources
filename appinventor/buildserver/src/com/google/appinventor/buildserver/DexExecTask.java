/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.appinventor.buildserver;

import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dex task, modified from the Android SDK to run in BuildServer.
 * Custom task to execute dx while handling dependencies.
 */
public class DexExecTask {

    private String mExecutable;
    private String mOutput;
    private String mDexedLibs;
    private boolean mVerbose = false;
    private boolean mNoLocals = false;
    private int mChildProcessRamMb = 1024;
    private boolean mDisableDexMerger = false;
    private static Map<String, String> alreadyChecked = new HashMap<String, String>();
    private String mJetifierClasspath;

    private static final Object semaphore = new Object(); // Used to protect dex cache creation


    /**
     * Sets the value of the "executable" attribute.
     *
     * @param executable the value.
     */
    public void setExecutable(String executable) {
        mExecutable = executable;
    }

    /**
     * Sets the value of the "verbose" attribute.
     *
     * @param verbose the value.
     */
    public void setVerbose(boolean verbose) {
        mVerbose = verbose;
    }

    /**
     * Sets the value of the "output" attribute.
     *
     * @param output the value.
     */
    public void setOutput(String output) {
        mOutput = output;
    }

    public void setDexedLibs(String dexedLibs) {
        mDexedLibs = dexedLibs;
    }

    /**
     * Sets the value of the "nolocals" attribute.
     *
     * @param verbose the value.
     */
    public void setNoLocals(boolean nolocals) {
        mNoLocals = nolocals;
    }

    public void setChildProcessRamMb(int mb) {
        mChildProcessRamMb = mb;
    }

    public void setDisableDexMerger(boolean disable) {
        mDisableDexMerger = disable;
    }

    public void setJetifierClasspath(String cp) {
        mJetifierClasspath = cp;
    }

    private boolean preDexLibraries(List<File> inputs) {
        if (mDisableDexMerger || inputs.size() == 1) {
            // only one input, no need to put a pre-dexed version, even if this path is
            // just a jar file (case for proguard'ed builds)
            return true;
        }

        synchronized (semaphore) {

            final int count = inputs.size();
            for (int i = 0; i < count; i++) {
                File input = inputs.get(i);
                if (input.isFile()) {
                    // check if this libs needs to be pre-dexed
                    String fileName = getDexFileName(input);
                    File dexedLib = new File(mDexedLibs, fileName);
                    String dexedLibPath = dexedLib.getAbsolutePath();

                    if (!dexedLib.isFile()/*||
                                                    dexedLib.lastModified() < input.lastModified()*/) {

                        System.out.println(
                            String.format("Pre-Dexing %1$s -> %2$s",
                              input.getAbsolutePath(), fileName));

                        if (dexedLib.isFile()) {
                            dexedLib.delete();
                        }

                        boolean dexSuccess = runDx(input, dexedLibPath, /*showInputs=*/ false);
                        if (!dexSuccess) return false;
                    } else {
                        System.out.println(
                            String.format("Using Pre-Dexed %1$s <- %2$s",
                              fileName, input.getAbsolutePath()));
                    }

                    // replace the input with the pre-dex libs.
                    inputs.set(i, dexedLib);
                }
            }
            return true;
        }
    }

    private String getDexFileName(File inputFile) {
        String hashed = getHashFor(inputFile);
        return "dex-cached-" + hashed + ".jar";
    }

    private String getHashFor(File inputFile) {
        String retval = alreadyChecked.get(inputFile.getAbsolutePath());
        if (retval != null) return retval;
        // add a hash of the original file path
        try {
            HashFunction hashFunction = Hashing.md5();
            HashCode hashCode = hashFunction.hashBytes(Files.readAllBytes(inputFile.toPath()));
            retval = hashCode.toString();
            alreadyChecked.put(inputFile.getAbsolutePath(), retval);
            return retval;
        } catch (IOException e) {
            e.printStackTrace();
            return "ERROR";
        }
    }

    private String getJetifiedFilePath(File lib) {
        String fileName = PathUtil.basename(lib.getAbsolutePath());
        String fileNameWithoutExtension = PathUtil.trimOffExtension(fileName);
        return fileNameWithoutExtension + "-jetified.jar";
    }

    // TODO: Handle Jetification of XML files
    // TODO: Caching
    private boolean runJetifier(File lib) {
        int mx = mChildProcessRamMb - 200;

        /*
        java -cp <mJettifierClasspath> com.android.tools.build.jetifier.standalone.Main \
        -i <input lib path> \
        -o <output jetified lib path>
         */

        List<String> commandLineList = new ArrayList<>();
        commandLineList.add(System.getProperty("java.home") + "/bin/java");
        commandLineList.add("-mx" + mx + "M");
        commandLineList.add("-cp");
        commandLineList.add(mJetifierClasspath);
        commandLineList.add("com.android.tools.build.jetifier.standalone.Main");
        commandLineList.add("-i");
        commandLineList.add(lib.getAbsolutePath());
        commandLineList.add("-o");
        commandLineList.add(getJetifiedFilePath(lib));

        return Execution.execute(null, commandLineList.toArray(new String[0]), System.out, System.err);
    }

    public boolean execute(List<File> paths) {
        List<File> jetifiedPaths = new ArrayList<>();
        for (File path : paths) {
            if (path.getName().endsWith(".jar")) {
                if (runJetifier(path)) {
                    jetifiedPaths.add(new File(getJetifiedFilePath(path)));
                }
                // TODO: What to do in case Jetifier fails?
            } else {
                jetifiedPaths.add(path);
            }
        }

        // pre dex libraries if needed
        boolean successPredex = preDexLibraries(jetifiedPaths);
        if (!successPredex) return false;

        System.out.println(String.format(
                "Converting compiled files and external libraries into %1$s...", mOutput));

        return runDx(paths, mOutput, mVerbose /*showInputs*/);
    }

    private boolean runDx(File input, String output, boolean showInputs) {
        return runDx(Collections.singleton(input), output, showInputs);
    }

    private boolean runDx(Collection<File> inputs, String output, boolean showInputs) {
        int mx = mChildProcessRamMb - 200;

        List<String> commandLineList = new ArrayList<String>();
        commandLineList.add(System.getProperty("java.home") + "/bin/java");
        commandLineList.add("-mx" + mx + "M");
        commandLineList.add("-jar");
        commandLineList.add(mExecutable);

        commandLineList.add("--dex");
        commandLineList.add("--positions=lines");

        if (mNoLocals) {
            commandLineList.add("--no-locals");
        }

        if (mVerbose) {
            commandLineList.add("--verbose");
        }

        commandLineList.add("--output=" + output);

        for (File input : inputs) {
            String absPath = input.getAbsolutePath();
            if (showInputs) {
                System.out.println("Input: " + absPath);
            }
            commandLineList.add(absPath);
        }

        // Convert command line to an array
        String[] dxCommandLine = new String[commandLineList.size()];
        commandLineList.toArray(dxCommandLine);

        boolean dxSuccess = Execution.execute(null, dxCommandLine, System.out, System.err);
        return dxSuccess;

    }

    protected String getExecTaskName() {
        return "dx";
    }
}
