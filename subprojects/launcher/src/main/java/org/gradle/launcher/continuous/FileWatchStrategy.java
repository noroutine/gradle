/*
 * Copyright 2015 the original author or authors.
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

package org.gradle.launcher.continuous;

import org.gradle.BuildListener;
import org.gradle.BuildResult;
import org.gradle.api.Task;
import org.gradle.api.execution.TaskExecutionListener;
import org.gradle.api.file.DirectoryTree;
import org.gradle.api.file.FileCollection;
import org.gradle.api.initialization.Settings;
import org.gradle.api.internal.file.FileBackedDirectoryTree;
import org.gradle.api.internal.file.FileCollectionInternal;
import org.gradle.api.invocation.Gradle;
import org.gradle.api.tasks.TaskState;
import org.gradle.internal.UncheckedException;
import org.gradle.internal.filewatch.*;
import org.gradle.internal.nativeintegration.filesystem.FileCanonicalizer;

import java.io.File;
import java.io.IOException;

/**
 * Hacky initial implementation for file watching
 * Monitors the "current" directory and excludes build/** and .gradle/**
 * TODO: Look for the project directory?
 */
class FileWatchStrategy implements TriggerStrategy, TaskExecutionListener, BuildListener {
    private final TriggerListener listener;
    private final FileWatcherListener fileWatcherListener;
    private final FileWatcherFactory fileWatcherFactory;
    private final FileCanonicalizer fileCanonicalizer;
    private FileWatchInputs.Builder fileWatchInputBuilder;
    private FileWatcher fileWatcher;

    FileWatchStrategy(TriggerListener listener, FileWatcherFactory fileWatcherFactory, FileCanonicalizer fileCanonicalizer) {
        this.listener = listener;
        this.fileCanonicalizer = fileCanonicalizer;
        this.fileWatcherListener = new FileChangeCallback(listener);
        this.fileWatcherFactory = fileWatcherFactory;
    }

    @Override
    public void run() {
        // TODO: Enforce quiet period here?
    }

    @Override
    public void buildStarted(Gradle gradle) {
        if(fileWatcher != null) {
            fileWatcher.stop();
            fileWatcher = null;
        }
        fileWatchInputBuilder = FileWatchInputs.newBuilder();
    }

    @Override
    public void settingsEvaluated(Settings settings) {

    }

    @Override
    public void projectsLoaded(Gradle gradle) {

    }

    @Override
    public void projectsEvaluated(Gradle gradle) {

    }

    @Override
    public void buildFinished(BuildResult result) {
        try {
            fileWatcher = fileWatcherFactory.createFileWatcher(fileWatcherListener);
            fileWatcher.watch(fileWatchInputBuilder.build());
        } catch (IOException e) {
            // TODO:
            UncheckedException.throwAsUncheckedException(e);
        }
    }

    @Override
    public void beforeExecute(Task task)  {
        File buildDir = canonicalizeFile(task.getProject().getBuildDir());
        if(task.getInputs().getHasSourceFiles()) {
            FileCollection taskInputFiles = task.getInputs().getFiles();
            if(taskInputFiles instanceof FileCollectionInternal) {
                for (DirectoryTree tree : ((FileCollectionInternal)taskInputFiles).getAsDirectoryTrees()) {
                    if (tree instanceof FileBackedDirectoryTree) {
                        for (File file : ((FileBackedDirectoryTree) tree).getFiles()) {
                            if (!isSameOrChildOf(buildDir, file)) {
                                fileWatchInputBuilder.add(file);
                            }
                        }
                    } else if (!isSameOrChildOf(buildDir, tree.getDir())) {
                        fileWatchInputBuilder.add(tree);
                    }
                }
            } else {
                for (File file : taskInputFiles) {
                    if (!isSameOrChildOf(buildDir, file)) {
                        fileWatchInputBuilder.add(file);
                    }
                }
            }
        }
    }

    protected File canonicalizeFile(File file) {
        return file.exists() ? fileCanonicalizer.canonicalize(file) : file;
    }

    private boolean isSameOrChildOf(File root, File file) {
        if(!root.exists()) {
            return false;
        }
        File current = file;
        while (current != null) {
            current = canonicalizeFile(current);
            if(current.equals(root)) {
                return true;
            }
            current = current.getParentFile();
        }
        return false;
    }

    @Override
    public void afterExecute(Task task, TaskState state) {

    }

    static class FileChangeCallback implements FileWatcherListener {
        private final TriggerListener listener;

        private FileChangeCallback(TriggerListener listener) {
            this.listener = listener;
        }

        @Override
        public void onOverflow() {
            listener.triggered(new DefaultTriggerDetails(TriggerDetails.Type.REBUILD, "overflow in filewatching"));
        }

        @Override
        public void onChange(FileChangeDetails fileChangeDetails) {
            listener.triggered(new DefaultTriggerDetails(TriggerDetails.Type.REBUILD, "file change"));
        }
    }
}
