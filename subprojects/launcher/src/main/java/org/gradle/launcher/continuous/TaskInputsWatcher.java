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

import org.gradle.BuildAdapter;
import org.gradle.BuildResult;
import org.gradle.api.Action;
import org.gradle.api.Task;
import org.gradle.api.execution.TaskExecutionAdapter;
import org.gradle.api.internal.file.FileCollectionInternal;
import org.gradle.api.internal.file.UnionFileCollection;
import org.gradle.api.internal.file.collections.SimpleFileCollection;
import org.gradle.api.invocation.Gradle;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.specs.Spec;
import org.gradle.internal.filewatch.FileWatcherEvent;
import org.gradle.internal.filewatch.FileWatcherFactory;
import org.gradle.internal.filewatch.FilteringFileWatcherListener;
import org.gradle.internal.filewatch.StopThenFireFileWatcherListener;

public class TaskInputsWatcher extends BuildAdapter {
    private final static Logger LOGGER = Logging.getLogger(TaskInputsWatcher.class);
    private final TriggerListener listener;
    private final FileWatcherFactory fileWatcherFactory;
    private final FileCollectionInternal taskInputs;

    public TaskInputsWatcher(TriggerListener listener, FileWatcherFactory fileWatcherFactory) {
        this.listener = listener;
        this.fileWatcherFactory = fileWatcherFactory;
        this.taskInputs = new UnionFileCollection();
    }

    @Override
    public void buildStarted(Gradle gradle) {
        gradle.getTaskGraph().addTaskExecutionListener(new TaskExecutionAdapter() {
            @Override
            public void beforeExecute(Task task) {
                // Capture snapshot of input files
                taskInputs.add(new SimpleFileCollection(task.getInputs().getFiles().getFiles()));
            }
        });
    }

    @Override
    public void buildFinished(BuildResult result) {
        if (true || LOGGER.isDebugEnabled()) {
            LOGGER.error("taskInputs.getFileSystemRoots(): {}", taskInputs.getFileSystemRoots());
            LOGGER.error("Task inputs: {}", taskInputs.getFiles());
        }

        fileWatcherFactory.watch(
            taskInputs.getFileSystemRoots(),
            new Action<Throwable>() {
                @Override
                public void execute(Throwable throwable) {
                    listener.triggered(new DefaultTriggerDetails(TriggerDetails.Type.STOP, "error " + throwable.getMessage()));
                }
            },
            new FilteringFileWatcherListener(
                new Spec<FileWatcherEvent>() {
                    @Override
                    public boolean isSatisfiedBy(FileWatcherEvent element) {
                        if (true || LOGGER.isDebugEnabled()) {
                            LOGGER.error("Received file system event: {}", element);
                        }
                        return element.getType().equals(FileWatcherEvent.Type.UNDEFINED)
                            || taskInputs.wouldContain(element.getFile());
                    }
                },
                new StopThenFireFileWatcherListener(new Runnable() {
                    @Override
                    public void run() {
                        listener.triggered(new DefaultTriggerDetails(TriggerDetails.Type.REBUILD, "file change"));
                    }
                })
            )
        );
    }
}
