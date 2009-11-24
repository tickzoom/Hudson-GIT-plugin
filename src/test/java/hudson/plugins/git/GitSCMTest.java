package hudson.plugins.git;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.model.Cause;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Project;
import hudson.model.Result;
import hudson.model.TaskListener;
import hudson.model.User;
import hudson.tasks.BatchFile;
import hudson.util.StreamTaskListener;

import java.io.File;
import java.util.List;
import java.util.Set;

import org.jvnet.hudson.test.CaptureEnvironmentBuilder;
import org.jvnet.hudson.test.HudsonTestCase;
import org.spearce.jgit.lib.PersonIdent;


/**
 * Tests for {@link GitSCM}.
 * @author ishaaq
 */
public class GitSCMTest extends HudsonTestCase {

    private TaskListener listener;
    private EnvVars envVars;

    private File workDir;
    private GitAPI git;
    private FilePath workspace;
    
    private File bareDir;
    private GitAPI bareGit;
    private FilePath bareWorkspace;

    private File remoteDir;
    private GitAPI remoteGit;
    private FilePath remoteWorkspace;

    private final PersonIdent johnDoe = new PersonIdent("John Doe", "john@doe.com");
    private final PersonIdent janeDoe = new PersonIdent("Jane Doe", "jane@doe.com");

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        workDir = createTmpDir();
        listener = new StreamTaskListener();
        envVars = new EnvVars();
        setAuthor(johnDoe);
        setCommitter(johnDoe);
        workspace = new FilePath(workDir);
// Let the plugin create the hudson workspace otherwise, we can't find
// regression errors when it's ability to create one from scratch breaks.
// And start from scratch was broken for on the fork with GitCommitPublisher.
        
        // Remote repository to test polling.
        bareDir = createTmpDir();
        bareWorkspace = new FilePath(bareDir);
        bareWorkspace.delete();
        bareGit = new GitAPI("git", bareWorkspace, listener, envVars);
        bareGit.initBare();

