package org.jenkinsci.plugins.redpen.service;

import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.redpen.auth.JWTUtility;
import org.jenkinsci.plugins.redpen.constant.Constants;
import org.jenkinsci.plugins.redpen.models.AttachmentModel;
import org.jenkinsci.plugins.redpen.models.ParameterModel;
import org.jenkinsci.plugins.redpen.models.TestFrameWork;
import org.jenkinsci.plugins.redpen.util.FileReaderUtils;
import org.jenkinsci.plugins.redpen.util.FileUtils;
import org.jenkinsci.plugins.redpen.util.PathUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

public class RedpenJenkinsCore {
    private static final Logger LOGGER = Logger.getLogger(RedpenJenkinsCore.class.getName());

    /**
     * Attach logs and generated artifacts of job, and Add status of job in Jira issue comment.
     *
     * @param parameter : ParameterModel
     */
    public void doPerform(ParameterModel parameter) {
        String issueKey = parameter.getIssueKey();
        String jwtToken = getJWT(parameter);

        String jobStarted = String.format("Redpen Plugin Detect Failure on build %s followed by jira ticket %s", parameter.getBuildNumber(), issueKey);
        LOGGER.info(jobStarted);

        if (!StringUtils.isBlank(jwtToken)) {
            try {
                AttachmentModel uploadedFileNames = addAttachments(parameter, jwtToken);
                addComment(parameter, jwtToken, uploadedFileNames);

                String jobCompleted = String.format("Redpen Plugin Detect Failure on build %s followed by jira ticket %s", parameter.getBuildNumber(), issueKey);
                LOGGER.info(jobCompleted);

            } catch (IOException e) {
                LOGGER.warning(String.format("Something went wrong during Redpen Plugin Task %s", e.getMessage()));
            }
        }
    }

    /**
     * Attach build log file and added Configured files in Jira issue.
     *
     * @param parameter : ParameterModel
     * @param jwtToken  : JWT Token
     * @return List of Attached file name
     */
    private AttachmentModel addAttachments(ParameterModel parameter, String jwtToken) throws IOException {
        RedpenService redpenService = RedpenService.getRedpenInstance();
        StringBuilder comment = new StringBuilder();
        AttachmentModel attachmentModel = new AttachmentModel();

        List<String> uploadedFileNames = new ArrayList<>();

        String issueKey = parameter.getIssueKey();
        String projectName = parameter.getProjectName();
        Instant buildTriggerTime = parameter.getBuildTriggerTime();
        String workspaceBasePath = getWorkspaceDirPath(projectName);

        File buildLogFile = new File(parameter.getLogAbsolutePath());
        String fileName = FileUtils.getNewFile(buildLogFile.getName(), parameter.getResult(), String.valueOf(parameter.getBuildTriggerTime().getEpochSecond()));
        boolean attachmentUploaded = redpenService.addAttachment(issueKey, jwtToken, buildLogFile, fileName);

        if (attachmentUploaded) {
            uploadedFileNames.add(fileName);
            LOGGER.log(Level.INFO, "File {0} is attached", fileName);
        }

        AttachmentModel unitTestResults = uploadFilesFromSelectedTestFrameWork(issueKey, jwtToken, parameter.getUnitTestFrameWork(), parameter.getUnitTestFrameWorkPath(), workspaceBasePath, buildTriggerTime, Constants.UNIT_TEST);
        uploadedFileNames.addAll(unitTestResults.getAttachments());
        comment.append(unitTestResults.getComment());

        AttachmentModel e2eTestUploadedFiles = uploadFilesFromSelectedTestFrameWork(issueKey, jwtToken, parameter.getE2eTestFrameWork(), parameter.getE2eTestFrameWorkPath(), workspaceBasePath, buildTriggerTime, Constants.E2E_TEST);
        uploadedFileNames.addAll(e2eTestUploadedFiles.getAttachments());
        comment.append(e2eTestUploadedFiles.getComment());

        AttachmentModel coverageUploadedFiles = uploadFilesFromSelectedTestFrameWork(issueKey, jwtToken, parameter.getCoverageFrameWork(), parameter.getCoverageFrameWorkPath(), workspaceBasePath, buildTriggerTime, Constants.COVERAGE_TEST);
        uploadedFileNames.addAll(coverageUploadedFiles.getAttachments());
        comment.append(coverageUploadedFiles.getComment());

        String[] logDir = parameter.getLogFileLocation().trim().split(",");

        for (String s : logDir) {
            String trimPath = s.trim();
            File file = new File(workspaceBasePath, trimPath);
            if (!StringUtils.isBlank(trimPath) && file.getCanonicalFile().toPath().startsWith(workspaceBasePath)) {
                String logPath = file.getAbsolutePath();
                AttachmentModel reportFiles = attachLogFiles(buildTriggerTime, workspaceBasePath, logPath, issueKey, jwtToken, "", "Other Files", true);
                uploadedFileNames.addAll(reportFiles.getAttachments());
                comment.append(reportFiles.getComment());
            }
        }

        uploadedFileNames.forEach(file -> LOGGER.info(String.format("File : %s is attached", file)));
        attachmentModel.setAttachments(uploadedFileNames);
        attachmentModel.setComment(comment.toString());
        return attachmentModel;
    }

