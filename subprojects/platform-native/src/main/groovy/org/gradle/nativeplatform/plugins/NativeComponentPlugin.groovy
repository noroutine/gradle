/*
 * Copyright 2011 the original author or authors.
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
package org.gradle.nativeplatform.plugins
import org.gradle.api.Action
import org.gradle.api.Incubating
import org.gradle.api.Plugin
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.gradle.model.Mutate
import org.gradle.model.RuleSource
import org.gradle.model.collection.CollectionBuilder
import org.gradle.nativeplatform.*
import org.gradle.nativeplatform.internal.NativeBinarySpecInternal
import org.gradle.nativeplatform.tasks.CreateStaticLibrary
import org.gradle.nativeplatform.tasks.InstallExecutable
import org.gradle.nativeplatform.tasks.LinkExecutable
import org.gradle.nativeplatform.tasks.LinkSharedLibrary
import org.gradle.nativeplatform.test.NativeTestSuiteBinarySpec
import org.gradle.nativeplatform.toolchain.internal.plugins.StandardToolChainsPlugin
import org.gradle.platform.base.BinaryTasksCollection
/**
 * A plugin that creates tasks used for constructing native binaries.
 */
@Incubating
public class NativeComponentPlugin implements Plugin<ProjectInternal> {

    public void apply(final ProjectInternal project) {
        project.pluginManager.apply(NativeComponentModelPlugin.class);
        project.pluginManager.apply(StandardToolChainsPlugin)
    }

    static class Rules extends RuleSource {

        @Mutate
        void createNativeBinaryTasks(CollectionBuilder<NativeComponentSpec> nativeComponents) {
            nativeComponents.afterEach { nativeComponentSpec ->
                println "nativeComponentSpec = $nativeComponentSpec"
                nativeComponentSpec.binaries.withType(NativeBinarySpec) {
                    NativeBinarySpecInternal binary ->
                        createTasksForBinary(binary)
                }
            }
        }

        static void createTasksForBinary(NativeBinarySpecInternal binary) {
            BinaryTasksCollection tasks = binary.getTasks()
            if (binary instanceof NativeExecutableBinarySpec || binary instanceof NativeTestSuiteBinarySpec) {
                createLinkExecutableTask(tasks, binary)
                createInstallTask(tasks, binary);
            } else if (binary instanceof SharedLibraryBinarySpec) {
                createLinkSharedLibraryTask(tasks, binary)
            } else if (binary instanceof StaticLibraryBinarySpec) {
                createStaticLibraryTask(tasks, binary)
            } else {
                throw new RuntimeException("Not a valid binary type for building: " + binary)
            }
        }

        static LinkExecutable createLinkExecutableTask(BinaryTasksCollection tasks, def executable) {
            def binary = executable as NativeBinarySpecInternal
            tasks.create(binary.namingScheme.getTaskName("link"), LinkExecutable, new Action<LinkExecutable>() {
                @Override
                void execute(LinkExecutable linkTask) {
                    linkTask.description = "Links ${executable}"

                    linkTask.toolChain = binary.toolChain
                    linkTask.targetPlatform = executable.targetPlatform

                    linkTask.lib { binary.libs*.linkFiles }

                    linkTask.conventionMapping.outputFile = { executable.executableFile }
                    linkTask.linkerArgs = binary.linker.args
                    binary.builtBy(linkTask)
                }
            })
        }

        static void createLinkSharedLibraryTask(BinaryTasksCollection tasks, SharedLibraryBinarySpec sharedLibrary) {
            def binary = sharedLibrary as NativeBinarySpecInternal
            tasks.create(binary.namingScheme.getTaskName("link"), LinkSharedLibrary, new Action<LinkSharedLibrary>() {
                @Override
                void execute(LinkSharedLibrary linkTask) {

                    linkTask.description = "Links ${sharedLibrary}"

                    linkTask.toolChain = binary.toolChain
                    linkTask.targetPlatform = binary.targetPlatform

                    linkTask.lib { binary.libs*.linkFiles }

                    linkTask.conventionMapping.outputFile = { sharedLibrary.sharedLibraryFile }
                    linkTask.conventionMapping.installName = { sharedLibrary.sharedLibraryFile.name }
                    linkTask.linkerArgs = binary.linker.args
                    binary.builtBy(linkTask)
                }
            })
        }

        static void createStaticLibraryTask(BinaryTasksCollection tasks, StaticLibraryBinarySpec staticLibrary) {
            def binary = staticLibrary as NativeBinarySpecInternal
            tasks.create(binary.namingScheme.getTaskName("create"), CreateStaticLibrary, new Action<CreateStaticLibrary>() {
                @Override
                void execute(CreateStaticLibrary createStaticLibrary) {
                    createStaticLibrary.description = "Creates ${staticLibrary}"
                    createStaticLibrary.toolChain = binary.toolChain
                    createStaticLibrary.targetPlatform = staticLibrary.targetPlatform
                    createStaticLibrary.conventionMapping.outputFile = { staticLibrary.staticLibraryFile }
                    createStaticLibrary.staticLibArgs = binary.staticLibArchiver.args
                    binary.builtBy(createStaticLibrary)
                }
            })
        }

        static createInstallTask(BinaryTasksCollection tasks, def executable) {
            def binary = executable as NativeBinarySpecInternal
            tasks.create(binary.namingScheme.getTaskName("install"), InstallExecutable, new Action<InstallExecutable>() {
                @Override
                void execute(InstallExecutable installTask) {
                    installTask.description = "Installs a development image of $executable"
                    installTask.group = LifecycleBasePlugin.BUILD_GROUP
                    installTask.toolChain = binary.toolChain

                    def project = installTask.project
                    installTask.conventionMapping.destinationDir = { project.file("${project.buildDir}/install/${binary.namingScheme.outputDirectoryBase}") }
                    installTask.conventionMapping.executable = { executable.executableFile }
                    installTask.lib { binary.libs*.runtimeFiles }

                    installTask.dependsOn(executable)
                }
            })
        }
    }
}
