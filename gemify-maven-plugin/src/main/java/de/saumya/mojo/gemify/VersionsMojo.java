package de.saumya.mojo.gemify;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.repository.RepositorySystem;
import org.codehaus.plexus.util.IOUtil;
import org.sonatype.aether.RepositorySystemSession;

import de.saumya.mojo.gems.Maven2GemVersionConverter;
import de.saumya.mojo.gems.MavenArtifactConverter;
import de.saumya.mojo.ruby.gems.GemException;
import de.saumya.mojo.ruby.gems.GemManager;

/**
 * Goal which list the versions for the given gemname.
 * 
 * @goal versions
 * @requiresProject false
 */
public class VersionsMojo extends AbstractMojo {

    private static final List<ArtifactRepository> EMPTY_REPO_LIST = Collections.emptyList();

    /**
     * gemname to identify the maven artifact (format: groupId.artifactId).
     * 
     * @parameter default-value="${gemify.gemname}"
     * @required
     */
    private String                  gemname;

    /**
     * local repository for internal use.
     * 
     * @parameter default-value="${localRepository}"
     * @required
     * @readonly
     */
    protected ArtifactRepository    localRepository;

    /**
     * reference to maven project for internal use.
     * 
     * @parameter default-value="${project}"
     * @required
     * @readonly true
     */
    protected MavenProject          project;

    /**
     * @parameter default-value="${repositorySystemSession}"
     * @readonly
     */
    private RepositorySystemSession repositorySession;

    /** @component */
    private ProjectBuilder          builder;

    /** @component */
    protected GemManager            manager;

    public void execute() throws MojoExecutionException {
        if (this.gemname == null) {
            throw new MojoExecutionException("no gemname given, use '-Dgemify.gemname=...' to specify one");
        }
        // remove the mvn:prefix if any
        this.gemname = this.gemname.replaceFirst("^mvn:", "");
        if (!this.gemname.contains(GemManager.GROUP_ID_ARTIFACT_ID_SEPARATOR)) {
            throw new MojoExecutionException("not valid name for a maven-gem, it needs a at least one '" + GemManager.GROUP_ID_ARTIFACT_ID_SEPARATOR 
                                             + "'");
        }

        try {
            // first get all maven-versions
            final Artifact artifact = this.manager.createJarArtifactForGemname(this.gemname);
            final List<ArtifactRepository> repos;
            if(repositorySession.isOffline()){
                repos = EMPTY_REPO_LIST;
            }
            else {
                repos = this.project.getRemoteArtifactRepositories();
            }
            final List<String> versions = this.manager.availableVersions(artifact, this.localRepository, repos);
            getLog().debug("raw versions: " + versions);
            // now convert the maven-versions into gem-versions
            final List<String> gemVersions = new ArrayList<String>(versions.size());
            final Maven2GemVersionConverter converter = new Maven2GemVersionConverter();
            for (final String version : versions) {
                final ProjectBuildingRequest request = new DefaultProjectBuildingRequest();
                request.setLocalRepository(this.localRepository)
                        .setRemoteRepositories(this.project.getRemoteArtifactRepositories())
                        .setResolveDependencies(false)
                        .setRepositorySession(this.repositorySession)
                        .setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL);
                try {
                    artifact.setVersion(version);
                    // build the POM for that artifact to ensure that is
                    // possible when using
                    // that version for building a gem - i.e.
                    // org.slf4j.slf4j:log4j12:1.1.0-RC0
                    // has no parent-pom in the central repository
                    this.builder.build(artifact, request);
                    gemVersions.add(converter.createGemVersion(version));
                }
                catch (final ProjectBuildingException e) {
                    if (getLog().isDebugEnabled()) {
                        getLog().debug("skip version (pom does not load): "
                                               + version,
                                       e);
                    }
                    else {
                        getLog().info("skip version (pom does not load): "
                                + version);
                    }
                }
            }
            // print result for user
            getLog().info("\n\n\t" + MavenArtifactConverter.GEMNAME_PREFIX + this.gemname + " " + gemVersions + "\n\n");
        }
        catch (final GemException e) {
            throw new MojoExecutionException("error finding versions for: "
                    + this.gemname, e);
        }
    }
}
