/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.gradle.internal.precommit;

import org.elasticsearch.gradle.dependencies.CompileOnlyResolvePlugin;
import org.elasticsearch.gradle.internal.ExportElasticsearchBuildResourcesTask;
import org.elasticsearch.gradle.internal.conventions.precommit.PrecommitPlugin;
import org.elasticsearch.gradle.internal.info.BuildParams;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.tasks.TaskProvider;

import java.io.File;
import java.nio.file.Path;

import static org.elasticsearch.gradle.internal.util.DependenciesUtils.createFileCollectionFromNonTransitiveArtifactsView;

public class ThirdPartyAuditPrecommitPlugin extends PrecommitPlugin {

    public static final String JDK_JAR_HELL_CONFIG_NAME = "jdkJarHell";
    public static final String LIBS_ELASTICSEARCH_CORE_PROJECT_PATH = ":libs:core";

    @Override
    public TaskProvider<? extends Task> createTask(Project project) {
        project.getPlugins().apply(CompileOnlyResolvePlugin.class);
        project.getConfigurations().create("forbiddenApisCliJar");
        project.getDependencies().add("forbiddenApisCliJar", "de.thetaphi:forbiddenapis:3.6");
        Configuration jdkJarHellConfig = project.getConfigurations().create(JDK_JAR_HELL_CONFIG_NAME);
        if (project.getPath().equals(LIBS_ELASTICSEARCH_CORE_PROJECT_PATH) == false) {
            // Internal projects are not all plugins, so make sure the check is available
            // we are not doing this for this project itself to avoid jar hell with itself
            var elasticsearchCoreProject = project.findProject(LIBS_ELASTICSEARCH_CORE_PROJECT_PATH);
            if (elasticsearchCoreProject != null) {
                project.getDependencies().add(JDK_JAR_HELL_CONFIG_NAME, elasticsearchCoreProject);
            }
        }

        TaskProvider<ExportElasticsearchBuildResourcesTask> resourcesTask = project.getTasks()
            .register("thirdPartyAuditResources", ExportElasticsearchBuildResourcesTask.class);
        Path resourcesDir = project.getBuildDir().toPath().resolve("third-party-audit-config");
        resourcesTask.configure(t -> {
            t.setOutputDir(resourcesDir.toFile());
            t.copy("forbidden/third-party-audit.txt");
        });
        TaskProvider<ThirdPartyAuditTask> audit = project.getTasks().register("thirdPartyAudit", ThirdPartyAuditTask.class);
        // usually only one task is created. but this construct makes our integTests easier to setup
        project.getTasks().withType(ThirdPartyAuditTask.class).configureEach(t -> {
            Configuration runtimeConfiguration = project.getConfigurations().getByName("runtimeClasspath");
            Configuration compileOnly = project.getConfigurations()
                .getByName(CompileOnlyResolvePlugin.RESOLVEABLE_COMPILE_ONLY_CONFIGURATION_NAME);
            t.setClasspath(runtimeConfiguration.plus(compileOnly));
            t.getJarsToScan()
                .from(
                    createFileCollectionFromNonTransitiveArtifactsView(
                        runtimeConfiguration,
                        identifier -> identifier instanceof ModuleComponentIdentifier
                            && ((ModuleComponentIdentifier) identifier).getGroup().startsWith("org.elasticsearch") == false
                    )
                );
            t.dependsOn(resourcesTask);
            t.getTargetCompatibility().set(project.provider(BuildParams::getRuntimeJavaVersion));
            t.getJavaHome().set(project.provider(BuildParams::getRuntimeJavaHome).map(File::getPath));
            t.setSignatureFile(resourcesDir.resolve("forbidden/third-party-audit.txt").toFile());
            t.getJdkJarHellClasspath().from(jdkJarHellConfig);
            t.getForbiddenAPIsClasspath().from(project.getConfigurations().getByName("forbiddenApisCliJar").plus(compileOnly));
        });
        return audit;
    }

}
