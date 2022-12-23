/*
 * Original work copyright (c) 2015, Alex Antonov. All rights reserved.
 * Modified work copyright (c) 2015, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.meiskalt7.protobuf.gradle

import com.android.build.gradle.api.BaseVariant
import com.android.build.gradle.api.TestVariant
import com.android.build.gradle.api.UnitTestVariant
import com.android.builder.model.SourceProvider
import com.meiskalt7.protobuf.gradle.internal.DefaultProtoSourceSet
import com.meiskalt7.protobuf.gradle.internal.ProjectExt
import com.meiskalt7.protobuf.gradle.tasks.ProtoSourceSet
import groovy.transform.CompileStatic
import groovy.transform.TypeChecked
import groovy.transform.TypeCheckingMode
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.CopySpec
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.plugins.AppliedPlugin
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.SourceSet
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.util.GradleVersion

/**
 * The main class for the protobuf plugin.
 */
@CompileStatic
class ProtobufExtractPlugin implements Plugin<Project> {
    // any one of these plugins should be sufficient to proceed with applying this plugin
    private static final List<String> PREREQ_PLUGIN_OPTIONS = [
            'java',
            'java-library',
            'com.android.application',
            'com.android.feature',
            'com.android.library',
            'android',
            'android-library',
            'com.google.protobuf'
    ]

    private Project project
    private ProtobufExtractExtension protobufExtractExtension
    private boolean wasApplied = false

    void apply(final Project project) {
      if (GradleVersion.current() < GradleVersion.version("5.6")) {
        throw new GradleException(
          "Gradle version is ${project.gradle.gradleVersion}. Minimum supported version is 5.6")
      }

      this.protobufExtractExtension = project.extensions.create("protobufExtract", ProtobufExtractExtension, project)

      this.project = project

      // Provides the osdetector extension
      project.apply([plugin:com.google.gradle.osdetector.OsDetectorPlugin])

        // At least one of the prerequisite plugins must by applied before this plugin can be applied, so
        // we will use the PluginManager.withPlugin() callback mechanism to delay applying this plugin until
        // after that has been achieved. If project evaluation completes before one of the prerequisite plugins
        // has been applied then we will assume that none of prerequisite plugins were specified and we will
        // throw an Exception to alert the user of this configuration issue.
        Action<? super AppliedPlugin> applyWithPrerequisitePlugin = { AppliedPlugin prerequisitePlugin ->
          if (wasApplied) {
            project.logger.info('The com.google.protobuf plugin was already applied to the project: ' + project.path
                + ' and will not be applied again after plugin: ' + prerequisitePlugin.id)
          } else {
            wasApplied = true

            doApply()
          }
        }

        PREREQ_PLUGIN_OPTIONS.each { pluginName ->
          project.pluginManager.withPlugin(pluginName, applyWithPrerequisitePlugin)
        }

        project.afterEvaluate {
          if (!wasApplied) {
            throw new GradleException('The com.google.protobuf plugin could not be applied during project evaluation.'
                + ' The Java plugin or one of the Android plugins must be applied to the project first.')
          }
        }
    }

    @TypeChecked(TypeCheckingMode.SKIP) // Don't depend on AGP
    private void doApply() {
        boolean isAndroid = Utils.isAndroidProject(project)
        // Java projects will extract included protos from a 'compileProtoPath'
        // configuration of each source set, while Android projects will
        // extract included protos from {@code variant.compileConfiguration}
        // of each variant.
        Collection<Closure> postConfigure = []
        if (isAndroid) {
          project.android.sourceSets.configureEach { sourceSet ->
            ProtoSourceSet protoSourceSet = protobufExtractExtension.sourceSets.create(sourceSet.name)
            addSourceSetExtension(sourceSet, protoSourceSet)
            Configuration protobufConfig = createProtobufConfiguration(protoSourceSet)
            setupExtractProtosTask(protoSourceSet, protobufConfig)
          }

          NamedDomainObjectContainer<ProtoSourceSet> variantSourceSets =
            project.objects.domainObjectContainer(ProtoSourceSet) { String name ->
              new DefaultProtoSourceSet(name, project.objects)
            }
          ProjectExt.forEachVariant(this.project) { BaseVariant variant ->
            addTasksForVariant(variant, variantSourceSets, postConfigure)
          }
        } else {
          project.sourceSets.configureEach { sourceSet ->
            ProtoSourceSet protoSourceSet = protobufExtractExtension.sourceSets.create(sourceSet.name)
            addSourceSetExtension(sourceSet, protoSourceSet)
            Configuration protobufConfig = createProtobufConfiguration(protoSourceSet)
            addTasksForSourceSet(sourceSet, protoSourceSet, protobufConfig, postConfigure)
          }
        }
        project.afterEvaluate {
          postConfigure.each { it.call() }
        }
    }

    /**
     * Creates a 'protobufExtract' configuration for the given source set. The build author can
     * configure dependencies for it. The extract-protos task of each source set will
     * extract protobuf files from dependencies in this configuration.
     */
    private Configuration createProtobufConfiguration(ProtoSourceSet protoSourceSet) {
      String protobufConfigName = Utils.getConfigName(protoSourceSet.name, 'protobufExtract')
      return project.configurations.create(protobufConfigName) { Configuration it ->
        it.visible = false
        it.transitive = true
      }
    }

