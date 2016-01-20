package org.jenkinsci.plugins.rundeck;

import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.Util;
import hudson.model.*;
import hudson.model.Cause.UpstreamCause;
import hudson.model.Descriptor.FormException;
import hudson.model.Run.Artifact;
import hudson.scm.ChangeLogSet.Entry;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.util.FormValidation;

import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.List;

import net.sf.json.JSONObject;

import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.rundeck.api.*;
import org.rundeck.api.RundeckApiException.RundeckApiLoginException;
import org.rundeck.api.domain.RundeckExecution;
import org.rundeck.api.domain.RundeckExecution.ExecutionStatus;
import org.rundeck.api.domain.RundeckJob;
import org.rundeck.api.domain.RundeckOutput;
import org.rundeck.api.domain.RundeckOutputEntry;

/**
 * Jenkins {@link Notifier} that runs a job on Rundeck (via the {@link RundeckClient})
 * 
 * @author Vincent Behar
 */
public class RundeckNotifier extends Notifier {

    /** Pattern used for the token expansion of $ARTIFACT_NAME{regex} */
    private static final transient Pattern TOKEN_ARTIFACT_NAME_PATTERN = Pattern.compile("\\$ARTIFACT_NAME\\{(.+)\\}");

    /** Pattern used for extracting the job reference (project:group/name) */
    private static final transient Pattern JOB_REFERENCE_PATTERN = Pattern.compile("^([^:]+?):(.*?)\\/?([^/]+)$");

    
    private final String jobId;

    private final String options;

    private final String nodeFilters;

    private final String tag;

    private final Boolean shouldWaitForRundeckJob;

    private final Boolean shouldFailTheBuild;

    private final Boolean includeRundeckLogs;
    
    private RundeckClient myRundeckInstance;  // instance of the actual job TODO
    private String serverURL;   
    private String jobUser;
    private String jobPassword;
    
    public RundeckNotifier(String jobId, String options, String nodeFilters, String tag,
            Boolean shouldWaitForRundeckJob, Boolean shouldFailTheBuild) {
       this(jobId, options, nodeFilters, tag,             
            shouldWaitForRundeckJob, shouldFailTheBuild, false, null,  null, null);
    }

    public RundeckNotifier(String jobId, String options, String nodeFilters, String tag,
            Boolean shouldWaitForRundeckJob, Boolean shouldFailTheBuild, String serverURL, String apiToken,
            String jobUser, String jobPassword) {
       this(jobId, options, nodeFilters, tag,
            shouldWaitForRundeckJob, shouldFailTheBuild, false, serverURL, jobUser, jobPassword);
    }

    @DataBoundConstructor
    public RundeckNotifier(String jobId, String options, String nodeFilters, String tag,
            Boolean shouldWaitForRundeckJob, Boolean shouldFailTheBuild, Boolean includeRundeckLogs,
		String serverurl, String jobUser, String jobPassword) {
        this.jobId = jobId;
        this.options = options;
        this.nodeFilters = nodeFilters;
        this.tag = tag;
        this.shouldWaitForRundeckJob = shouldWaitForRundeckJob;
        this.shouldFailTheBuild = shouldFailTheBuild;
        this.includeRundeckLogs = includeRundeckLogs;
        this.serverURL = serverurl;        
        this.jobUser = jobUser;
        this.jobPassword = jobPassword;
    }
    
    public RundeckClient getMyRundeckInstance() {
    	if (myRundeckInstance == null)
    	{
    		// FIXME TEST, move to getProjectAction
    		RundeckClient myRundeck = null;
			try {
				myRundeck = getDescriptor().getRundeckClient(this.getServerURL(), this.getJobUser(), this.getJobPassword(), false);
			} catch (FormException e) {
				// ignore
				;
			}
    		setMyRundeckInstance(myRundeck);
    	}
		return myRundeckInstance;
	}

	public void setMyRundeckInstance(RundeckClient myRundeckInstance) {
		this.myRundeckInstance = myRundeckInstance;
	}

