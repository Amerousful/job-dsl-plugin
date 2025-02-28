package javaposse.jobdsl.plugin;

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.Collections2;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.ExtensionList;
import hudson.ExtensionListListener;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.AbstractItem;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Items;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.View;
import hudson.model.ViewGroup;
import hudson.tasks.Builder;
import javaposse.jobdsl.dsl.DslException;
import javaposse.jobdsl.dsl.GeneratedConfigFile;
import javaposse.jobdsl.dsl.GeneratedItems;
import javaposse.jobdsl.dsl.GeneratedJob;
import javaposse.jobdsl.dsl.GeneratedUserContent;
import javaposse.jobdsl.dsl.GeneratedView;
import javaposse.jobdsl.dsl.JobManagement;
import javaposse.jobdsl.dsl.ScriptRequest;
import javaposse.jobdsl.plugin.actions.GeneratedConfigFilesAction;
import javaposse.jobdsl.plugin.actions.GeneratedConfigFilesBuildAction;
import javaposse.jobdsl.plugin.actions.GeneratedJobsAction;
import javaposse.jobdsl.plugin.actions.GeneratedJobsBuildAction;
import javaposse.jobdsl.plugin.actions.GeneratedUserContentsAction;
import javaposse.jobdsl.plugin.actions.GeneratedUserContentsBuildAction;
import javaposse.jobdsl.plugin.actions.GeneratedViewsAction;
import javaposse.jobdsl.plugin.actions.GeneratedViewsBuildAction;
import javaposse.jobdsl.plugin.actions.GeneratedObjectsRunAction;
import jenkins.model.Jenkins;
import jenkins.model.ParameterizedJobMixIn.ParameterizedJob;
import jenkins.tasks.SimpleBuildStep;
import org.acegisecurity.AccessDeniedException;
import org.apache.commons.io.FilenameUtils;
import org.jenkinsci.plugins.configfiles.GlobalConfigFiles;
import org.jenkinsci.plugins.scriptsecurity.scripts.ApprovalContext;
import org.jenkinsci.plugins.scriptsecurity.scripts.ScriptApproval;
import org.jenkinsci.plugins.scriptsecurity.scripts.languages.GroovyLanguage;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import static hudson.Util.fixEmptyAndTrim;
import static java.lang.String.format;
import static javaposse.jobdsl.plugin.actions.GeneratedObjectsAction.extractGeneratedObjects;

/**
 * This Builder keeps a list of job DSL scripts, and when prompted, executes these to create /
 * update Jenkins jobs.
 */
public class ExecuteDslScripts extends Builder implements SimpleBuildStep {
    private static final Logger LOGGER = Logger.getLogger(ExecuteDslScripts.class.getName());

    private static volatile boolean rebootRequired;

    /**
     * Newline-separated list of locations to load as dsl scripts.
     */
    private String targets;

    /**
     * Text of a dsl script.
     */
    private String scriptText;

    /**
     * Whether we're using some text for the script directly
     */
    private Boolean usingScriptText;

    private boolean sandbox;

    private boolean ignoreExisting;

    private boolean ignoreMissingFiles;

    private boolean failOnMissingPlugin;

    private boolean failOnSeedCollision;

    private boolean unstableOnDeprecation;

    private RemovedJobAction removedJobAction = RemovedJobAction.IGNORE;

    private RemovedViewAction removedViewAction = RemovedViewAction.IGNORE;

    private RemovedConfigFilesAction removedConfigFilesAction = RemovedConfigFilesAction.IGNORE;

    private LookupStrategy lookupStrategy = LookupStrategy.JENKINS_ROOT;

    private String additionalClasspath;

    private Map<String, Object> additionalParameters;

    static {
        ExtensionList.lookup(Descriptor.class).addListener(new ExtensionListListener() {
            @Override
            public void onChange() {
                rebootRequired = true;
            }
        });
    }

    @DataBoundConstructor
    public ExecuteDslScripts() {
    }

