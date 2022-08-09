package org.jenkinsci.plugins.redpen;

import com.cloudbees.plugins.credentials.CredentialsMatcher;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.common.AbstractIdCredentialsListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import jenkins.model.ParameterizedJobMixIn;
import lombok.Data;
import lombok.EqualsAndHashCode;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.jenkinsci.plugins.redpen.constant.Constants;
import org.jenkinsci.plugins.redpen.models.TestFrameWork;
import org.jenkinsci.plugins.redpen.secrets.SecretRetriever;
import org.jenkinsci.plugins.redpen.service.RedpenJenkinsCore;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

@EqualsAndHashCode(callSuper = true)
@Data
public class RedpenJobProperty extends JobProperty<Job<?, ?>> {
    private static final Logger LOGGER = Logger.getLogger(RedpenJobProperty.class.getName());

    private String credentialId;
    private String userEmail;
    private Secret userPassword;
    private String logFileLocation;
    private String unitTestFrameWork;
    private String e2eTestFrameWork;
    private String coverageFrameWork;
    private String unitTestFrameWorkPath;
    private String e2eTestFrameWorkPath;
    private String coverageFrameWorkPath;

    @DataBoundConstructor
    public RedpenJobProperty(String credentialId, String logFileLocation, String unitTestFrameWork,
                             String e2eTestFrameWork, String coverageFrameWork, String unitTestFrameWorkPath,
                             String e2eTestFrameWorkPath, String coverageFrameWorkPath, String userEmail, Secret userPassword) {
        this.credentialId = credentialId;
        this.logFileLocation = logFileLocation;
        this.unitTestFrameWork = unitTestFrameWork;
        this.e2eTestFrameWork = e2eTestFrameWork;
        this.coverageFrameWork = coverageFrameWork;
        this.unitTestFrameWorkPath = unitTestFrameWorkPath;
        this.e2eTestFrameWorkPath = e2eTestFrameWorkPath;
        this.coverageFrameWorkPath = coverageFrameWorkPath;
        this.userEmail = userEmail;
        this.userPassword = userPassword;
    }

    @Extension
    public static final class DescriptorImpl extends JobPropertyDescriptor {

        @Override
        public boolean isApplicable(Class<? extends Job> jobType) {
            return ParameterizedJobMixIn.ParameterizedJob.class.isAssignableFrom(jobType);
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "Redpen General";
        }

        @Override
        public JobProperty<?> newInstance(StaplerRequest req, JSONObject formData) {

            if (req == null) {
                return null;
            }

            RedpenJobProperty redpenPluginInstance = req.bindJSON(
                    RedpenJobProperty.class,
                    net.sf.json.JSONObject.fromObject(formData.getJSONObject(Constants.REDPEN_PLUGIN)));

            if (redpenPluginInstance == null) {
                LOGGER.fine("Couldn't bind JSON");
                return null;
            }

            if (redpenPluginInstance.credentialId == null) {
                LOGGER.fine("Credential not found, nullifying Redpen Plugin");
                return null;
            }

            return redpenPluginInstance;
        }

        public ListBoxModel doFillUnitTestFrameWorkItems() {

            ListBoxModel list = new ListBoxModel();
            list.add(Constants.NONE_DISPLAY_NAME, "");
            list.add(Constants.JUNIT_DISPLAY_NAME, Constants.JUNIT);
            list.add(Constants.NUNIT_DISPLAY_NAME, Constants.NUNIT);
            list.add(Constants.JEST_DISPLAY_NAME, Constants.JEST);

            return list;
        }

        public ListBoxModel doFillCoverageFrameWorkItems() {

            ListBoxModel list = new ListBoxModel();
            list.add(Constants.NONE_DISPLAY_NAME, "");
            list.add(Constants.JACOCO_DISPLAY_NAME, Constants.JACOCO);

            return list;
        }

        public ListBoxModel doFillE2eTestFrameWorkItems() {

            ListBoxModel list = new ListBoxModel();
            list.add(Constants.NONE_DISPLAY_NAME, "");
            list.add(Constants.WEB_DRIVER_IO_DISPLAY_NAME, Constants.WEB_DRIVER_IO);
            list.add(Constants.SELENIUM_DISPLAY_NAME, Constants.SELENIUM);

            return list;
        }

        public ListBoxModel doFillCredentialIdItems(
                @QueryParameter String credentialsId, @AncestorInPath Item item) {
            ListBoxModel listBoxModel = new ListBoxModel();
            List<CredentialsMatcher> matchers = new ArrayList<>();

            if (item == null) { // no context
                return listBoxModel;
            }
            item.checkPermission(Item.CONFIGURE);

            if (!StringUtils.isEmpty(credentialsId)) {
                matchers.add(0, CredentialsMatchers.withId(credentialsId));
            }

            matchers.add(CredentialsMatchers.instanceOf(StandardUsernamePasswordCredentials.class));
            matchers.add(CredentialsMatchers.instanceOf(StringCredentials.class));

            SecretRetriever secretRetriever = new SecretRetriever();

            List<StringCredentials> credentials = secretRetriever.getCredential();

            AbstractIdCredentialsListBoxModel<StandardListBoxModel, StandardCredentials> options = new StandardListBoxModel()
                    .withMatching(
                            CredentialsMatchers.anyOf(
                                    matchers.toArray(new CredentialsMatcher[0])),
                            credentials);

            listBoxModel.add(Constants.NONE_DISPLAY_NAME, "");
            listBoxModel.addAll(options);

            return listBoxModel;
        }

        public FormValidation doCheckE2eTestFrameWork(@QueryParameter String value, @AncestorInPath Item item) {
            return doCheckValidator(value, item);
        }

        public FormValidation doCheckCoverageFrameWork(@QueryParameter String value, @AncestorInPath Item item) {
            return doCheckValidator(value, item);
        }

        public FormValidation doCheckUnitTestFrameWork(@QueryParameter String value, @AncestorInPath Item item) {
            return doCheckValidator(value, item);
        }

        public FormValidation doCheckUnitTestFrameWorkPath(@QueryParameter String value){
                return doCheckTestFrameWorkPath(value);
        }

        public FormValidation doCheckE2eTestFrameWorkPath(@QueryParameter String value){
            return doCheckTestFrameWorkPath(value);
        }

        public FormValidation doCheckCoverageFrameWorkPath(@QueryParameter String value){
            return doCheckTestFrameWorkPath(value);
        }

        private FormValidation doCheckTestFrameWorkPath(String value) {
            String basePath = RedpenJenkinsCore.getCurrentDirPath();
            if(StringUtils.isBlank(value))  {
                return FormValidation.ok();
            }
            try {
                File file = new File(basePath, value);
                if (file.getCanonicalFile().toPath().startsWith(basePath)) {
                    return FormValidation.ok();
                }
            } catch (IOException e) {
                return FormValidation.errorWithMarkup("FilePath is invalid");
            }
            return FormValidation.errorWithMarkup("FilePath not allowed");
        }

        private FormValidation doCheckValidator(String value, @AncestorInPath Item item) {

            if (item == null) { // no context
                return FormValidation.ok();
            }
            item.checkPermission(Item.CONFIGURE);

            if (StringUtils.isBlank(value)) {
                return FormValidation.ok();
            }

            Optional<TestFrameWork> availableInList = RedpenJenkinsCore.isAvailableInList(value);

            if (availableInList.isPresent()) {
                TestFrameWork testFrameWork = availableInList.get();
                return FormValidation.ok(String.format("Default Path : '%s' ", testFrameWork.getPath()));
            }

            return FormValidation.ok();
        }

    }
}
