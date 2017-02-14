package io.fabric8.maven.docker.service;

import com.google.common.collect.ImmutableMap;

import io.fabric8.maven.docker.access.AuthConfig;
import io.fabric8.maven.docker.access.BuildOptions;
import io.fabric8.maven.docker.access.DockerAccess;
import io.fabric8.maven.docker.access.DockerAccessException;
import io.fabric8.maven.docker.assembly.DockerAssemblyManager;
import io.fabric8.maven.docker.config.AssemblyConfiguration;
import io.fabric8.maven.docker.config.BuildImageConfiguration;
import io.fabric8.maven.docker.config.CleanupMode;
import io.fabric8.maven.docker.config.ImageConfiguration;
import io.fabric8.maven.docker.util.AuthConfigFactory;
import io.fabric8.maven.docker.util.DockerFileUtil;
import io.fabric8.maven.docker.util.EnvUtil;
import io.fabric8.maven.docker.util.ImageName;
import io.fabric8.maven.docker.util.ImagePullCache;
import io.fabric8.maven.docker.util.Logger;
import io.fabric8.maven.docker.util.MojoParameters;
import org.apache.maven.plugin.MojoExecutionException;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class BuildService {

    // Key for the previously used image cache
    public static final String CONTEXT_KEY_PREVIOUSLY_PULLED = "CONTEXT_KEY_PREVIOUSLY_PULLED";

    private final DockerAccess docker;
    private final QueryService queryService;
    public final ArchiveService archiveService;
    public final AuthService authService;
    private final Logger log;

    BuildService(DockerAccess docker, QueryService queryService, ArchiveService archiveService, AuthService authService, Logger log) {
        this.docker = docker;
        this.queryService = queryService;
        this.archiveService = archiveService;
        this.authService = authService;
        this.log = log;
    }

    public void executeBuildWorkflow(ImageConfiguration imageConfig, BuildContext buildContext)
            throws DockerAccessException, MojoExecutionException {

        autoPullBaseImage(imageConfig, buildContext);

        buildImage(imageConfig, buildContext.getMojoParameters(), checkForNocache(imageConfig), addBuildArgs(buildContext));
    }

    /**
     * Check an image, and, if <code>autoPull</code> is set to true, fetch it. Otherwise if the image
     * is not existent, throw an error
     * @param image image name
     * @param registry optional registry which is used if the image itself doesn't have a registry.
     * @param autoPullAlwaysAllowed whether an unconditional autopull is allowed.
     * @throws DockerAccessException
     * @throws MojoExecutionException
     */
    public void checkImageWithAutoPull(String image, String registry, boolean autoPullAlwaysAllowed, BuildContext buildContext) throws DockerAccessException, MojoExecutionException {
        // TODO: further refactoring could be done to avoid referencing the QueryService here
        ImagePullCache previouslyPulledCache = getPreviouslyPulledImageCache(buildContext);
        if (!queryService.imageRequiresAutoPull(buildContext.getAutoPull(), image, autoPullAlwaysAllowed, previouslyPulledCache)) {
            return;
        }

        ImageName imageName = new ImageName(image);
        long time = System.currentTimeMillis();
        docker.pullImage(withLatestIfNoTag(image), authService.prepareAuthConfig(imageName, registry, false, buildContext.getAuthParameters()), registry);
        log.info("Pulled %s in %s", imageName.getFullName(), EnvUtil.formatDurationTill(time));
        updatePreviousPulledImageCache(image, buildContext);

        if (registry != null && !imageName.hasRegistry()) {
            // If coming from a registry which was not contained in the original name, add a tag from the
            // full name with the registry to the short name with no-registry.
            docker.tag(imageName.getFullName(registry), image, false);
        }
    }


    /**
     * Build an image
     *
     * @param imageConfig the image configuration
     * @param params mojo params for the project
     * @param noCache if not null, dictate the caching behaviour. Otherwise its taken from the build configuration
     * @param buildArgs
     * @throws DockerAccessException
     * @throws MojoExecutionException
     */
    public void buildImage(ImageConfiguration imageConfig, MojoParameters params, boolean noCache, Map<String, String> buildArgs)
        throws DockerAccessException, MojoExecutionException {

        String imageName = imageConfig.getName();
        ImageName.validate(imageName);

        BuildImageConfiguration buildConfig = imageConfig.getBuildConfiguration();

        String oldImageId = null;

        CleanupMode cleanupMode = buildConfig.cleanupMode();
        if (cleanupMode.isRemove()) {
            oldImageId = queryService.getImageId(imageName);
        }

        long time = System.currentTimeMillis();

        if (buildConfig.getDockerArchive() != null) {
            docker.loadImage(imageName, buildConfig.getAbsoluteDockerTarPath(params));
            log.info("%s: Loaded tarball in %s", buildConfig.getDockerArchive(), EnvUtil.formatDurationTill(time));
            return;
        }

        File dockerArchive = archiveService.createArchive(imageName, buildConfig, params, log);
        log.info("%s: Created %s in %s", dockerArchive.getName(), imageConfig.getDescription(), EnvUtil.formatDurationTill(time));

        Map<String, String> mergedBuildMap = prepareBuildArgs(buildArgs, buildConfig);

        // auto is now supported by docker, consider switching?
        BuildOptions opts =
            new BuildOptions(buildConfig.getBuildOptions())
            .dockerfile(getDockerfileName(buildConfig))
            .forceRemove(cleanupMode.isRemove())
            .noCache(noCache)
            .buildArgs(mergedBuildMap);
        String newImageId = doBuildImage(imageName, dockerArchive, opts);
        log.info("%s: Built image %s",imageConfig.getDescription(), newImageId);

        if (oldImageId != null && !oldImageId.equals(newImageId)) {
            try {
                docker.removeImage(oldImageId, true);
                log.info("%s: Removed old image %s", imageConfig.getDescription(), oldImageId);
            } catch (DockerAccessException exp) {
                if (cleanupMode == CleanupMode.TRY_TO_REMOVE) {
                    log.warn("%s: %s (old image)%s", imageConfig.getDescription(), exp.getMessage(),
                             (exp.getCause() != null ? " [" + exp.getCause().getMessage() + "]" : ""));
                } else {
                    throw exp;
                }
            }
        }
    }

    private Map<String, String> prepareBuildArgs(Map<String, String> buildArgs, BuildImageConfiguration buildConfig) {
        ImmutableMap.Builder<String, String> builder = ImmutableMap.<String, String>builder().putAll(buildArgs);
        if (buildConfig.getArgs() != null) {
            builder.putAll(buildConfig.getArgs());
        }
        return builder.build();
    }

    private String getDockerfileName(BuildImageConfiguration buildConfig) {
        if (buildConfig.isDockerFileMode()) {
            return buildConfig.getDockerFile().getName();
        } else {
            return null;
        }
    }

    private String doBuildImage(String imageName, File dockerArchive, BuildOptions options)
        throws DockerAccessException, MojoExecutionException {
        docker.buildImage(imageName, dockerArchive, options);
        return queryService.getImageId(imageName);
    }

    // ============================================

    private Map<String, String> addBuildArgs(BuildContext buildContext) {
        Map<String, String> buildArgsFromProject = addBuildArgsFromProperties(buildContext.getMojoParameters().getProject().getProperties());
        Map<String, String> buildArgsFromSystem = addBuildArgsFromProperties(System.getProperties());
        return ImmutableMap.<String, String>builder()
                .putAll(buildContext.getBuildArgs() != null ? buildContext.getBuildArgs() : Collections.<String, String>emptyMap())
                .putAll(buildArgsFromProject)
                .putAll(buildArgsFromSystem)
                .build();
    }

    private Map<String, String> addBuildArgsFromProperties(Properties properties) {
        String argPrefix = "docker.buildArg.";
        Map<String, String> buildArgs = new HashMap<>();
        for (Object keyObj : properties.keySet()) {
            String key = (String) keyObj;
            if (key.startsWith(argPrefix)) {
                String argKey = key.replaceFirst(argPrefix, "");
                String value = properties.getProperty(key);

                if (!isEmpty(value)) {
                    buildArgs.put(argKey, value);
                }
            }
        }
        log.debug("Build args set %s", buildArgs);
        return buildArgs;
    }

    private void autoPullBaseImage(ImageConfiguration imageConfig, BuildContext buildContext)
            throws DockerAccessException, MojoExecutionException {
        BuildImageConfiguration buildConfig = imageConfig.getBuildConfiguration();

        if (buildConfig.getDockerArchive() != null) {
            // No auto pull needed in archive mode
            return;
        }

        String fromImage;
        if (buildConfig.isDockerFileMode()) {
            fromImage = extractBaseFromDockerfile(buildConfig, buildContext);
        } else {
            fromImage = extractBaseFromConfiguration(buildConfig);
        }
        if (fromImage != null && !DockerAssemblyManager.SCRATCH_IMAGE.equals(fromImage)) {
            String pullRegistry =
                    EnvUtil.findRegistry(new ImageName(fromImage).getRegistry(), buildContext.getPullRegistry(), buildContext.getRegistry());
            checkImageWithAutoPull(fromImage, pullRegistry, true, buildContext);
        }
    }

    private String extractBaseFromConfiguration(BuildImageConfiguration buildConfig) {
        String fromImage;
        fromImage = buildConfig.getFrom();
        if (fromImage == null) {
            AssemblyConfiguration assemblyConfig = buildConfig.getAssemblyConfiguration();
            if (assemblyConfig == null) {
                fromImage = DockerAssemblyManager.DEFAULT_DATA_BASE_IMAGE;
            }
        }
        return fromImage;
    }

    private String extractBaseFromDockerfile(BuildImageConfiguration buildConfig, BuildContext buildContext) {
        String fromImage;
        try {
            File fullDockerFilePath = buildConfig.getAbsoluteDockerFilePath(buildContext.getMojoParameters());
            fromImage = DockerFileUtil.extractBaseImage(fullDockerFilePath);
        } catch (IOException e) {
            // Cant extract base image, so we wont try an auto pull. An error will occur later anyway when
            // building the image, so we are passive here.
            fromImage = null;
        }
        return fromImage;
    }

    private boolean checkForNocache(ImageConfiguration imageConfig) {
        String nocache = System.getProperty("docker.nocache");
        if (nocache != null) {
            return nocache.length() == 0 || Boolean.valueOf(nocache);
        } else {
            BuildImageConfiguration buildConfig = imageConfig.getBuildConfiguration();
            return buildConfig.nocache();
        }
    }

    private void updatePreviousPulledImageCache(String image, BuildContext buildContext) {
        ImagePullCache cache = getPreviouslyPulledImageCache(buildContext);
        cache.add(image);
        buildContext.getMojoParameters().getSession().getUserProperties().setProperty(CONTEXT_KEY_PREVIOUSLY_PULLED, cache.toString());
    }

    private synchronized ImagePullCache getPreviouslyPulledImageCache(BuildContext buildContext) {
        @SuppressWarnings({ "rawtypes", "unchecked" })
        Properties userProperties = buildContext.getMojoParameters().getSession().getUserProperties();
        String pullCacheJson = userProperties.getProperty(CONTEXT_KEY_PREVIOUSLY_PULLED);
        ImagePullCache cache = new ImagePullCache(pullCacheJson);
        if (pullCacheJson == null) {
            userProperties.put(CONTEXT_KEY_PREVIOUSLY_PULLED, cache.toString());
        }
        return cache;
    }

    // Fetch only latest if no tag is given
    private String withLatestIfNoTag(String name) {
        ImageName imageName = new ImageName(name);
        return imageName.getTag() == null ? imageName.getNameWithoutTag() + ":latest" : name;
    }

    private boolean isEmpty(String str) {
        return str == null || str.isEmpty();
    }

    // ===========================================

    public static class BuildContext implements Serializable {

        private MojoParameters mojoParameters;

        private Map pluginContext;

        private Date buildTimestamp;

        private Map<String, String> buildArgs;

        private String pullRegistry;

        private String registry;

        private String autoPull;

        private AuthService.AuthParameters authParameters;

        public BuildContext() {}

        public MojoParameters getMojoParameters() {
            return mojoParameters;
        }

        public void setMojoParameters(MojoParameters mojoParameters) {
            this.mojoParameters = mojoParameters;
        }

        public Map getPluginContext() {
            return pluginContext;
        }

        public void setPluginContext(Map pluginContext) {
            this.pluginContext = pluginContext;
        }

        public Date getBuildTimestamp() {
            return buildTimestamp;
        }

        public void setBuildTimestamp(Date buildTimestamp) {
            this.buildTimestamp = buildTimestamp;
        }

        public Map<String, String> getBuildArgs() {
            return buildArgs;
        }

        public void setBuildArgs(Map<String, String> buildArgs) {
            this.buildArgs = buildArgs;
        }

        public String getPullRegistry() {
            return pullRegistry;
        }

        public void setPullRegistry(String pullRegistry) {
            this.pullRegistry = pullRegistry;
        }

        public String getRegistry() {
            return registry;
        }

        public void setRegistry(String registry) {
            this.registry = registry;
        }

        public String getAutoPull() {
            return autoPull;
        }

        public void setAutoPull(String autoPull) {
            this.autoPull = autoPull;
        }

        public AuthService.AuthParameters getAuthParameters() {
            return authParameters;
        }

        public void setAuthParameters(AuthService.AuthParameters authParameters) {
            this.authParameters = authParameters;
        }

        // ===========================================

        public static class Builder {

            private BuildContext context = new BuildContext();

            public Builder() {
                this.context = new BuildContext();
            }

            public Builder mojoParameters(MojoParameters mojoParameters) {
                context.setMojoParameters(mojoParameters);
                return this;
            }

            public Builder pluginContext(Map pluginContext) {
                context.setPluginContext(pluginContext);
                return this;
            }

            public Builder buildTimestamp(Date buildTimestamp) {
                context.setBuildTimestamp(buildTimestamp);
                return this;
            }

            public Builder buildArgs(Map<String, String> buildArgs) {
                context.setBuildArgs(buildArgs);
                return this;
            }

            public Builder pullRegistry(String pullRegistry) {
                context.setPullRegistry(pullRegistry);
                return this;
            }

            public Builder registry(String registry) {
                context.setRegistry(registry);
                return this;
            }

            public Builder autoPull(String autoPull) {
                context.setAutoPull(autoPull);
                return this;
            }

            public Builder authParameters(AuthService.AuthParameters authParameters) {
                context.setAuthParameters(authParameters);
                return this;
            }

            public BuildContext build() {
                return context;
            }
        }
    }

}