    ExecuteDslScripts(String scriptText) {
        this.usingScriptText = true;
        this.scriptText = scriptText;
        this.targets = null;
        this.ignoreExisting = false;
        this.failOnSeedCollision = false;
        this.removedJobAction = RemovedJobAction.DISABLE;
        this.additionalClasspath = null;
    }

    public String getTargets() {
        return !this.isUsingScriptText() ? this.targets : null;
    }

    @DataBoundSetter
    public void setTargets(String targets) {
        this.targets = fixEmptyAndTrim(targets);
    }

    public String getScriptText() {
        return this.isUsingScriptText() ? this.scriptText : null;
    }

    @DataBoundSetter
    public void setScriptText(String scriptText) {
        this.scriptText = fixEmptyAndTrim(scriptText);
    }

    public boolean isUsingScriptText() {
        return usingScriptText == null ? (targets == null) : usingScriptText;
    }

    // We want to be able to set this, but we never want it to return a value.
    // This will prevent the snippet generator generating output for this field,
    // while also not throwing an exception due to missing getter.
    public Boolean getUseScriptText() {
        return null;
    }

    // This property is optional and one-directional (set only).
    // It is set only from the UI and will force usingScriptText to true or
    // false, based on the UI databound value.
    @DataBoundSetter
    public void setUseScriptText(Boolean value) {
        this.usingScriptText = value;
    }

    public boolean isSandbox() {
        return sandbox;
    }

    @DataBoundSetter
    public void setSandbox(boolean sandbox) {
        this.sandbox = sandbox;
    }

    public boolean isIgnoreMissingFiles() {
        return !this.isUsingScriptText() && this.ignoreMissingFiles;
    }

    @DataBoundSetter
    public void setIgnoreMissingFiles(boolean ignoreMissingFiles) {
        this.ignoreMissingFiles = ignoreMissingFiles;
    }

    public boolean isIgnoreExisting() {
        return ignoreExisting;
    }

    @DataBoundSetter
    public void setIgnoreExisting(boolean ignoreExisting) {
        this.ignoreExisting = ignoreExisting;
    }

    public boolean isFailOnMissingPlugin() {
        return failOnMissingPlugin;
    }

    @DataBoundSetter
    public void setFailOnMissingPlugin(boolean failOnMissingPlugin) {
        this.failOnMissingPlugin = failOnMissingPlugin;
    }

    public boolean isFailOnSeedCollision() {
        return failOnSeedCollision;
    }

    @DataBoundSetter
    public void setFailOnSeedCollision(boolean failOnSeedCollision) {
        this.failOnSeedCollision = failOnSeedCollision;
    }

    public boolean isUnstableOnDeprecation() {
        return unstableOnDeprecation;
    }

    @DataBoundSetter
    public void setUnstableOnDeprecation(boolean unstableOnDeprecation) {
        this.unstableOnDeprecation = unstableOnDeprecation;
    }

    public RemovedJobAction getRemovedJobAction() {
        return removedJobAction;
    }

    @DataBoundSetter
    public void setRemovedJobAction(RemovedJobAction removedJobAction) {
        this.removedJobAction = removedJobAction;
    }

    public RemovedViewAction getRemovedViewAction() {
        return removedViewAction;
    }

    @DataBoundSetter
    public void setRemovedViewAction(RemovedViewAction removedViewAction) {
        this.removedViewAction = removedViewAction;
    }

    /**
     * @since 1.62
     */
    public RemovedConfigFilesAction getRemovedConfigFilesAction() {
        return removedConfigFilesAction;
    }

    /**
     * @since 1.62
     */
    @DataBoundSetter
    public void setRemovedConfigFilesAction(RemovedConfigFilesAction removedConfigFilesAction) {
        this.removedConfigFilesAction = removedConfigFilesAction;
    }

    public LookupStrategy getLookupStrategy() {
        return lookupStrategy == null ? LookupStrategy.JENKINS_ROOT : lookupStrategy;
    }

    @DataBoundSetter
    public void setLookupStrategy(LookupStrategy lookupStrategy) {
        this.lookupStrategy = lookupStrategy;
    }