    /**
     * Adds the proto extension to the SourceSet, e.g., it creates
     * sourceSets.main.proto and sourceSets.test.proto.
     */
    @TypeChecked(TypeCheckingMode.SKIP) // Don't depend on AGP
    private SourceDirectorySet addSourceSetExtension(Object sourceSet, ProtoSourceSet protoSourceSet) {
      String name = sourceSet.name
      SourceDirectorySet sds = protoSourceSet.proto
      // sourceSet.extensions.add('proto', sds)
      sds.srcDir("src/${name}/proto")
      sds.include("**/*.proto")
      return sds
    }

    /**
     * Creates Protobuf tasks for a sourceSet in a Java project.
     */
    private void addTasksForSourceSet(
        SourceSet sourceSet, ProtoSourceSet protoSourceSet,
        Configuration protobufConfig, Collection<Closure> postConfigure) {
      Provider<ProtobufExtract> extractProtosTask = setupExtractProtosTask(protoSourceSet, protobufConfig)
      project.tasks.getByName("generateProto").dependsOn(extractProtosTask)

      // Make protos in 'test' sourceSet able to import protos from the 'main' sourceSet.
      // Pass include proto files from main to test.
      if (Utils.isTest(sourceSet.name)) {
        protoSourceSet.includesFrom(protobufExtractExtension.sourceSets.getByName("main"))
      }

      sourceSet.java.srcDirs(protoSourceSet.output)

      // Include source proto files in the compiled archive, so that proto files from
      // dependent projects can import them.
      project.tasks.named(sourceSet.getTaskName('process', 'resources'), ProcessResources).configure {
        it.from(protoSourceSet.proto) { CopySpec cs ->
          cs.include '**/*.proto'
        }
      }

      postConfigure.add {
        project.plugins.withId("idea") {
          boolean isTest = Utils.isTest(sourceSet.name)
          protoSourceSet.proto.srcDirs.each { File protoDir ->
            Utils.addToIdeSources(project, isTest, protoDir, false)
          }
          Utils.addToIdeSources(project, isTest, project.files(extractProtosTask).singleFile, true)
        }
      }
    }

    /**
     * Creates Protobuf tasks for a variant in an Android project.
     */
    @TypeChecked(TypeCheckingMode.SKIP) // Don't depend on AGP
    private void addTasksForVariant(
      Object variant,
      NamedDomainObjectContainer<ProtoSourceSet> variantSourceSets,
      Collection<Closure> postConfigure
    ) {
      ProtoSourceSet variantSourceSet = variantSourceSets.create(variant.name)

      // Make protos in 'test' variant able to import protos from the 'main' variant.
      // Pass include proto files from main to test.
      if (variant instanceof TestVariant || variant instanceof UnitTestVariant) {
        postConfigure.add {
          variantSourceSet.includesFrom(protobufExtractExtension.sourceSets.getByName("main"))
          variantSourceSet.includesFrom(variantSourceSets.getByName(variant.testedVariant.name))
        }
      }

      // GenerateProto task, one per variant (compilation unit).
      variant.sourceSets.each { SourceProvider sourceProvider ->
        variantSourceSet.extendsFrom(protobufExtractExtension.sourceSets.getByName(sourceProvider.name))
      }

      if (project.android.hasProperty('libraryVariants')) {
          // Include source proto files in the compiled archive, so that proto files from
          // dependent projects can import them.
          variant.getProcessJavaResourcesProvider().configure {
            it.from(variantSourceSet.proto) {
              include '**/*.proto'
            }
          }
      }
    }

    /**
     * Sets up a task to extract protos from protobuf dependencies. They are
     * treated as sources and will be compiled.
     *
     * <p>This task is per-sourceSet, for both Java and Android. In Android a
     * variant may have multiple sourceSets, each of these sourceSets will have
     * its own extraction task.
     */
    private Provider<ProtobufExtract> setupExtractProtosTask(
      ProtoSourceSet protoSourceSet,
      Configuration protobufConfig
    ) {
      String sourceSetName = protoSourceSet.name
      String taskName = getExtractProtosTaskName(sourceSetName)
      Provider<ProtobufExtract> task = project.tasks.register(taskName, ProtobufExtract) {
        it.description = "Extracts proto files/dependencies specified by 'protobufExtract' configuration"
        it.destDir.set(getExtractedProtosDir(sourceSetName) as File)
        it.inputFiles.from(protobufConfig)
      }
      protoSourceSet.proto.srcDir(task)
      return task
    }

    private String getExtractProtosTaskName(String sourceSetName) {
      return 'extract' + Utils.getSourceSetSubstringForTaskNames(sourceSetName) + 'Protoschemas'
    }

    private String getExtractedProtosDir(String sourceSetName) {
      return "${project.buildDir}/extracted-protos/${sourceSetName}"
    }
}