    /**
     * Add Comment on Jira issue
     *
     * @param parameter         : ParameterModel
     * @param jwtToken          : JWT Token
     * @param uploadedFileNames : Attached fileNames
     */
    private void addComment(ParameterModel parameter, String jwtToken, AttachmentModel uploadedFileNames) {
        RedpenService redpenService = RedpenService.getRedpenInstance();
        String jobURL = getJobUrl(parameter);
        String comment = getGeneratedComment(parameter, jobURL);

        comment += uploadedFileNames.getComment();

        redpenService.addComment(parameter.getIssueKey(), jwtToken, comment, uploadedFileNames.getAttachments());
    }


    private String getFilePath(String frameWork, String frameWorkPath) {
        Optional<TestFrameWork> availableInList = RedpenJenkinsCore.isAvailableInList(frameWork);

        if (availableInList.isPresent() && availableInList.get().getValue() != null) {
            return !StringUtils.isBlank(frameWorkPath) ? frameWorkPath : availableInList.get().getPath();
        }

        return null;
    }

    /**
     * Create Current Job URL
     *
     * @param parameter : ParameterModel
     * @return jobURL : "http:<jenkins-server-url>/job/<project-name>/<job-number>"
     */
    private String getJobUrl(ParameterModel parameter) {
        return String.format("%s%s/%s/%s", parameter.getRootURL(), "job", parameter.getProjectName(), parameter.getBuildNumber());
    }

    /**
     * Generate Comment for Jira issue
     *
     * @param parameter : ParameterModel
     * @param jobURL    : Jenkins job URL
     * @return generated Comment for Jira RTE.
     */
    private String getGeneratedComment(ParameterModel parameter, String jobURL) {
        String displayName = parameter.getBuildNumber();
        String result = parameter.getResult();
        Instant buildTriggerTime = parameter.getBuildTriggerTime();

        return String.format(" [Build %s|%s] Result {color:red}*%s*{color} Time %s", displayName, jobURL, result, buildTriggerTime);
    }

    /**
     * Get JWT
     *
     * @param parameter : ParameterModel
     * @return JWT Token to make API call
     */
    private String getJWT(ParameterModel parameter) {
        return JWTUtility.getJWTToken(parameter.getSecret(), parameter.getUserEmail(), parameter.getUserPassword());
    }

    /**
     * Get Work Directory of Jenkins
     *
     * @return work directory path
     */
    public static String getCurrentDirPath() {
        String currentDir = System.getenv().get(Constants.JENKINS_HOME);

        if (currentDir == null) {
            currentDir = String.format("%s%s", System.getProperty("user.dir"), "/work");
        }

        return PathUtils.getPath(currentDir);
    }

    public static String getWorkspaceDirPath(String projectName) {
        return PathUtils.getPath(String.format("%s/workspace/%s", getCurrentDirPath(), projectName));
    }