    public String getAdditionalClasspath() {
        return additionalClasspath;
    }

    @DataBoundSetter
    public void setAdditionalClasspath(String additionalClasspath) {
        this.additionalClasspath = fixEmptyAndTrim(additionalClasspath);
    }

    public Map<String, Object> getAdditionalParameters() {
        return additionalParameters;
    }

    @DataBoundSetter
    public void setAdditionalParameters(Map<String, Object> additionalParameters) {
        this.additionalParameters = additionalParameters;
    }

    void configure(Item ancestor) {
        if (!sandbox && isUsingScriptText() && scriptText != null && ((DescriptorImpl) getDescriptor()).isSecurityEnabled()) {
            ScriptApproval.get().configuring(scriptText, GroovyLanguage.get(), ApprovalContext.create().withCurrentUser().withItem(ancestor));
        }
    }

    private Object readResolve() {
        configure(null);
        return this;
    }

    /**
     * Runs every job DSL script provided in the plugin configuration, which results in new /
     * updated Jenkins jobs. The created / updated jobs are reported in the build result.
     */
    @Override
    public void perform(@NonNull Run<?, ?> run, @NonNull FilePath workspace, @NonNull Launcher launcher,
                        @NonNull TaskListener listener) throws InterruptedException, IOException {
        try {
            EnvVars env = run.getEnvironment(listener);
            if (run instanceof AbstractBuild) {
                env.putAll(((AbstractBuild<?, ?>) run).getBuildVariables());
            }

            Map<String, Object> binding = new HashMap<>(env);
            if (additionalParameters != null) {
                binding.putAll(additionalParameters);
            }

            JenkinsJobManagement jenkinsJobManagement = new JenkinsJobManagement(
                    listener.getLogger(), binding, run, workspace, getLookupStrategy()
            );
            jenkinsJobManagement.setFailOnMissingPlugin(failOnMissingPlugin);
            jenkinsJobManagement.setFailOnSeedCollision(failOnSeedCollision);
            jenkinsJobManagement.setUnstableOnDeprecation(unstableOnDeprecation);
            JobManagement jobManagement = new InterruptibleJobManagement(jenkinsJobManagement);

            if (rebootRequired) {
                listener.getLogger().println(Messages.ExecuteDslScripts_RestartRequired());
            }

            try (ScriptRequestGenerator generator = new ScriptRequestGenerator(workspace, env)) {
                Set<ScriptRequest> scriptRequests = generator.getScriptRequests(
                        getTargets(), isUsingScriptText(), getScriptText(), ignoreExisting, isIgnoreMissingFiles(), additionalClasspath
                );

                JenkinsDslScriptLoader dslScriptLoader;
                if (((DescriptorImpl) getDescriptor()).isSecurityEnabled()) {
                    if (sandbox) {
                        dslScriptLoader = new SandboxDslScriptLoader(jobManagement, run.getParent());
                    } else {
                        dslScriptLoader = new ScriptApprovalDslScriptLoader(jobManagement, run.getParent());
                    }
                } else {
                    dslScriptLoader = new JenkinsDslScriptLoader(jobManagement);
                }

                GeneratedItems generatedItems = dslScriptLoader.runScripts(scriptRequests);
                Set<GeneratedJob> freshJobs = generatedItems.getJobs();
                Set<GeneratedView> freshViews = generatedItems.getViews();
                Set<GeneratedConfigFile> freshConfigFiles = generatedItems.getConfigFiles();
                Set<GeneratedUserContent> freshUserContents = generatedItems.getUserContents();

                // Save onto Builder, which belongs to a Project.
                addJobAction(run, new GeneratedJobsBuildAction(freshJobs, getLookupStrategy()));
                addJobAction(run, new GeneratedViewsBuildAction(freshViews, getLookupStrategy()));
                addJobAction(run, new GeneratedConfigFilesBuildAction(freshConfigFiles));
                addJobAction(run, new GeneratedUserContentsBuildAction(freshUserContents));

                updateTemplates(run.getParent(), listener, new HashSet<>(run.getAction(GeneratedJobsBuildAction.class).getModifiedObjects()));
                updateGeneratedJobs(run.getParent(), listener, new HashSet<>(run.getAction(GeneratedJobsBuildAction.class).getModifiedObjects()));
                updateGeneratedViews(run.getParent(), listener, new HashSet<>(run.getAction(GeneratedViewsBuildAction.class).getModifiedObjects()));
                updateGeneratedConfigFiles(run.getParent(), listener, new HashSet<>(run.getAction(GeneratedConfigFilesBuildAction.class).getModifiedObjects()));
                updateGeneratedUserContents(run.getParent(), listener, new HashSet<>(run.getAction(GeneratedUserContentsBuildAction.class).getModifiedObjects()));
            }
        } catch (RuntimeException e) {
            if (!(e instanceof DslException) && !(e instanceof AccessDeniedException)) {
                e.printStackTrace(listener.getLogger());
            }
            LOGGER.log(Level.FINE, String.format("Exception while processing DSL scripts: %s", e.getMessage()), e);
            throw new AbortException(e.getMessage());
        }
    }