	private void logConfig (BuildListener listener, RundeckDescriptor descriptor, String info)
    {
		String globalinfo = "no global rundeck instance";
		if (descriptor.getGlobalRundeckInstance() != null)
		{
			globalinfo = "URL/user: " + descriptor.getGlobalRundeckInstance().getUrl()
		    		+	 descriptor.getGlobalRundeckInstance().getLogin();
		}
    	listener.getLogger().println("Descriptor data: " + info + "\nglobal:\n" +  globalinfo);
    	listener.getLogger().println("job\nURL/user: " + this.serverURL
        		+	this.jobUser );
    	  	 
    	 Iterator<RundeckClient> iterator = RundeckDescriptor.hmRundeckInstances.values().iterator(); 
		int i = 0;
    	 while (iterator.hasNext()) {
    		 i++;
			RundeckClient rundeck = iterator.next();
			info = info + "\n" + i + " " + rundeck.toString() + " url:" + rundeck.getUrl() + " login:" +  rundeck.getLogin() + " pw:" 
					+ rundeck.getPassword() + " token:" + rundeck.getToken();

		}
		RundeckClient globalinstance = descriptor.getRundeckInstance();
		info = info + "\n descriptor rundeckInstance: URL " + globalinstance.getUrl() + " login " + globalinstance.getLogin();
		
		listener.getLogger().println("job rundeck instances: "  + RundeckDescriptor.hmRundeckInstances.size() + info);
		
    }

    /**
     * perform trigger of rundeck job
     * @param build
     * @param launcher
     * @param listener
     * @return
     * @throws InterruptedException
     * @throws IOException
     */
    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
            throws InterruptedException, IOException {
        if (build.getResult() != Result.SUCCESS) {
            return true;
        }

        RundeckDescriptor descriptor = getDescriptor();  
        // this.getRundeckInstance ist hier null -  löschen des attributes
        //TODO ist das dieselbe instanz, die newInstance gerufen hat?
        //RundeckClient rundeck = RundeckDescriptor.getRundeckInstanceForKey(this.getServerURL(), this.getJobUser())  ;   // this.getRundeckInstance(); FIXME
        RundeckClient rundeck = null;
		try {
			rundeck = descriptor.getRundeckClient(this.getServerURL(), this.getJobUser(), this.getJobPassword(), false);
		} catch (FormException e1) {
			// handle above
			;
		} 
       
//        logConfig (listener, descriptor, " perform:  global rundeck instance " + descriptor.getRundeckInstance() + " job instance from map " + rundeck); // TODO
        
//        listener.getLogger().println(" perform: with rundeck client:\n job-URL:" + this.getServerURL()
//    			+ " job-user:" + this.jobUser + " job-pw:  " + this.jobPassword + " used rundeckinstance: " + rundeck);        
            

        if (rundeck == null) {
            listener.getLogger().println("Rundeck configuration is not valid !");
            return false;
        }
        try {
            rundeck.ping();
        } catch (RundeckApiException e) {
            listener.getLogger().println("Rundeck is not running !");
            return false;
        }

        if (shouldNotifyRundeck(build, listener)) {
        	try {
                RundeckDescriptor.findJobId(jobId, rundeck);
            } catch (RundeckApiException e) {
                listener.getLogger().println("Failed to get job with the identifier : " + jobId + " : "+e.getMessage());
                return false;
            } 
            return notifyRundeck(rundeck, build, listener);
        }

        return true;
    }
    
    

    /**
     * Check if we need to notify Rundeck for this build. If we have a tag, we will look for it in the changelog of the
     * build and in the changelog of all upstream builds.
     * 
     * @param build for checking the changelog
     * @param listener for logging the result
     * @return true if we should notify Rundeck, false otherwise
     */
    private boolean shouldNotifyRundeck(AbstractBuild<?, ?> build, BuildListener listener) {
        if (StringUtils.isBlank(tag)) {
            listener.getLogger().println("Notifying Rundeck...");
            return true;
        }

        // check for the tag in the changelog
        for (Entry changeLog : build.getChangeSet()) {
            if (StringUtils.containsIgnoreCase(changeLog.getMsg(), tag)) {
                listener.getLogger().println("Found " + tag + " in changelog (from " + changeLog.getAuthor().getId()
                                             + ") - Notifying Rundeck...");
                return true;
            }
        }

        // if we have an upstream cause, check for the tag in the changelog from upstream
        for (Cause cause : build.getCauses()) {
            if (UpstreamCause.class.isInstance(cause)) {
                UpstreamCause upstreamCause = (UpstreamCause) cause;
                TopLevelItem item = Hudson.getInstance().getItem(upstreamCause.getUpstreamProject());
                if (AbstractProject.class.isInstance(item)) {
                    AbstractProject<?, ?> upstreamProject = (AbstractProject<?, ?>) item;
                    AbstractBuild<?, ?> upstreamBuild = upstreamProject.getBuildByNumber(upstreamCause.getUpstreamBuild());
                    if (upstreamBuild != null) {
                        for (Entry changeLog : upstreamBuild.getChangeSet()) {
                            if (StringUtils.containsIgnoreCase(changeLog.getMsg(), tag)) {
                                listener.getLogger().println("Found " + tag + " in changelog (from "
                                                             + changeLog.getAuthor().getId() + ") in upstream build ("
                                                             + upstreamBuild.getFullDisplayName()
                                                             + ") - Notifying Rundeck...");
                                return true;
                            }
                        }
                    }
                }
            }
        }

        return false;
    }

