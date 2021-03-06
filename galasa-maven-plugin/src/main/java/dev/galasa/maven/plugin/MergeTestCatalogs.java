/*
 * Licensed Materials - Property of IBM
 * 
 * (c) Copyright IBM Corp. 2019.
 */
package dev.galasa.maven.plugin;

import java.io.File;
import java.time.Instant;
import java.util.List;
import java.util.Map.Entry;

import org.apache.commons.io.FileUtils;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResult;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Merge all the test catalogs on the dependency list
 * 
 * @author Michael Baylis
 *
 */
@Mojo(name = "mergetestcat", defaultPhase = LifecyclePhase.PACKAGE, threadSafe = true, requiresDependencyCollection = ResolutionScope.COMPILE_PLUS_RUNTIME, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class MergeTestCatalogs extends AbstractMojo {
    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject            project;

    @Component
    private MavenProjectHelper      projectHelper;

    @Component
    private RepositorySystem        repoSystem;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    private RepositorySystemSession repoSession;

    @Parameter(defaultValue = "${project.build.directory}", property = "outputDir", required = true)
    private File                    outputDirectory;

    @Parameter(defaultValue = "${galasa.skip.bundletestcatatlog}", readonly = true, required = false)
    private boolean                 skip;

    @Parameter(defaultValue = "${galasa.build.job}", readonly = true, required = false)
    private String                  buildJob;

    public void execute() throws MojoExecutionException, MojoFailureException {

        if (skip) {
            getLog().info("Skipping Bundle Test Catalog build");
            return;
        }

        if (!"galasa-obr".equals(project.getPackaging())) {
            getLog().info("Skipping Bundle Test Catalog merge, not a galasa-obr project");
            return;
        }

        if (!outputDirectory.exists()) {
            outputDirectory.mkdirs();
        }

        try {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();

            // *** Create the Master
            JsonObject jsonRoot = new JsonObject();
            JsonObject jsonClasses = new JsonObject();
            jsonRoot.add("classes", jsonClasses);
            JsonObject jsonPackages = new JsonObject();
            jsonRoot.add("packages", jsonPackages);
            JsonObject jsonBundles = new JsonObject();
            jsonRoot.add("bundles", jsonBundles);
            JsonObject jsonSenv = new JsonObject();
            jsonRoot.add("sharedEnvironments", jsonSenv);

            jsonRoot.addProperty("name", project.getName());

            Instant now = Instant.now();

            if (buildJob == null || buildJob.trim().isEmpty()) {
                buildJob = project.getGroupId() + ":" + project.getArtifactId() + ":" + project.getVersion() + " - "
                        + now.toString();
            }

            jsonRoot.addProperty("build", buildJob);
            jsonRoot.addProperty("version", project.getVersion());
            jsonRoot.addProperty("built", now.toString());

            List<Dependency> dependencies = project.getDependencies();
            for (Dependency dependency : dependencies) {
                if (!"compile".equals(dependency.getScope())) {
                    continue;
                }
                if (!"jar".equals(dependency.getType())) {
                    continue;
                }

                // *** Try and see if the dependency has a sister test catalog
                DefaultArtifact artifactTestCatalog = new DefaultArtifact(dependency.getGroupId(),
                        dependency.getArtifactId(), "testcatalog", "json", dependency.getVersion());

                ArtifactRequest request = new ArtifactRequest();
                request.setArtifact(artifactTestCatalog);

                ArtifactResult result = null;
                try {
                    result = repoSystem.resolveArtifact(repoSession, request);
                } catch (Exception e) {
                    getLog().warn(e.getMessage());
                }

                if (result != null) {
                    getLog().info("Merging bundle test catalog " + result.getArtifact().toString());

                    String subTestCatalog = FileUtils.readFileToString(result.getArtifact().getFile(), "utf-8");
                    JsonObject testCatalogRoot = gson.fromJson(subTestCatalog, JsonObject.class);

                    // *** Append/replace all the test classes
                    JsonObject subTestClasses = testCatalogRoot.getAsJsonObject("classes");
                    for (Entry<String, JsonElement> testClassEntry : subTestClasses.entrySet()) {
                        String name = testClassEntry.getKey();
                        JsonElement tc = testClassEntry.getValue();

                        jsonClasses.add(name, tc);
                    }

                    // *** Append to the packages
                    JsonObject subPackages = testCatalogRoot.getAsJsonObject("packages");
                    for (Entry<String, JsonElement> packageEntry : subPackages.entrySet()) {
                        String name = packageEntry.getKey();
                        JsonArray list = (JsonArray) packageEntry.getValue();

                        JsonArray mergedPackage = jsonPackages.getAsJsonArray(name);
                        if (mergedPackage == null) {
                            mergedPackage = new JsonArray();
                            jsonPackages.add(name, mergedPackage);
                        }

                        for (int i = 0; i < list.size(); i++) {
                            String className = list.get(i).getAsString();
                            mergedPackage.add(className);
                        }
                    }

                    // *** Append/replace all the bundles
                    JsonObject subBundles = testCatalogRoot.getAsJsonObject("bundles");
                    for (Entry<String, JsonElement> bundleEntry : subBundles.entrySet()) {
                        String name = bundleEntry.getKey();
                        JsonElement tc = bundleEntry.getValue();

                        jsonBundles.add(name, tc);
                    }

                    // *** Append/replace all the Shared Environments
                    JsonObject subSenv = testCatalogRoot.getAsJsonObject("sharedEnvironments");
                    for (Entry<String, JsonElement> senvEntry : subSenv.entrySet()) {
                        String name = senvEntry.getKey();
                        JsonElement tc = senvEntry.getValue();

                        jsonSenv.add(name, tc);
                    }
                }
            }

            // *** Write the new master test catalog
            String testCatlog = gson.toJson(jsonRoot);

            File fileTestCatalog = new File(outputDirectory, "testcatalog.json");
            FileUtils.writeStringToFile(fileTestCatalog, testCatlog, "utf-8");

            projectHelper.attachArtifact(project, "json", "testcatalog", fileTestCatalog);
        } catch (Throwable t) {
            throw new MojoExecutionException("Problem merging the test catalog", t);
        }

    }

}