    private void addJobAction(Run run, GeneratedObjectsRunAction action){
        GeneratedObjectsRunAction generatedJobsBuildAction = run.getAction(action.getClass());
        if (generatedJobsBuildAction == null) {
            run.addAction(action);
        }
        else {
            generatedJobsBuildAction.addModifiedObjects(action.getModifiedObjects());
        }
    }

    /**
     * Uses generatedJobs as existing data, so call before updating generatedJobs.
     */
    private Set<String> updateTemplates(Job seedJob, TaskListener listener,
                                        Set<GeneratedJob> freshJobs) throws IOException {
        Set<String> freshTemplates = getTemplates(freshJobs);
        Set<String> existingTemplates = getTemplates(extractGeneratedObjects(seedJob, GeneratedJobsAction.class));
        Set<String> newTemplates = Sets.difference(freshTemplates, existingTemplates);
        Set<String> removedTemplates = Sets.difference(existingTemplates, freshTemplates);

        logItems(listener, "Existing templates", existingTemplates);
        logItems(listener, "New templates", newTemplates);
        logItems(listener, "Unreferenced templates", removedTemplates);

        // Collect information about the templates we loaded
        final String seedJobName = seedJob.getName();
        DescriptorImpl descriptor = Jenkins.get().getDescriptorByType(DescriptorImpl.class);
        boolean descriptorMutated = false;

        // Clean up
        for (String templateName : removedTemplates) {
            Collection<SeedReference> seedJobReferences = descriptor.getTemplateJobMap().get(templateName);
            Collection<SeedReference> matching = Collections2.filter(seedJobReferences, new SeedNamePredicate(seedJobName));
            if (!matching.isEmpty()) {
                seedJobReferences.removeAll(Sets.newHashSet(matching));
                descriptorMutated = true;
            }
        }

        // Ensure we have a reference
        for (String templateName : freshTemplates) {
            Collection<SeedReference> seedJobReferences = descriptor.getTemplateJobMap().get(templateName);
            Collection<SeedReference> matching = Collections2.filter(seedJobReferences, new SeedNamePredicate(seedJobName));

            AbstractItem templateProject = getLookupStrategy().getItem(seedJob, templateName, AbstractItem.class);
            final String digest = Util.getDigestOf(new FileInputStream(templateProject.getConfigFile().getFile()));

            if (matching.size() == 1) {
                // Just update digest
                SeedReference ref = Iterables.get(matching, 0);
                if (digest.equals(ref.getDigest())) {
                    ref.setDigest(digest);
                    descriptorMutated = true;
                }
            } else {
                if (matching.size() > 1) {
                    // Not sure how there could be more one, throw it all away and start over
                    seedJobReferences.removeAll(Sets.newHashSet(matching));
                }
                seedJobReferences.add(new SeedReference(templateName, seedJobName, digest));
                descriptorMutated = true;
            }
        }

        if (descriptorMutated) {
            descriptor.save();
        }
        return freshTemplates;
    }