    /**
     * Notify Rundeck : run a job on Rundeck
     * 
     * @param rundeck instance to notify
     * @param build for adding actions
     * @param listener for logging the result
     * @return true if successful, false otherwise
     */
    private boolean notifyRundeck(RundeckClient rundeck, AbstractBuild<?, ?> build, BuildListener listener) {
        //if the jobId is in the form "project:[group/*]name", find the actual job ID first.
        String foundJobId = null;
        
        try {
            foundJobId = RundeckDescriptor.findJobId(jobId, rundeck);
        } catch (RundeckApiException e) {
            listener.getLogger().println("Failed to get job with the identifier : " + jobId + " : "+e.getMessage());
            return false;
        } catch (IllegalArgumentException e) {
            listener.getLogger().println("Failed to get job with the identifier : " + jobId + " : " +e.getMessage());
            return false;
        }
        if (foundJobId == null) {
            listener.getLogger().println("Could not find a job with the identifier : " + jobId);
            return false;
        }
        try {
            RundeckExecution execution = rundeck.triggerJob(RunJobBuilder.builder()
                    .setJobId(foundJobId)
                    .setOptions(parseProperties(options, build, listener))
                    .setNodeFilters(parseProperties(nodeFilters, build, listener))
                    .build());

            listener.getLogger().println("Notification succeeded ! Execution #" + execution.getId() + ", at "
                    + execution.getUrl() + " (status : " + execution.getStatus() + ")");
            build.addAction(new RundeckExecutionBuildBadgeAction(execution.getUrl()));

            if (Boolean.TRUE.equals(shouldWaitForRundeckJob)) {
                listener.getLogger().println("Waiting for Rundeck execution to finish...");
                while (ExecutionStatus.RUNNING.equals(execution.getStatus())) {
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        listener.getLogger().println("Oops, interrupted ! " + e.getMessage());
                        break;
                    }
                    execution = rundeck.getExecution(execution.getId());
                }
                listener.getLogger().println("Rundeck execution #" + execution.getId() + " finished in "
                        + execution.getDuration() + ", with status : " + execution.getStatus());

                if (Boolean.TRUE.equals(includeRundeckLogs)) {
                   listener.getLogger().println("BEGIN RUNDECK LOG OUTPUT ------------------------------");
                   RundeckOutput rundeckOutput = rundeck.getJobExecutionOutput(execution.getId(), 0, 0, 0);
                   if (null != rundeckOutput) {
                      List<RundeckOutputEntry> logEntries = rundeckOutput.getLogEntries();
                         if (null != logEntries) {
                            for (int i=0; i<logEntries.size(); i++) {
                               RundeckOutputEntry rundeckOutputEntry = (RundeckOutputEntry)logEntries.get(i);
                               listener.getLogger().println(rundeckOutputEntry.getMessage());
                            }
                         }
                   }
                   listener.getLogger().println("END RUNDECK LOG OUTPUT --------------------------------");
                }

                switch (execution.getStatus()) {
                    case SUCCEEDED:
                        return true;
                    case ABORTED:
                    case FAILED:
                        if (getShouldFailTheBuild())
                           build.setResult(Result.FAILURE);
                        return false;
                    default:
                        return true;
                }
            } else {
                return true;
            }
        } catch (RundeckApiLoginException e) {
            listener.getLogger().println("Login failed on " + rundeck.getUrl() + " : " + e.getMessage());
            return false;
        } catch (RundeckApiException.RundeckApiTokenException e) {
            listener.getLogger().println("Token auth failed on " + rundeck.getUrl() + " : " + e.getMessage());
            return false;
        } catch (RundeckApiException e) {
            listener.getLogger().println("Error while talking to Rundeck's API at " + rundeck.getUrl() + " : "
                                         + e.getMessage());
            return false;
        } catch (IllegalArgumentException e) {
            listener.getLogger().println("Configuration error : " + e.getMessage());
            return false;
        }
    }

    /**
     * Parse the given input (should be in the Java-Properties syntax) and expand Jenkins environment variables.
     * 
     * @param input specified in the Java-Properties syntax (multi-line, key and value separated by = or :)
     * @param build for retrieving Jenkins environment variables
     * @param listener for retrieving Jenkins environment variables and logging the errors
     * @return A {@link Properties} instance (may be empty), or null if unable to parse the options
     */
    private Properties parseProperties(String input, AbstractBuild<?, ?> build, BuildListener listener) {
        if (StringUtils.isBlank(input)) {
            return new Properties();
        }

        // try to expand jenkins env vars
        try {
            EnvVars envVars = build.getEnvironment(listener);
            input = Util.replaceMacro(input, envVars);
        } catch (Exception e) {
            listener.getLogger().println("Failed to expand environment variables : " + e.getMessage());
        }

        // expand our custom tokens : $ARTIFACT_NAME{regex} => name of the first matching artifact found
        // http://groups.google.com/group/rundeck-discuss/browse_thread/thread/94a6833b84fdc10b
        Matcher matcher = TOKEN_ARTIFACT_NAME_PATTERN.matcher(input);
        int idx = 0;
        while (matcher.reset(input).find(idx)) {
            idx = matcher.end();
            String regex = matcher.group(1);
            Pattern pattern = Pattern.compile(regex);
            for (@SuppressWarnings("rawtypes")
            Artifact artifact : build.getArtifacts()) {
                if (pattern.matcher(artifact.getFileName()).matches()) {
                    input = StringUtils.replace(input, matcher.group(0), artifact.getFileName());
                    idx = matcher.start() + artifact.getFileName().length();
                    break;
                }
            }
        }

        try {
            return Util.loadProperties(input);
        } catch (IOException e) {
            listener.getLogger().println("Failed to parse : " + input);
            listener.getLogger().println("Error : " + e.getMessage());
            return null;
        }
    }

    /* (non-Javadoc)
     * called on job setup 
     * @see hudson.tasks.BuildStepCompatibilityLayer#getProjectAction(hudson.model.AbstractProject)
     */
    @Override
    public Action getProjectAction(AbstractProject<?, ?> project) {
        try {        	
        	// RundeckClient rundeck = getDescriptor().getRundeckInstance();
        	RundeckClient rundeck = getMyRundeckInstance();
        	RundeckDescriptor.log("getProjectAction", this.toString() + " use instance " + rundeck.toString() +   "for: job " + jobId );  // TODO debug

        	return new RundeckJobProjectLinkerAction(rundeck, jobId);            
        } catch (RundeckApiException e) {
            return null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * If we should not fail the build, we need to run after finalized, so that the result of "perform" is not used by
     * Jenkins
     */
    @Override
    public boolean needsToRunAfterFinalized() {
        return !shouldFailTheBuild;
    }

    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    public String getJobIdentifier() {
        return jobId;
    }

    public String getJobId() {
        return jobId;
    }

    public String getOptions() {
        return options;
    }

    public String getNodeFilters() {
        return nodeFilters;
    }

    public String getServerURL() {
        return serverURL;
    }
    
    public void setServerURL(String url)
    {
        serverURL = url;
    }
    

    public String getJobUser() {
		return jobUser;
	}

	public String getJobPassword() {
		return jobPassword;
	}

	public String getTag() {
        return tag;
    }

    public Boolean getShouldWaitForRundeckJob() {
        return shouldWaitForRundeckJob;
    }

    public Boolean getShouldFailTheBuild() {
        return shouldFailTheBuild;
    }

    public Boolean getIncludeRundeckLogs() {
        return includeRundeckLogs;
    }

    @Override
    public RundeckDescriptor getDescriptor() {
        return (RundeckDescriptor) super.getDescriptor();
    }


	@Extension(ordinal = 1000)
    public static final class RundeckDescriptor extends BuildStepDescriptor<Publisher> {

    	private  static HashMap<String, RundeckClient> hmRundeckInstances = new HashMap<String, RundeckClient>(); 
        private RundeckClient rundeckInstance;	// normal instance
        private RundeckClient globalRundeckInstance;	// store for initial instance

		String jobURL;

		String jobinfos;
		Integer apiversion;

        public RundeckDescriptor() {
            super();
            load();
        }

		public static void log(String module, String info){
			// support debug
        	String out = module + ": " + info;
        	Date now = new Date();
//        	System.err.println(now.toLocaleString() + ": " + out);
        }
        
        public static RundeckClient getRundeckInstanceForKey(String url, String user)
        {
        	RundeckClient found = null;
        	String key = RundeckDescriptor.getRdKey(url, user);
        	found = hmRundeckInstances.get(key);
        	return found;
        }

        public RundeckClient getGlobalRundeckInstance() {
			return globalRundeckInstance;
		}

		public void setGlobalRundeckInstance(RundeckClient globalRundeckInstance) {
			this.globalRundeckInstance = globalRundeckInstance;
		}

		/* (non-Javadoc)
         * global configuration of rundeck connection
         * called on changes in global configuration
         * @see hudson.model.Descriptor#configure(org.kohsuke.stapler.StaplerRequest, net.sf.json.JSONObject)
         */
        @Override
        public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
        	
        	RundeckDescriptor.log("configure", this.toString() + " init "   +   json.toString()); 

            try {
                RundeckClientBuilder builder = RundeckClient.builder();
                builder.url(json.getString("url"));
                if (json.get("authtoken") != null && !"".equals(json.getString("authtoken"))) {
                    builder.token(json.getString("authtoken"));
                } else {
                    builder.login(json.getString("login"), json.getString("password"));
                }

                if (json.optInt("apiversion") > 0) {
                    builder.version(json.getInt("apiversion"));
                }
                setRundeckInstance(builder.build()); //###
            } catch (IllegalArgumentException e) {
            	 setRundeckInstance(null);                
            }
            
            save();
            return super.configure(req, json);
        }
        //### von B
        public void setConfig(String url, String authtoken, int apiversion) {
            RundeckClientBuilder builder = RundeckClient.builder();
            builder.url(url);
            builder.token(authtoken);
            builder.version(apiversion);
            rundeckInstance = builder.build();
            save();
        }

        public void setConfig(String url, String login, String password, int apiversion) {
            RundeckClientBuilder builder = RundeckClient.builder();
            builder.url(url);
            builder.login(login, password);
            builder.version(apiversion);
            rundeckInstance = builder.build();
            save();
        }
//### bis hier von B
//### jetzt von C

        /**
         * Generate key for map of rundeck client instances
         * if url = null or empty return null
         * @param url
         * @param user
         * @return
         */
        public static String getRdKey(String url, String user)
        {
        	String key = null;
        	if (url != null && url.trim().length()  >  0)
        	{
        		key = url + "#";
        		if (user != null && user.trim().length() >  0){
        			key = key + user.trim();
        		}
        	}
        	

        	return key;
        }
        
        /**
         * checks if url/user are same as global rundeck instance
         * @param url
         * @param user
         * @return
         */
        public boolean isGlobalRdKey(String url, String user)
        {
        	boolean rc = false;
        	
        	if (rundeckInstance != null && url != null )
        	{
        		rc = true;
        		if (!url.equals(rundeckInstance.getUrl())) rc = false;
        		if (user != null)
        		{
        		  if (!user.equals(rundeckInstance.getLogin())) rc = false;
        		  
        		}
        		else{
        			if (rundeckInstance.getLogin() != null) rc = false;
        		}
        	}
        	
        	return rc;
        }

        /**
         *
         * called on changes in job definition fields
         * @param req
         * @param formData
         * @return
         * @throws FormException
         */
        @Override
        public Publisher newInstance(StaplerRequest req, JSONObject formData) throws FormException {
            String jobIdentifier = formData.getString("jobIdentifier");
            RundeckJob job = null;
            
            
            RundeckClient rundeck = getRundeckClient(formData.getString("serverURL"), formData.getString("jobUser"), formData.getString("jobPassword"), true );                   
            
            RundeckDescriptor.log("newInstance", " jobid "  + jobIdentifier + " use rundeckinstance " + rundeck + " notifier instance " + this); 
            try {
                job = findJob(jobIdentifier, rundeck);
            } catch (RundeckApiException e) {
                throw new FormException("Failed to get job with the identifier : " + jobIdentifier, e, "jobIdentifier");
            } catch (IllegalArgumentException e) {
                throw new FormException("Failed to get job with the identifier : " + jobIdentifier, e, "jobIdentifier");
            }
            if (job == null) {
                throw new FormException("Could not found a job with the identifier : " + jobIdentifier, "jobIdentifier");
            }
            RundeckNotifier notifier = new RundeckNotifier(jobIdentifier,
                                       formData.getString("options"),
                                       formData.getString("nodeFilters"),
                                       formData.getString("tag"),
                                       formData.getBoolean("shouldWaitForRundeckJob"),
                                       formData.getBoolean("shouldFailTheBuild"),
                                       formData.getBoolean("includeRundeckLogs"),
                                       formData.getString("serverURL"),                                                                            
                                       formData.getString("jobUser"),
                                       formData.getString("jobPassword")
            			);            
                                     	
            return notifier;
        }
        
        /**
         * Compare strings.
         * strings can be null or empty. An empty string is handled like a null string.
         * @param s1
         * @param s2
         * @return
         */
        private boolean stringsAreEqual(String s1, String s2)
        {
			boolean rc = false;
			if (s1 == null && s2 == null) {
				return true;
			}
			if (s1 != null && s2 != null) {
				return (s1.trim().equals(s2.trim()));
			}
			if (StringUtils.isBlank(s1) && StringUtils.isBlank(s2)) {
				// one string is null and one string is blank
				
				return true;
			}

			return rc;
		}
        
        /**
         * create rundeckClient from actual data (global or job)
         * or take global rundeckInstance
         * 
         * @param serverurl
         * @param user
         * @param password
         * 
         * @param calledFromForm
         * @return
         * @throws hudson.model.Descriptor.FormException 
         */
        public  RundeckClient getRundeckClient(String serverurl, String user, String password, boolean calledFromForm) throws hudson.model.Descriptor.FormException{
        	RundeckClient rc = null;
        	       	
        	// check if initial instance exists
        	if (getRundeckInstance() == null)
            {
            	// no instance, create one            	
            	rc = buildNewRundeckClient(serverurl, user, password, "serverURL");
            	// save as initial and global
            	setRundeckInstance(rc);
            	setGlobalRundeckInstance(rc);
            	RundeckDescriptor.log("getRundeckClient", this.toString() +  " create first and global rundeckInstance " + rc.toString() + ", use this for URL " + serverurl + " user " + user);
            	return rc;
            }
        	
        	String url = serverurl;
        	
        	// compare data with global data
        	RundeckClient globalInstance = getGlobalRundeckInstance();
        	//
        	if (globalInstance == null)
        	{
        		/*
        		 * search individual or create new individual if user and pw are set 
        		 */
        		RundeckClient rundeck = RundeckDescriptor.getRundeckInstanceForKey(url, user);

        		if (rundeck == null)
        		{
        			rundeck = buildNewRundeckClient(url, user, password, "serverURL");        			   
        			hmRundeckInstances.put(RundeckDescriptor.getRdKey(url, user), rundeck);
        		}
        		RundeckDescriptor.log("getRundeckClient", this.toString() + " no globalinstance, use individual " + rundeck.toString() +   "for: url " + url + " user " + user );

        		return rundeck;
        	}
        	// global instance is set:	
        	boolean useGlobal = false;
        	if (serverurl == null || serverurl.trim().length() == 0)
        	{
        		// take Url from global configuration
        		url = globalInstance.getUrl();
        	}
        	String login = user;
        	if (user == null || user.trim().length() == 0)
        	{
        		// take user from global configuration and take global instance
        		login = globalInstance.getLogin();
        		useGlobal = true;
        	}
        	
        	if (!useGlobal  && (this.stringsAreEqual(globalInstance.getUrl(),url) && this.stringsAreEqual(globalInstance.getLogin(),user))){
        		RundeckDescriptor.log("getRundeckClient", " use globalinstance " + globalInstance.toString() +   "for: url >" + serverurl + "< user >" + user + "<" + " = global login  <" + globalInstance.getLogin() + "> ? " + this.stringsAreEqual(globalInstance.getLogin(),user) );
        		useGlobal = true;
			}
        	
        	if (useGlobal){
        		// global instance fits wanted server connection
        		RundeckDescriptor.log("getRundeckClient", this.toString() + " use globalinstance " + globalInstance.toString() +   "for: url >" + serverurl + "< user >" + user + "<" );
        		return globalInstance;
        	}
        		       	
        	String key = RundeckDescriptor.getRdKey(url, login);
        	
        	// look for individual instance
        	RundeckClient rundeck = RundeckDescriptor.getRundeckInstanceForKey(url, login);
        	
        	if (rundeck != null)
        	{
        		// found existing individual instance
        		RundeckDescriptor.log("getRundeckClient", this.toString() + " use existing " + rundeck.toString() +   "for: url " + serverurl + " user " + user );

        		return rundeck;        		
        	}
        	
        	RundeckDescriptor.log("getRundeckClient", this.toString() + " no instance found "   +   "for: url <" + serverurl + "> user <" + user + "> create new with <" + url + "< >" + login + "< >"  + password + "< global is:" + getGlobalRundeckInstance().toString());

        	// no fitting rundeck client found until now, create new one.
			rc = buildNewRundeckClient(url, login, password, "serverURL");			
			hmRundeckInstances.put(key, rc);
                 
        	return rc;
        }

		/**
		 * Create new instance of RundeckClient.
		 * @param url
		 * @param user
		 * @param password
		 * 
		 * @param formField - for error message
		 * @return
		 * @throws hudson.model.Descriptor.FormException 
		 */
		private RundeckClient buildNewRundeckClient(String url, String user,
				String password, String formField) throws hudson.model.Descriptor.FormException {
			RundeckClient rc;
			
			String globalinfo = "null";
			if (getGlobalRundeckInstance() != null)
			{
				globalinfo = getGlobalRundeckInstance().toString();
			}
			RundeckDescriptor.log("buildNewRundeckClient", this.toString() +
					" start build new RundeckClient for url " + url + " user " + user
							+ " globale is " + globalinfo); 
			// create new client
			RundeckClientBuilder builder = RundeckClient.builder();

			builder.url(url);

			try {
				
					rc = builder.login(user, password).build();

				
			} catch (IllegalArgumentException e) {

				RundeckDescriptor.log("buildNewRundeckClient",
						"ERROR in build: url " + url + " user " + user);
				if (formField != null)
				{
					throw new FormException("ERROR in build: url " + url + " user " + user , e, formField);	
				}
				return null;				 
			}

			RundeckDescriptor.log("buildNewRundeckClient", this.toString() +
					" build new RundeckClient, url " + url + " user " + user
							+ " new instance " + rc);
			return rc;
		}
       
		/**
		 * Validate job form JobIdentifier with button
		 * @param url
		 * @param jobIdentifier
		 * @param login
		 * @param password
		 * @param token
		 * @return
		 */
		public FormValidation doTestJobIdentifier(
				@QueryParameter("serverURL") final String url,
				@QueryParameter("jobIdentifier") final String jobIdentifier,
				@QueryParameter("jobUser") final String login,
				@QueryParameter("jobPassword") final String password,
				@QueryParameter(value = "apiToken", fixEmpty = true) final String token) {			

			FormValidation rc = checkJobIdentifier(jobIdentifier, url, login,
					password);
			return rc;            

		}
        		
        /**
         * Test global connection config
         * @param url
         * @param login
         * @param password
         * @param token
         * @param apiversion
         * @return
         */
        public FormValidation doTestConnection(@QueryParameter("rundeck.url") final String url,
                @QueryParameter("rundeck.login") final String login,
                @QueryParameter("rundeck.password") final String password,
                @QueryParameter(value = "rundeck.authtoken", fixEmpty = true) final String token,
                @QueryParameter(value = "rundeck.apiversion", fixEmpty = true) final Integer apiversion) {

            RundeckClient rundeck = null;
            RundeckClientBuilder builder = RundeckClient.builder().url(url);
            if (null != apiversion && apiversion > 0) {
                builder.version(apiversion);
                this.apiversion = apiversion;
            } else {
                builder.version(RundeckClient.API_VERSION);
            }
            try {
                if (null != token) {
                    rundeck = builder.token(token).build();
                } else {
                    rundeck = builder.login(login, password).build();
                }
            } catch (IllegalArgumentException e) {
                return FormValidation.error("Rundeck configuration is not valid ! %s", e.getMessage());
            }
            try {
                rundeck.ping();
            } catch (RundeckApiException e) {
                return FormValidation.error("We couldn't find a live Rundeck instance at %s", rundeck.getUrl());
            }
            try {
                rundeck.testAuth();
            } catch (RundeckApiLoginException e) {
                return FormValidation.error("Your credentials for the user %s are not valid !", rundeck.getLogin());
            } catch (RundeckApiException.RundeckApiTokenException e) {
                return FormValidation.error("Your token authentication is not valid!");
            }
            // infos
            String info = "URL: " +  rundeck.getUrl() + " user: " + rundeck.getLogin();
            RundeckDescriptor.log("doTestConnection: info ",  info  + " rundeckinstance " + rundeck); 

            return FormValidation.ok("Your Rundeck instance is alive, and your credentials are valid !" + info); 
        }
        
		/**
		 * check job parameter against rundeck server
		 * @param jobIdentifier
		 * @param url
		 * @param login
		 * @param password
		 * @return
		 */
		private FormValidation checkJobIdentifier(String jobIdentifier,
				String url, String login, String password) {
			
			RundeckClient actClient;
			try {
				actClient = getRundeckClient(url, login, password,true);
			} catch (hudson.model.Descriptor.FormException e1) {
				return FormValidation
						.error("Rundeck (global) configuration: no valid Client for  " 
								+ this.jobURL + " user: " + login + "ex:" + e1.getMessage() );
				
			} 			
			
			
			RundeckDescriptor.log("checkJobIdentifier", " jobid "  + jobIdentifier + " rundeckinstance " + actClient + " url " + actClient.getUrl()); 

			String info = " job URL: " + url + " user: " + login + " rundeckInstance: " + actClient.toString();
	
			//TODO first check  connection (see doTestConnection)  
			
			if (StringUtils.isBlank(url) && getGlobalRundeckInstance() == null) {
				return FormValidation
						.error("No global rundeck configuration: serverURL, user, password  are mandatory !");
			}
			if (StringUtils.isBlank(jobIdentifier)) {
				return FormValidation
						.error("The job identifier is mandatory !");
			}
			//check user data if user, then password and vice versa
			if (!StringUtils.isBlank(login) && StringUtils.isBlank(password)) {
				return FormValidation
						.error("password is empty !");
			}
			if (StringUtils.isBlank(login) && !StringUtils.isBlank(password)) {
				return FormValidation
						.error("user is empty !");
			}
			if (StringUtils.isBlank(login) && !StringUtils.isBlank(url)) {
				// ir url is set we need user too.
				return FormValidation
						.error("user is empty !");
			}
			
			try {
				RundeckJob job = findJob(jobIdentifier, actClient);
				if (job == null) {
					return FormValidation
							.error("Could not find a job with the identifier : %s, URL %s login %s",
									jobIdentifier, url, login + info);
				} else {
					// check FullName against jobIdentifier
					String jobname = jobIdentifier;
					String[] parts = jobIdentifier.split(":");
					if (parts.length == 2)
					{
						jobname = parts[1];
					}
					if (jobname.equals(job.getFullName())){
						
					
					return FormValidation.ok(
							"Your Rundeck job is : %s [%s] %s, user %s", job.getId(),
							job.getProject(), job.getFullName(), actClient.getLogin());
					}
					else {
						return FormValidation.error(
								"invalid job, found job : %s [%s] %s, user %s", job.getId(),
								job.getProject(), job.getFullName(), actClient.getLogin());
					}
				}
			} catch (RundeckApiException e) {
				return FormValidation.error("Failed to get job details : %s",
						e.getMessage());
			} catch (IllegalArgumentException e) {
				return FormValidation.error("Failed to get job details : %s",
						e.getMessage());
			}

		}

        /**
         * check valid job infos
         * @param jobIdentifier
         * @return
         */
		public FormValidation doCheckJobIdentifier(
				@QueryParameter("jobIdentifier") final String jobIdentifier,
				@QueryParameter("serverURL") final String url,
				@QueryParameter("jobUser") final String login,
				@QueryParameter("jobPassword") final String password) {

			FormValidation rc = checkJobIdentifier(jobIdentifier, url, login,
					password);
			return rc;
		}

        /**
         * Return a rundeck Job ID, by find a rundeck job if the identifier is a project:[group/]*name format, otherwise
         * returning the original identifier as the ID.
         * @param jobIdentifier either a Job ID, or "project:[group/]*name"
         * @param rundeckClient the client instance
         * @return a job UUID
         * @throws RundeckApiException
         * @throws IllegalArgumentException
         */
        static String findJobId(String jobIdentifier, RundeckClient rundeckClient) throws RundeckApiException,
                IllegalArgumentException {
            Matcher matcher = JOB_REFERENCE_PATTERN.matcher(jobIdentifier);
            if (matcher.find() && matcher.groupCount() == 3) {
                String project = matcher.group(1);
                String groupPath = matcher.group(2);
                String name = matcher.group(3);
                return rundeckClient.findJob(project, groupPath, name).getId();
            } else {
                return jobIdentifier;
            }
        }
        /**
         * Find a {@link RundeckJob} with the given identifier
         *
         * @param jobIdentifier either a simple ID, an UUID or a reference (project:group/name)
         * @param rundeck
         * @return the {@link RundeckJob} found, or null if not found
         * @throws RundeckApiException in case of error, or if no job with this ID
         * @throws IllegalArgumentException if the identifier is not valid
         */
        public static RundeckJob findJob(String jobIdentifier, RundeckClient rundeck) throws RundeckApiException, IllegalArgumentException {
            Matcher matcher = JOB_REFERENCE_PATTERN.matcher(jobIdentifier);
            if (matcher.find() && matcher.groupCount() == 3) {
                String project = matcher.group(1);
                String groupPath = matcher.group(2);
                String name = matcher.group(3);
                return rundeck.findJob(project, groupPath, name);
            } else {
                return rundeck.getJob(jobIdentifier);
            }
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "Rundeck";
        }

        public RundeckClient getRundeckInstance() {
        	if (globalRundeckInstance == null) globalRundeckInstance = rundeckInstance;
        	//take original instance instead of rundeckInstance
            return globalRundeckInstance;
        }

        public void setRundeckInstance(RundeckClient rundeckInstance) {
        	// update global instance
        	this.setGlobalRundeckInstance(rundeckInstance);
            this.rundeckInstance = rundeckInstance;
        }
    }

    /**
     * {@link BuildBadgeAction} used to display a Rundeck icon + a link to the Rundeck execution page, on the Jenkins
     * build history and build result page.
     */
    public static class RundeckExecutionBuildBadgeAction implements BuildBadgeAction {

        private final String executionUrl;

        public RundeckExecutionBuildBadgeAction(String executionUrl) {
            super();
            this.executionUrl = executionUrl;
        }

        public String getDisplayName() {
            return "Rundeck Execution Result";
        }

        public String getIconFileName() {
            return "/plugin/rundeck/images/rundeck_24x24.png";
        }

        public String getUrlName() {
            return executionUrl;
        }

    }

}
