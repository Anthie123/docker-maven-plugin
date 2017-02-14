package io.fabric8.maven.docker;

import java.io.File;
import java.io.IOException;
import java.util.*;

import com.google.common.collect.ImmutableMap;
import io.fabric8.maven.docker.access.DockerAccessException;
import io.fabric8.maven.docker.assembly.DockerAssemblyManager;
import io.fabric8.maven.docker.config.AssemblyConfiguration;
import io.fabric8.maven.docker.config.BuildImageConfiguration;
import io.fabric8.maven.docker.config.ImageConfiguration;
import io.fabric8.maven.docker.service.AuthService;
import io.fabric8.maven.docker.service.BuildService;
import io.fabric8.maven.docker.service.ServiceHub;
import io.fabric8.maven.docker.util.*;
import org.apache.maven.archiver.MavenArchiveConfiguration;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.shared.filtering.MavenFileFilter;
import org.apache.maven.shared.filtering.MavenReaderFilter;

/**
 * @author roland
 * @author balazsmaria
 * @since 26/06/15
 */
abstract public class AbstractBuildSupportMojo extends AbstractDockerMojo {
    // ==============================================================================================================
    // Parameters required from Maven when building an assembly. They cannot be injected directly
    // into DockerAssemblyCreator.
    // See also here: http://maven.40175.n5.nabble.com/Mojo-Java-1-5-Component-MavenProject-returns-null-vs-JavaDoc-parameter-expression-quot-project-quot-s-td5733805.html

    @Parameter
    private MavenArchiveConfiguration archive;

    @Component
    private MavenFileFilter mavenFileFilter;

    @Component
    private MavenReaderFilter mavenFilterReader;

    @Parameter
    private Map<String, String> buildArgs;

    @Parameter(property = "docker.pull.registry")
    private String pullRegistry;

    @Parameter(property = "docker.source.dir", defaultValue="src/main/docker")
    private String sourceDirectory;

    @Parameter(property = "docker.target.dir", defaultValue="target/docker")
    private String outputDirectory;

    @Override
    protected BuildService.BuildContext.Builder getBuildContextBuilder() throws MojoExecutionException {
        return super.getBuildContextBuilder()
                .buildArgs(buildArgs)
                .mojoParameters(createMojoParameters())
                .pullRegistry(pullRegistry);
    }

    @Override
    protected AuthService.AuthParameters.Builder getAuthParametersBuilder() throws MojoExecutionException {
        return super.getAuthParametersBuilder()
                .mojoParameters(createMojoParameters());
    }

    protected MojoParameters createMojoParameters() {
        return new MojoParameters(session, project, archive, mavenFileFilter, mavenFilterReader,
                                  settings, sourceDirectory, outputDirectory);
    }


}