    private void updateGeneratedJobs(final Job seedJob, TaskListener listener,
                                     Set<GeneratedJob> freshJobs) throws IOException, InterruptedException {
        // Update Project
        Set<GeneratedJob> generatedJobs = extractGeneratedObjects(seedJob, GeneratedJobsAction.class);
        Set<GeneratedJob> added = Sets.difference(freshJobs, generatedJobs);
        Set<GeneratedJob> existing = Sets.intersection(generatedJobs, freshJobs);
        Set<GeneratedJob> unreferenced = Sets.difference(generatedJobs, freshJobs);
        Set<GeneratedJob> removed = new HashSet<>();
        Set<GeneratedJob> disabled = new HashSet<>();

        logItems(listener, "Added items", added);
        logItems(listener, "Existing items", existing);
        logItems(listener, "Unreferenced items", unreferenced);

        // Update unreferenced jobs
        for (GeneratedJob unreferencedJob : unreferenced) {
            Item removedItem = getLookupStrategy().getItem(seedJob, unreferencedJob.getJobName(), Item.class);
            if (removedItem != null && removedJobAction != RemovedJobAction.IGNORE) {
                if (removedJobAction == RemovedJobAction.DELETE) {
                    removedItem.delete();
                    removed.add(unreferencedJob);
                } else {
                    if (removedItem instanceof ParameterizedJob) {
                        ParameterizedJob project = (ParameterizedJob) removedItem;
                        project.checkPermission(Item.CONFIGURE);
                        project.makeDisabled(true);
                        disabled.add(unreferencedJob);
                    }
                }
            }
        }

        // print what happened with unreferenced jobs
        logItems(listener, "Disabled items", disabled);
        logItems(listener, "Removed items", removed);

        updateGeneratedJobMap(seedJob, Sets.union(added, existing), unreferenced);
    }

    private void updateGeneratedJobMap(Job seedJob, Set<GeneratedJob> createdOrUpdatedJobs,
                                       Set<GeneratedJob> removedJobs) throws IOException {
        DescriptorImpl descriptor = Jenkins.get().getDescriptorByType(DescriptorImpl.class);
        boolean descriptorMutated = false;
        Map<String, SeedReference> generatedJobMap = descriptor.getGeneratedJobMap();

        for (GeneratedJob generatedJob : createdOrUpdatedJobs) {
            Item item = getLookupStrategy().getItem(seedJob, generatedJob.getJobName(), Item.class);
            if (item != null) {
                SeedReference newSeedReference = new SeedReference(seedJob.getFullName());
                if (generatedJob.getTemplateName() != null) {
                    Item template = getLookupStrategy().getItem(seedJob, generatedJob.getTemplateName(), Item.class);
                    if (template != null) {
                        newSeedReference.setTemplateJobName(template.getFullName());
                    }
                }
                newSeedReference.setDigest(Util.getDigestOf(Items.getConfigFile(item).getFile()));

                SeedReference oldSeedReference = generatedJobMap.get(item.getFullName());
                if (!newSeedReference.equals(oldSeedReference)) {
                    generatedJobMap.put(item.getFullName(), newSeedReference);
                    descriptorMutated = true;
                }
            }
        }

        for (GeneratedJob removedJob : removedJobs) {
            Item removedItem = getLookupStrategy().getItem(seedJob, removedJob.getJobName(), Item.class);
            if (removedItem != null) {
                generatedJobMap.remove(removedItem.getFullName());
                descriptorMutated = true;
            }
        }

        if (descriptorMutated) {
            descriptor.save();
        }
    }