        // Remote repository to test polling.
        remoteDir = createTmpDir();
        remoteWorkspace = new FilePath(remoteDir);
        remoteWorkspace.delete();
        remoteGit = new GitAPI("git", remoteWorkspace, listener, envVars);
        remoteGit.init();
    }

    private void setAuthor(final PersonIdent author) {
        envVars.put("GIT_AUTHOR_NAME", author.getName());
        envVars.put("GIT_AUTHOR_EMAIL", author.getEmailAddress());
    }

    private void setCommitter(final PersonIdent committer) {
        envVars.put("GIT_COMMITTER_NAME", committer.getName());
        envVars.put("GIT_COMMITTER_EMAIL", committer.getEmailAddress());
    }

    /**
     * Basic test - create a GitSCM based project, check it out and build for the first time.
     * Next test that polling works correctly, make another commit, check that polling finds it,
     * then build it and finally test the build culprits as well as the contents of the workspace.
     * @throws Exception if an exception gets thrown.
     */
    public void testBasic() throws Exception {
        FreeStyleProject project = setupSimpleProject("master");

        // create initial commit and then run the build against it:
        final String commitFile1 = "commitFile1";
        commit(commitFile1, johnDoe, "Commit number 1");
        pushToBare();
        build(project, Result.SUCCESS, commitFile1);
        TaskListener sysOutListener = new StreamTaskListener(System.out);
        assertFalse("scm polling should not detect any more changes after build", project.pollSCMChanges(sysOutListener));

        final String commitFile2 = "commitFile2";
        commit(commitFile2, janeDoe, "Commit number 2");
        pushToBare();
        assertTrue("scm polling did not detect commit2 change", project.pollSCMChanges(listener));
        //... and build it...
        final FreeStyleBuild build2 = build(project, Result.SUCCESS, commitFile2);
        final Set<User> culprits = build2.getCulprits();
        assertEquals("The build should have only one culprit", 1, culprits.size());
        assertEquals("", janeDoe.getName(), culprits.iterator().next().getFullName());
        assertTrue(project.getWorkspace().child(commitFile2).exists());
        assertBuildStatusSuccess(build2);
        assertFalse("scm polling should not detect any more changes after build", project.pollSCMChanges(listener));
    }
    
    private boolean pollSCMChanges(FreeStyleProject project) {
        TaskListener sysOutListener = new StreamTaskListener(System.out);
        return project.pollSCMChanges(sysOutListener);
    }

    /**
     * Method name is self-explanatory.
     */
    public void testCommitDuringBuildCanPublishAtLeastTwice() throws Exception {
        final String commitFile1 = "commitFile1";
        // Empty string means build any branches with changes.
        FreeStyleProject project = setupProjectThatCommits("","",commitFile1);
        
        // create initial commit and then run the build against it:
        commit(commitFile1, remoteGit, johnDoe, "Commit number 1");
        
        // Stick it into a feature branch.
        remoteGit.branch("feature1");
        remoteGit.checkout("feature1");
        pushToBare();
        
      	assertFalse("scm polling cannot detect a new commit because the workspace doesn't exist yet.", pollSCMChanges(project));
        
        build(project, Result.SUCCESS, commitFile1);
        System.out.println(project.getLastBuild().getLog());
        String log = bareGit.launchCommand("log","--pretty=oneline","integrate");
        assertTrue("The Publisher never pushed the commit.",log.contains("Test message"));
        
      	assertFalse("scm polling should not detect a new commit because the build completed.", pollSCMChanges(project));
      	
        ////
        //  Do it again to make sure we don't get Non Fast Forward merge errors.
        ////
        
        // create initial commit and then run the build against it:
        final String commitFile2 = "commitFile2";
        remoteGit.checkout("feature1");
        commit(commitFile2, remoteGit, johnDoe, "Commit number 2");
        
        pushToBare();
        
      	assertTrue("scm polling must detect a new commit after this change.", pollSCMChanges(project));
        
        build(project, Result.SUCCESS, commitFile2);
        System.out.println(project.getLastBuild().getLog());
        log = bareGit.launchCommand("log","--pretty=oneline","integrate");
        assertTrue("The Publisher never pushed the commit.",log.contains("Test message"));
        
      	assertFalse("scm polling must not find a change.", pollSCMChanges(project));
    }

    /**
     * Method name is self-explanatory.
     */
    public void testExclusionOfBranchToBuild() throws Exception {
        final String commitFile1 = "commitFile1";
        // Empty string means build any branches with changes.
        String excludeBranch = "exludeMe";
        FreeStyleProject project = setupProjectThatCommits("",excludeBranch,commitFile1);
        
        // create initial commit and then run the build against it:
        commit(commitFile1, remoteGit, johnDoe, "Commit number 1");
        
        // Stick it into a feature branch.
        remoteGit.branch("feature1");
        remoteGit.checkout("feature1");
        pushToBare();
        
      	assertFalse("scm polling cannot detect a new commit because the workspace doesn't exist yet.", pollSCMChanges(project));
        
        build(project, Result.SUCCESS, commitFile1);
        System.out.println(project.getLastBuild().getLog());
        String log = bareGit.launchCommand("log","--pretty=oneline","integrate");
        assertTrue("The Publisher never pushed the commit.",log.contains("Test message"));
        
      	assertFalse("scm polling should not detect a new commit because the build completed.", pollSCMChanges(project));
      	
        ////
        //  Do it again to make sure we don't get Non Fast Forward merge errors.
        ////
        
        // create initial commit and then run the build against it:
        final String commitFile2 = "commitFile2";
        remoteGit.branch(excludeBranch);
        remoteGit.checkout(excludeBranch);
        commit(commitFile2, remoteGit, johnDoe, "Commit number 2");
        
        pushToBare();
        
      	assertFalse("scm polling must not detect a commit to the excluded branch.", pollSCMChanges(project));
    }
    
    /**
     * Method name is self-explanatory.
     */
    public void testNewCommitToUntrackedBranchDoesNotTriggerBuild() throws Exception {
        FreeStyleProject project = setupSimpleProject("master");

        // create initial commit and then run the build against it:
        final String commitFile1 = "commitFile1";
        commit(commitFile1, johnDoe, "Commit number 1");
        pushToBare();
        build(project, Result.SUCCESS, commitFile1);

        remoteGit.branch("untracked");
        remoteGit.checkout("untracked");
        
        //now create and checkout a new branch:
        //.. and commit to it:
        final String commitFile2 = "commitFile2";
        commit(commitFile2, remoteGit, johnDoe, "Commit number 2");
        pushToBare();
        assertFalse("scm polling should not detect commit2 change because it is not in the branch we are tracking.", pollSCMChanges(project));
    }

    /**
     * A previous version of GitSCM would only build against branches, not tags. This test checks that that
     * regression has been fixed.
     */
    public void testGitSCMCanBuildAgainstTags() throws Exception {
    	// This test assumes we alredy have a repository.
    	// That makes sense for tags since the build creates
    	// them, we must already have a repository.
        git = new GitAPI("git", workspace, listener, envVars);
    	git.init();
        final String mytag = "mytag";
        FreeStyleProject project = setupSimpleProject(git,mytag);
        final String commitFile1 = "commitFile1";
        commit(commitFile1, johnDoe, "Commit number 1");
        pushToBare();

        //now create and checkout a new branch:
        final String tmpBranch = "tmp";
        // commit to it
        final String commitFile2 = "commitFile2";
        commit(commitFile2, git, johnDoe, "Commit number 2");
        git.branch(tmpBranch);
        git.checkout(tmpBranch);
        pushToBare();
        
        assertFalse("scm polling should not detect any more changes since mytag is untouched right now", project.pollSCMChanges(listener));
        build(project, Result.FAILURE, commitFile2);

        // tag it, then delete the tmp branch
        git.tag(mytag, "mytag initial");
        git.checkout("master");
        git.launchCommand("branch", "-D", tmpBranch);

        // at this point we're back on master, there are no other branches, tag "mytag" exists but is
        // not part of "master"
        assertTrue("scm polling should detect commit2 change in 'mytag'", project.pollSCMChanges(listener));
        build(project, Result.SUCCESS, commitFile2);
        assertFalse("scm polling should not detect any more changes after last build", project.pollSCMChanges(listener));

        // now, create tmp branch again against mytag:
        git.branch(tmpBranch);
        git.checkout(tmpBranch);
        // another commit:
        final String commitFile3 = "commitFile3";
        commit(commitFile3, git, johnDoe, "Commit number 3");
        pushToBare();
        assertFalse("scm polling should not detect any more changes since mytag is untouched right now", project.pollSCMChanges(listener));

        // now we're going to force mytag to point to the new commit, if everything goes well, gitSCM should pick the change up:
        git.tag(mytag, "mytag moved");
        git.checkout("master");
        git.launchCommand("branch", "-D", tmpBranch);

        // at this point we're back on master, there are no other branches, "mytag" has been updated to a new commit:
        assertTrue("scm polling should detect commit3 change in 'mytag'", project.pollSCMChanges(listener));
        build(project, Result.SUCCESS, commitFile3);
        assertFalse("scm polling should not detect any more changes after last build", project.pollSCMChanges(listener));
    }

    /**
     * Not specifying a branch string in the project implies that we should be polling for changes in
     * all branches.
     */
    public void testMultipleBranchBuild() throws Exception {
        // empty string will result in a project that tracks against changes in all branches:
        final FreeStyleProject project = setupSimpleProject("");
        final String commitFile1 = "commitFile1";
        commit(commitFile1, johnDoe, "Commit number 1");
        pushToBare();
        build(project, Result.SUCCESS, commitFile1);

        // create a branch here so we can get back to this point  later...
        final String fork = "fork";
        remoteGit.branch(fork);

        final String commitFile2 = "commitFile2";
        commit(commitFile2, remoteGit, johnDoe, "Commit number 2");
        pushToBare();
        final String commitFile3 = "commitFile3";
        commit(commitFile3, remoteGit, johnDoe, "Commit number 3");
        pushToBare();
        assertTrue("scm polling should detect changes in 'master' branch", project.pollSCMChanges(listener));
        build(project, Result.SUCCESS, commitFile1, commitFile2);
        assertFalse("scm polling should not detect any more changes after last build", project.pollSCMChanges(listener));

        // now jump back...
        remoteGit.checkout(fork);

        // add some commits to the fork branch...
        final String forkFile1 = "forkFile1";
        commit(forkFile1, remoteGit, johnDoe, "Fork commit number 1");
        pushToBare();
        final String forkFile2 = "forkFile2";
        commit(forkFile2, remoteGit, johnDoe, "Fork commit number 2");
        pushToBare();
        assertTrue("scm polling should detect changes in 'fork' branch", project.pollSCMChanges(listener));
        build(project, Result.SUCCESS, forkFile1, forkFile2);
        assertFalse("scm polling should not detect any more changes after last build", project.pollSCMChanges(listener));
    }

    @SuppressWarnings("deprecation")
	private FreeStyleProject setupProjectThatCommits(String branchString, String excludeBranch, String fileName) throws Exception {
        FreeStyleProject project = createFreeStyleProject();
        final MockStaplerRequest req = new MockStaplerRequest()
            .setRepo(bareDir.getAbsolutePath(), "origin", "")
            .setBranch(branchString)
            .setExcludeBranch(excludeBranch)
            .setMergeTarget("integrate")
            .setGitClean("true");
        project.setScm(hudson.getScm("GitSCM").newInstance(req, null));
        final MockStaplerRequest req2 = new MockStaplerRequest();
        GitCommitPublisher buildStep = new GitCommitPublisher.DescriptorImpl().newInstance(req2,null);
        project.addPublisher(buildStep);
        ///
        /// NOTE: This test will fail on *nix machines.
        /// Instead you can create a shell script builder object
        /// and give it shell commands to do the same thing
        /// which is to write the current time (with milliseconds)
        /// to a file and commit it.
        ///
        String repository = bareGit.workspace.toString();
        BatchFile commitBuilder = new BatchFile(
        		"echo %time% > " + fileName + "\n"+
        		"git add .\n"+
        		"git commit -a -m\"Test message\"\n"+
        		"git push " + repository + " HEAD:refs/heads/integrate");
        project.getBuildersList().add(commitBuilder);
        return project;
    }

    private FreeStyleProject setupSimpleProject(String branchString) throws Exception {
    	FreeStyleProject project = setupSimpleProject(remoteGit,branchString);
    	return project;
    }
    
    private FreeStyleProject setupSimpleProject(GitAPI someGit, String branchString) throws Exception {
        FreeStyleProject project = createFreeStyleProject();
    	if( project.getWorkspace() != null) {
    		project.getWorkspace().delete();
    	}
        final MockStaplerRequest req = new MockStaplerRequest()
            .setRepo(someGit.workspace.toString(), "origin", "")
            .setBranch(branchString);
        project.setScm(hudson.getScm("GitSCM").newInstance(req, null));
        project.getBuildersList().add(new CaptureEnvironmentBuilder());
        return project;
    }

    private FreeStyleBuild build(final FreeStyleProject project, final Result expectedResult, final String...expectedNewlyCommittedFiles) throws Exception {
        final FreeStyleBuild build = project.scheduleBuild2(0, new Cause.UserCause()).get();
        String log = project.getBuilds().get(0).getLog();
        for(final String expectedNewlyCommittedFile : expectedNewlyCommittedFiles) {
            assertTrue("After build check for file failed for " + expectedNewlyCommittedFile +
            		"\nThe project log file follows:\n" + log, project.getWorkspace().child(expectedNewlyCommittedFile).exists());
        }
        if(expectedResult != null) {
            assertBuildStatus(expectedResult, build);
        }
        return build;
    }

    private void commit(final String fileName, final PersonIdent committer, final String message) throws GitException {
    	commit(fileName, remoteGit, committer, message);
    }
    
    private void commit(final String fileName, GitAPI someGit, final PersonIdent committer, final String message) throws GitException {
        setAuthor(committer);
        setCommitter(committer);
        FilePath file = someGit.workspace.child(fileName);
        try {
            file.write(fileName, null);
        } catch (Exception e) {
            throw new GitException("unable to write file", e);
        }
        someGit.add(fileName);
        someGit.launchCommand("commit", "-m", message);
    	System.out.println("==Remote branches:==");
        List<Branch> branches = someGit.getBranches();
        for( int i=0; i<branches.size(); i++) {
        	System.out.println(branches.get(i).name);
        }
    	System.out.println("==Bare branches:==");
        branches = bareGit.getBranches();
        for( int i=0; i<branches.size(); i++) {
        	System.out.println(branches.get(i).name);
        }
    }
    
    private void pushToBare() throws GitException {
    	remoteGit.push(bareDir.getAbsolutePath(),"HEAD");
    }
}