    /**
     * Upload generated artifacts of job in Jira issue as per given configuration in Jenkins Job Configuration.
     *
     * @param issueKey         : Jira issue key [TEST-1, TP-2]
     * @param jwtToken         : JWT Token
     * @param frameWork        : TestFrameWork
     * @param frameWorkPath    : Updated File Path for selected test framework
     * @param basePath         : workspace path
     * @param buildTriggerTime : build trigger time
     * @return List of attached file name
     */
    private AttachmentModel uploadFilesFromSelectedTestFrameWork(String issueKey, String jwtToken, String frameWork, String frameWorkPath, String basePath, Instant buildTriggerTime, String frameWorkName) throws IOException {
        List<String> allFiles = new ArrayList<>();
        String fileRelativePath = getFilePath(frameWork, frameWorkPath);
        AttachmentModel attachmentModel = new AttachmentModel();
        StringBuilder comment = new StringBuilder();

        if (fileRelativePath != null) {
            // Check For Path traversal vulnerability
            File file = new File(basePath, fileRelativePath);
            if (file.getCanonicalPath().startsWith(basePath)) {
                AttachmentModel reportFiles = attachLogFiles(buildTriggerTime, basePath, PathUtils.getPath(file.getAbsolutePath()), issueKey, jwtToken, frameWork, frameWorkName,false);
                allFiles.addAll(reportFiles.getAttachments());
                comment.append(reportFiles.getComment());
            }
        }

        attachmentModel.setAttachments(allFiles);
        attachmentModel.setComment(comment.toString());
        return attachmentModel;
    }

    /**
     * Get Selected Test framework's default log path
     *
     * @param key : Test framework's key
     * @return : log file path as per selected test framework
     */
    public static Optional<TestFrameWork> isAvailableInList(String key) {
        return Constants.TEST_FRAME_WORKS.stream().filter(a -> a.getValue().equals(key)).findFirst();
    }

    /**
     * Attach log file and generated artifacts if that file generated after build start.
     *
     * @param buildStartTime : Build start time
     * @param filePath       : File path [it can be folder path or direct absolute filepath]
     * @param issueKey       : Jira issue key [TEST-1, TP-2]
     * @param jwtToken       : JWT Token
     * @param isMandatory    : If this parameter is true then this method will upload attachment even if file is not generated after build start.
     * @return List of attached file name
     */
    private AttachmentModel attachLogFiles(Instant buildStartTime, String basePath, String filePath, String issueKey, String jwtToken, String frameWork, String frameWorkName, Boolean isMandatory) throws IOException {
        RedpenService redpenService = RedpenService.getRedpenInstance();
        List<String> fileNames = new ArrayList<>();
        AttachmentModel attachmentModel = new AttachmentModel();
        StringBuilder comment = new StringBuilder();

        File logs = new File(filePath);

        List<File> files = FileUtils.listFilesForFolder(logs);

        for (File file : files) {
            String absolutePath = file.getAbsolutePath();
            Path filePathAbsolute = Paths.get(absolutePath);
            BasicFileAttributes attr = Files.readAttributes(filePathAbsolute, BasicFileAttributes.class);
            FileTime fileTime = attr.creationTime();
            FileTime from = FileTime.from(buildStartTime);

            int after = from.compareTo(fileTime);

            if (after < 0 || Boolean.TRUE.equals(isMandatory)) {
                String fileName = String.format("%s_%s", buildStartTime.getEpochSecond(), file.getName());
                boolean attachmentUploaded = redpenService.addAttachment(issueKey, jwtToken, file, fileName);
                if (attachmentUploaded) {
                    fileNames.add(fileName);
                    LOGGER.log(Level.INFO, "File {0} is attached", fileName);
                    String result = FileReaderUtils.readFile(filePathAbsolute.toString(), basePath, frameWork, frameWorkName);
                    comment.append(result);
                }
            }
        }
        attachmentModel.setAttachments(fileNames);
        attachmentModel.setComment(comment.toString());
        return attachmentModel;
    }
}