    private void updateGeneratedViews(Job seedJob, TaskListener listener,
                                      Set<GeneratedView> freshViews) throws IOException {
        Set<GeneratedView> generatedViews = extractGeneratedObjects(seedJob, GeneratedViewsAction.class);
        Set<GeneratedView> added = Sets.difference(freshViews, generatedViews);
        Set<GeneratedView> existing = Sets.intersection(generatedViews, freshViews);
        Set<GeneratedView> unreferenced = Sets.difference(generatedViews, freshViews);
        Set<GeneratedView> removed = new HashSet<>();

        logItems(listener, "Added views", added);
        logItems(listener, "Existing views", existing);
        logItems(listener, "Unreferenced views", unreferenced);

        // Delete views
        if (removedViewAction == RemovedViewAction.DELETE) {
            for (GeneratedView unreferencedView : unreferenced) {
                String viewName = unreferencedView.getName();
                ItemGroup parent = getLookupStrategy().getParent(seedJob, viewName);
                if (parent instanceof ViewGroup) {
                    View view = ((ViewGroup) parent).getView(FilenameUtils.getName(viewName));
                    if (view != null) {
                        view.checkPermission(View.DELETE);
                        ((ViewGroup) parent).deleteView(view);
                        removed.add(unreferencedView);
                    }
                } else if (parent == null) {
                    LOGGER.log(Level.FINE, "Parent ViewGroup seems to have been already deleted");
                } else {
                    LOGGER.log(Level.WARNING, format("Could not delete view within %s", parent.getClass()));
                }
            }
        }

        logItems(listener, "Removed views", removed);
    }

    private void updateGeneratedConfigFiles(Job seedJob, TaskListener listener,
                                            Set<GeneratedConfigFile> freshConfigFiles) {
        Set<GeneratedConfigFile> generatedConfigFiles = extractGeneratedObjects(seedJob, GeneratedConfigFilesAction.class);
        Set<GeneratedConfigFile> added = Sets.difference(freshConfigFiles, generatedConfigFiles);
        Set<GeneratedConfigFile> existing = Sets.intersection(generatedConfigFiles, freshConfigFiles);
        Set<GeneratedConfigFile> unreferenced = Sets.difference(generatedConfigFiles, freshConfigFiles);

        logItems(listener, "Added config files", added);
        logItems(listener, "Existing config files", existing);
        logItems(listener, "Unreferenced config files", unreferenced);

        if (removedConfigFilesAction == RemovedConfigFilesAction.DELETE && Jenkins.get().getPluginManager().getPlugin("config-file-provider") != null) {
            GlobalConfigFiles globalConfigFiles = GlobalConfigFiles.get();
            for (GeneratedConfigFile unreferencedConfigFile : unreferenced) {
                Jenkins.get().checkPermission(Jenkins.ADMINISTER);
                globalConfigFiles.remove(unreferencedConfigFile.getId());
            }
        }
    }

    private void updateGeneratedUserContents(Job seedJob, TaskListener listener,
                                             Set<GeneratedUserContent> freshUserContents) {
        Set<GeneratedUserContent> generatedUserContents = extractGeneratedObjects(seedJob, GeneratedUserContentsAction.class);
        Set<GeneratedUserContent> added = Sets.difference(freshUserContents, generatedUserContents);
        Set<GeneratedUserContent> existing = Sets.intersection(generatedUserContents, freshUserContents);
        Set<GeneratedUserContent> unreferenced = Sets.difference(generatedUserContents, freshUserContents);

        logItems(listener, "Adding user content", added);
        logItems(listener, "Existing user content", existing);
        logItems(listener, "Unreferenced user content", unreferenced);
    }

    private static void logItems(TaskListener listener, String message, Collection<?> collection) {
        if (!collection.isEmpty()) {
            listener.getLogger().println(message + ":");
            for (Object item : collection) {
                listener.getLogger().println("    " + item.toString());
            }
        }
    }

    private static Set<String> getTemplates(Collection<GeneratedJob> jobs) {
        Collection<String> templateNames = Collections2.transform(jobs, GeneratedJob::getTemplateName);
        return new LinkedHashSet<>(Collections2.filter(templateNames, Predicates.notNull()));
    }

    private static class SeedNamePredicate implements Predicate<SeedReference> {
        private final String seedJobName;

        SeedNamePredicate(String seedJobName) {
            this.seedJobName = seedJobName;
        }

        @Override
        public boolean apply(SeedReference input) {
            return seedJobName.equals(input.getSeedJobName());
        }
    }
}
