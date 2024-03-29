package com.mui.launch;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.Map.Entry;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.maven.Maven;
import org.apache.maven.SettingsConfigurationException;
import org.apache.maven.artifact.manager.WagonManager;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.ArtifactRepositoryFactory;
import org.apache.maven.artifact.repository.ArtifactRepositoryPolicy;
import org.apache.maven.artifact.repository.DefaultArtifactRepository;
import org.apache.maven.artifact.repository.layout.ArtifactRepositoryLayout;
import org.apache.maven.cli.BatchModeDownloadMonitor;
import org.apache.maven.cli.ConsoleDownloadMonitor;
import org.apache.maven.cli.MavenCli;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.ReactorManager;
import org.apache.maven.monitor.event.DefaultEventDispatcher;
import org.apache.maven.monitor.event.DefaultEventMonitor;
import org.apache.maven.monitor.event.EventDispatcher;
import org.apache.maven.plugin.Mojo;
import org.apache.maven.profiles.DefaultProfileManager;
import org.apache.maven.profiles.ProfileManager;
import org.apache.maven.reactor.MavenExecutionException;
import org.apache.maven.settings.MavenSettingsBuilder;
import org.apache.maven.settings.RuntimeInfo;
import org.apache.maven.settings.Settings;
import org.codehaus.classworlds.ClassWorld;
import org.codehaus.plexus.PlexusContainerException;
import org.codehaus.plexus.component.composition.DefaultComponentComposerManager;
import org.codehaus.plexus.component.repository.ComponentDescriptor;
import org.codehaus.plexus.component.repository.exception.ComponentLifecycleException;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.codehaus.plexus.embed.Embedder;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.logging.LoggerManager;
import org.codehaus.plexus.util.Os;
import org.codehaus.plexus.util.cli.CommandLineUtils;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import com.mui.env.MavenEnvironmentConstants;
import com.mui.integration.MavenSystemProperties;
import com.mui.logger.MavenLogger;
import com.mui.logger.TextAreaLoggerManager;
import com.mui.monitor.BatchModeProgressDownloadMonitor;
import com.mui.monitor.ProgressDownloadMonitor;
import com.mui.*;

public class MavenGui {

	/** @deprecated use {@link Os#OS_NAME} */
	public static final String OS_NAME = Os.OS_NAME;

	/** @deprecated use {@link Os#OS_ARCH} */
	public static final String OS_ARCH = Os.OS_ARCH;

	/** @deprecated use {@link Os#OS_VERSION} */
	public static final String OS_VERSION = Os.OS_VERSION;

	private static Embedder embedder;

	private static MavenCommonContext context = MavenCommonContext.getInstance();
	
	public static int main(String[] args, ClassWorld classWorld) {

		// ----------------------------------------------------------------------
		// Setup the command line parser
		// ----------------------------------------------------------------------

		CLIManager cliManager = new CLIManager();

		CommandLine commandLine;
		try {
			commandLine = cliManager.parse(args);
		} catch (ParseException e) {
			MavenLogger.error("Unable to parse command line options: ",e);
			cliManager.displayHelp();
			return 1;
		}

		// TODO: maybe classworlds could handle this requirement...
		if ("1.4".compareTo(System.getProperty("java.specification.version")) > 0) {
			MavenLogger.error("Sorry, but JDK 1.4 or above is required to execute Maven. You appear to be using "
							+ "Java:");
			MavenLogger.error("java version \""
					+ System.getProperty("java.version",
							"<unknown java version>") + "\"");
			MavenLogger.error(System.getProperty("java.runtime.name",
					"<unknown runtime name>")
					+ " (build "
					+ System.getProperty("java.runtime.version",
							"<unknown runtime version>") + ")");
			MavenLogger.error(System.getProperty("java.vm.name",
					"<unknown vm name>")
					+ " (build "
					+ System.getProperty("java.vm.version",
							"<unknown vm version>")
					+ ", "
					+ System.getProperty("java.vm.info", "<unknown vm info>")
					+ ")");

			return 1;
		}

		boolean debug = commandLine.hasOption(CLIManager.DEBUG);

		boolean showErrors = debug || commandLine.hasOption(CLIManager.ERRORS);

		if (showErrors) {
			MavenLogger.info("+ Error stacktraces are turned on.");
		}

		// ----------------------------------------------------------------------
		// Process particular command line options
		// ----------------------------------------------------------------------

		if (commandLine.hasOption(CLIManager.HELP)) {
			cliManager.displayHelp();
			return 0;
		}

		if (commandLine.hasOption(CLIManager.VERSION)) {
			showVersion();

			return 0;
		} else if (debug) {
			showVersion();
		}
		EventDispatcher eventDispatcher = new DefaultEventDispatcher();
		embedder = new Embedder();
		try {
			embedder.start(classWorld);
		} catch (PlexusContainerException e) {
			showFatalError("Unable to start the embedded plexus container", e,
					showErrors);

			return 1;
		}

		// ----------------------------------------------------------------------
		// The execution properties need to be created before the settings
		// are constructed.
		// ----------------------------------------------------------------------

		Properties executionProperties = new Properties();
		Properties userProperties = new Properties();
		populateProperties(commandLine, executionProperties, userProperties);

		Settings settings;

		try {
			settings = buildSettings(commandLine);
		} catch (SettingsConfigurationException e) {
			showError("Error reading settings.xml: " + e.getMessage(), e,
					showErrors);

			return 1;
		} catch (ComponentLookupException e) {
			showFatalError("Unable to read settings.xml", e, showErrors);

			return 1;
		}

		Maven maven = null;

		MavenExecutionRequest request = null;

		LoggerManager loggerManager = null;

		try {
			// logger must be created first
			if(context.textAreaLogAppender == null)
				loggerManager = (LoggerManager) embedder.lookup(LoggerManager.ROLE);
			else{
				TextAreaLoggerManager taManager = new TextAreaLoggerManager("info");
				//embedder.addContextValue(key, value)
				loggerManager = (LoggerManager) taManager;
			}
			
			/*TextAreaLoggerManager taManager = new TextAreaLoggerManager("info");
			//embedder.addContextValue(key, value)
			loggerManager = (LoggerManager) taManager;*/
			embedder.setLoggerManager(loggerManager);

			if (debug) {
				loggerManager.setThreshold(Logger.LEVEL_DEBUG);
			} else if (commandLine.hasOption(CLIManager.QUIET)) {
				// TODO: we need to do some more work here. Some plugins use sys
				// out or log errors at info level.
				// Ideally, we could use Warn across the board
				loggerManager.setThreshold(Logger.LEVEL_ERROR);
				// TODO:Additionally, we can't change the mojo level because the
				// component key includes the version and it isn't known ahead
				// of time. This seems worth changing.
			}

			ProfileManager profileManager = new DefaultProfileManager(embedder
					.getContainer(), executionProperties);

			if (commandLine.hasOption(CLIManager.ACTIVATE_PROFILES)) {
				String profilesLine = commandLine
						.getOptionValue(CLIManager.ACTIVATE_PROFILES);

				StringTokenizer profileTokens = new StringTokenizer(
						profilesLine, ",");

				while (profileTokens.hasMoreTokens()) {
					String profileAction = profileTokens.nextToken().trim();

					if (profileAction.startsWith("-")) {
						profileManager.explicitlyDeactivate(profileAction
								.substring(1));
					} else if (profileAction.startsWith("+")) {
						profileManager.explicitlyActivate(profileAction
								.substring(1));
					} else {
						// TODO: deprecate this eventually!
						profileManager.explicitlyActivate(profileAction);
					}
				}
			}

			request = createRequest(commandLine, settings, eventDispatcher,
					loggerManager, profileManager, executionProperties,
					userProperties, showErrors);

			setProjectFileOptions(commandLine, request);

			maven = createMavenInstance(settings.isInteractiveMode());
		} catch (ComponentLookupException e) {
			showFatalError("Unable to configure the Maven application", e,
					showErrors);

			return 1;
		} finally {
			if (loggerManager != null) {
				try {
					embedder.release(loggerManager);
				} catch (ComponentLifecycleException e) {
					showFatalError("Error releasing logging manager", e,
							showErrors);
				}
			}
		}

		try {
			maven.execute(request);
		} catch (MavenExecutionException e) {
			return 1;
		}

		return 0;
	}

	private static Maven createMavenInstance(boolean interactive)
			throws ComponentLookupException {
		// TODO [BP]: doing this here as it is CLI specific, though it doesn't
		// feel like the right place (likewise logger).
		WagonManager wagonManager = (WagonManager) embedder
				.lookup(WagonManager.ROLE);
		if (interactive) {
			wagonManager.setDownloadMonitor(new ProgressDownloadMonitor());
		} else {
			wagonManager.setDownloadMonitor(new BatchModeProgressDownloadMonitor());
		}

		wagonManager.setInteractive(interactive);

		return (Maven) embedder.lookup(Maven.ROLE);
	}

	private static MavenExecutionRequest createRequest(CommandLine commandLine,
			Settings settings, EventDispatcher eventDispatcher,
			LoggerManager loggerManager, ProfileManager profileManager,
			Properties executionProperties, Properties userProperties,
			boolean showErrors) throws ComponentLookupException {
		MavenExecutionRequest request;

		ArtifactRepository localRepository = createLocalRepository(embedder,
				settings, commandLine);

		//TODO: need to take the given value
		File userDir = new File(System.getProperty("user.dir"));

		request = new DefaultMavenExecutionRequest(localRepository, settings,
				eventDispatcher, commandLine.getArgList(), userDir.getPath(),
				profileManager, executionProperties, userProperties, showErrors);

		// TODO [BP]: do we set one per mojo? where to do it?
		Logger logger = loggerManager.getLoggerForComponent(Mojo.ROLE);

		if (logger != null) {
			request.addEventMonitor(new DefaultEventMonitor(logger));
		}

		if (commandLine.hasOption(CLIManager.NON_RECURSIVE)) {
			request.setRecursive(false);
		}

		if (commandLine.hasOption(CLIManager.FAIL_FAST)) {
			request.setFailureBehavior(ReactorManager.FAIL_FAST);
		} else if (commandLine.hasOption(CLIManager.FAIL_AT_END)) {
			request.setFailureBehavior(ReactorManager.FAIL_AT_END);
		} else if (commandLine.hasOption(CLIManager.FAIL_NEVER)) {
			request.setFailureBehavior(ReactorManager.FAIL_NEVER);
		}

		return request;
	}

	private static ArtifactRepository createLocalRepository(Embedder embedder,
			Settings settings, CommandLine commandLine)
			throws ComponentLookupException {
		// TODO: release
		// TODO: something in plexus to show all active hooks?
		ArtifactRepositoryLayout repositoryLayout = (ArtifactRepositoryLayout) embedder
				.lookup(ArtifactRepositoryLayout.ROLE, "default");

		ArtifactRepositoryFactory artifactRepositoryFactory = (ArtifactRepositoryFactory) embedder
				.lookup(ArtifactRepositoryFactory.ROLE);

		String url = settings.getLocalRepository();

		if (!url.startsWith("file:")) {
			url = "file://" + url;
		}

		ArtifactRepository localRepository = new DefaultArtifactRepository(
				"local", url, repositoryLayout);

		boolean snapshotPolicySet = false;

		if (commandLine.hasOption(CLIManager.OFFLINE)) {
			settings.setOffline(true);

			snapshotPolicySet = true;
		}

		if (!snapshotPolicySet
				&& commandLine.hasOption(CLIManager.UPDATE_SNAPSHOTS)) {
			artifactRepositoryFactory
					.setGlobalUpdatePolicy(ArtifactRepositoryPolicy.UPDATE_POLICY_ALWAYS);
		}

		if (commandLine.hasOption(CLIManager.CHECKSUM_FAILURE_POLICY)) {
			MavenLogger.info("+ Enabling strict checksum verification on all artifact downloads.");

			artifactRepositoryFactory
					.setGlobalChecksumPolicy(ArtifactRepositoryPolicy.CHECKSUM_POLICY_FAIL);
		} else if (commandLine.hasOption(CLIManager.CHECKSUM_WARNING_POLICY)) {
			MavenLogger.info("+ Disabling strict checksum verification on all artifact downloads.");

			artifactRepositoryFactory
					.setGlobalChecksumPolicy(ArtifactRepositoryPolicy.CHECKSUM_POLICY_WARN);
		}

		return localRepository;
	}

	private static void setProjectFileOptions(CommandLine commandLine,
			MavenExecutionRequest request) {
		if (commandLine.hasOption(CLIManager.REACTOR)) {
			request.setReactorActive(true);
		} else if (commandLine.hasOption(CLIManager.ALTERNATE_POM_FILE)) {
			request.setPomFile(commandLine
					.getOptionValue(CLIManager.ALTERNATE_POM_FILE));
		}
	}

	private static Settings buildSettings(CommandLine commandLine)
			throws ComponentLookupException, SettingsConfigurationException {
		String userSettingsPath = null;

		if (commandLine.hasOption(CLIManager.ALTERNATE_USER_SETTINGS)) {
			userSettingsPath = commandLine
					.getOptionValue(CLIManager.ALTERNATE_USER_SETTINGS);
		}

		if(userSettingsPath == null || userSettingsPath.equals("")){
			userSettingsPath = 
				context.mavenEnvironmentVariables.getValue(MavenEnvironmentConstants.MAVEN_HOME_ENV_VAR_NAME)
					+ "\\conf\\settings.xml";
		}
		MavenLogger.info("Settings : " + userSettingsPath);
		Settings settings = null;
		
		MavenSettingsBuilder settingsBuilder = (MavenSettingsBuilder) embedder
				.lookup(MavenSettingsBuilder.ROLE);

		try {
			if (userSettingsPath != null) {
				File userSettingsFile = new File(userSettingsPath);

				if (userSettingsFile.exists()
						&& !userSettingsFile.isDirectory()) {
					settings = settingsBuilder.buildSettings(userSettingsFile);
				} else {
					MavenLogger.info("WARNING: Alternate user settings file: "
									+ userSettingsPath
									+ " is invalid. Using default path.");
				}
			}

			if (settings == null) {
				settings = settingsBuilder.buildSettings();
			}
			
			
		} catch (IOException e) {
			throw new SettingsConfigurationException(
					"Error reading settings file", e);
		} catch (XmlPullParserException e) {
			throw new SettingsConfigurationException(e.getMessage(), e
					.getDetail(), e.getLineNumber(), e.getColumnNumber());
		}

		// why aren't these part of the runtime info? jvz.

		if (commandLine.hasOption(CLIManager.BATCH_MODE)) {
			settings.setInteractiveMode(false);
		}

		if (commandLine.hasOption(CLIManager.SUPPRESS_PLUGIN_REGISTRY)) {
			settings.setUsePluginRegistry(false);
		}

		// Create settings runtime info

		settings.setRuntimeInfo(createRuntimeInfo(commandLine, settings));

		return settings;
	}

	private static RuntimeInfo createRuntimeInfo(CommandLine commandLine,
			Settings settings) {
		RuntimeInfo runtimeInfo = new RuntimeInfo(settings);

		if (commandLine.hasOption(CLIManager.FORCE_PLUGIN_UPDATES)
				|| commandLine.hasOption(CLIManager.FORCE_PLUGIN_UPDATES2)) {
			runtimeInfo.setPluginUpdateOverride(Boolean.TRUE);
		} else if (commandLine.hasOption(CLIManager.SUPPRESS_PLUGIN_UPDATES)) {
			runtimeInfo.setPluginUpdateOverride(Boolean.FALSE);
		}

		return runtimeInfo;
	}

	static void populateProperties(CommandLine commandLine,
			Properties executionProperties, Properties userProperties) {
		// add the env vars to the property set, with the "env." prefix
		// XXX support for env vars should probably be removed from the
		// ModelInterpolator
		try {
			Properties envVars = CommandLineUtils.getSystemEnvVars();
			Iterator i = envVars.entrySet().iterator();
			while (i.hasNext()) {
				Entry e = (Entry) i.next();
				executionProperties.setProperty("env." + e.getKey().toString(),
						e.getValue().toString());
			}
		} catch (IOException e) {
			MavenLogger.error("Error getting environment vars for profile activation: "
							+ e);
		}

		// ----------------------------------------------------------------------
		// Options that are set on the command line become system properties
		// and therefore are set in the session properties. System properties
		// are most dominant.
		// ----------------------------------------------------------------------

		if (commandLine.hasOption(CLIManager.SET_SYSTEM_PROPERTY)) {
			String[] defStrs = commandLine
					.getOptionValues(CLIManager.SET_SYSTEM_PROPERTY);

			if (defStrs != null) {
				for (int i = 0; i < defStrs.length; ++i) {
					setCliProperty(defStrs[i], userProperties);
				}
			}

			executionProperties.putAll(userProperties);
		}

		executionProperties.putAll(System.getProperties());
	}

	private static void setCliProperty(String property,
			Properties requestProperties) {
		String name;

		String value;

		int i = property.indexOf("=");

		if (i <= 0) {
			name = property.trim();

			value = "true";
		} else {
			name = property.substring(0, i).trim();

			value = property.substring(i + 1).trim();
		}

		requestProperties.setProperty(name, value);

		// ----------------------------------------------------------------------
		// I'm leaving the setting of system properties here as not to break
		// the SystemPropertyProfileActivator. This won't harm embedding. jvz.
		// ----------------------------------------------------------------------

		System.setProperty(name, value);
	}

	private static void showFatalError(String message, Exception e, boolean show) {
		MavenLogger.error("FATAL ERROR: " + message);
		if (show) {
			MavenLogger.error("Error stacktrace:", e);
		} else {
			MavenLogger.error("For more information, run with the -e flag");
		}
	}

	private static void showError(String message, Exception e, boolean show) {
		MavenLogger.error(message);
		if (show) {
			MavenLogger.error("Error stacktrace:\n", e);
		}
	}

	private static void showVersion() {
		InputStream resourceAsStream;
		try {
			Properties properties = new Properties();
			resourceAsStream = MavenCli.class
					.getClassLoader()
					.getResourceAsStream(
							"META-INF/maven/org.apache.maven/maven-core/pom.properties");

			if (resourceAsStream != null) {
				properties.load(resourceAsStream);

				if (properties.getProperty("builtOn") != null) {
					MavenLogger.info("Maven version: "
							+ properties.getProperty("version", "unknown")
							+ " built on " + properties.getProperty("builtOn"));
				} else {
					MavenLogger.info("Maven version: "
							+ properties.getProperty("version", "unknown"));
				}
			} else {
				MavenLogger.info("Maven version: unknown");
			}

			MavenLogger.info("Java version: "
					+ System.getProperty("java.version",
							"<unknown java version>"));

			MavenLogger.info("OS name: \"" + Os.OS_NAME + "\" version: \""
					+ Os.OS_VERSION + "\" arch: \"" + Os.OS_ARCH
					+ "\" Family: \"" + Os.OS_FAMILY + "\"");

		} catch (IOException e) {
			MavenLogger.error("Unable determine version from JAR file: "
					+ e.getMessage());
		}
	}

	// ----------------------------------------------------------------------
	// Command line manager
	// ----------------------------------------------------------------------

	static class CLIManager {
		public static final char ALTERNATE_POM_FILE = 'f';

		public static final char BATCH_MODE = 'B';

		public static final char SET_SYSTEM_PROPERTY = 'D';

		public static final char OFFLINE = 'o';

		public static final char REACTOR = 'r';

		public static final char QUIET = 'q';

		public static final char DEBUG = 'X';

		public static final char ERRORS = 'e';

		public static final char HELP = 'h';

		public static final char VERSION = 'v';

		private Options options;

		public static final char NON_RECURSIVE = 'N';

		public static final char UPDATE_SNAPSHOTS = 'U';

		public static final char ACTIVATE_PROFILES = 'P';

		public static final String FORCE_PLUGIN_UPDATES = "cpu";

		public static final String FORCE_PLUGIN_UPDATES2 = "up";

		public static final String SUPPRESS_PLUGIN_UPDATES = "npu";

		public static final String SUPPRESS_PLUGIN_REGISTRY = "npr";

		public static final char CHECKSUM_FAILURE_POLICY = 'C';

		public static final char CHECKSUM_WARNING_POLICY = 'c';

		private static final char ALTERNATE_USER_SETTINGS = 's';

		private static final String FAIL_FAST = "ff";

		private static final String FAIL_AT_END = "fae";

		private static final String FAIL_NEVER = "fn";

		public CLIManager() {
			options = new Options();

			options.addOption(OptionBuilder.withLongOpt("file").hasArg()
					.withDescription("Force the use of an alternate POM file.")
					.create(ALTERNATE_POM_FILE));

			options.addOption(OptionBuilder.withLongOpt("define").hasArg()
					.withDescription("Define a system property").create(
							SET_SYSTEM_PROPERTY));
			options.addOption(OptionBuilder.withLongOpt("offline")
					.withDescription("Work offline").create(OFFLINE));
			options.addOption(OptionBuilder.withLongOpt("help")
					.withDescription("Display help information").create(HELP));
			options.addOption(OptionBuilder.withLongOpt("version")
					.withDescription("Display version information").create(
							VERSION));
			options.addOption(OptionBuilder.withLongOpt("quiet")
					.withDescription("Quiet output - only show errors").create(
							QUIET));
			options.addOption(OptionBuilder.withLongOpt("debug")
					.withDescription("Produce execution debug output").create(
							DEBUG));
			options.addOption(OptionBuilder.withLongOpt("errors")
					.withDescription("Produce execution error messages")
					.create(ERRORS));
			options.addOption(OptionBuilder.withLongOpt("reactor")
					.withDescription(
							"Execute goals for project found in the reactor")
					.create(REACTOR));
			options.addOption(OptionBuilder.withLongOpt("non-recursive")
					.withDescription("Do not recurse into sub-projects")
					.create(NON_RECURSIVE));
			options
					.addOption(OptionBuilder
							.withLongOpt("update-snapshots")
							.withDescription(
									"Forces a check for updated releases and snapshots on remote repositories")
							.create(UPDATE_SNAPSHOTS));
			options.addOption(OptionBuilder.withLongOpt("activate-profiles")
					.withDescription(
							"Comma-delimited list of profiles to activate")
					.hasArg().create(ACTIVATE_PROFILES));

			options.addOption(OptionBuilder.withLongOpt("batch-mode")
					.withDescription("Run in non-interactive (batch) mode")
					.create(BATCH_MODE));

			options
					.addOption(OptionBuilder
							.withLongOpt("check-plugin-updates")
							.withDescription(
									"Force upToDate check for any relevant registered plugins")
							.create(FORCE_PLUGIN_UPDATES));
			options.addOption(OptionBuilder.withLongOpt("update-plugins")
					.withDescription("Synonym for " + FORCE_PLUGIN_UPDATES)
					.create(FORCE_PLUGIN_UPDATES2));
			options
					.addOption(OptionBuilder
							.withLongOpt("no-plugin-updates")
							.withDescription(
									"Suppress upToDate check for any relevant registered plugins")
							.create(SUPPRESS_PLUGIN_UPDATES));

			options
					.addOption(OptionBuilder
							.withLongOpt("no-plugin-registry")
							.withDescription(
									"Don't use ~/.m2/plugin-registry.xml for plugin versions")
							.create(SUPPRESS_PLUGIN_REGISTRY));

			options.addOption(OptionBuilder.withLongOpt("strict-checksums")
					.withDescription("Fail the build if checksums don't match")
					.create(CHECKSUM_FAILURE_POLICY));
			options.addOption(OptionBuilder.withLongOpt("lax-checksums")
					.withDescription("Warn if checksums don't match").create(
							CHECKSUM_WARNING_POLICY));

			options.addOption(OptionBuilder.withLongOpt("settings")
					.withDescription(
							"Alternate path for the user settings file")
					.hasArg().create(ALTERNATE_USER_SETTINGS));

			options.addOption(OptionBuilder.withLongOpt("fail-fast")
					.withDescription(
							"Stop at first failure in reactorized builds")
					.create(FAIL_FAST));

			options
					.addOption(OptionBuilder
							.withLongOpt("fail-at-end")
							.withDescription(
									"Only fail the build afterwards; allow all non-impacted builds to continue")
							.create(FAIL_AT_END));

			options
					.addOption(OptionBuilder
							.withLongOpt("fail-never")
							.withDescription(
									"NEVER fail the build, regardless of project result")
							.create(FAIL_NEVER));
		}

		public CommandLine parse(String[] args) throws ParseException {
			// We need to eat any quotes surrounding arguments...
			String[] cleanArgs = cleanArgs(args);

			CommandLineParser parser = new GnuParser();
			return parser.parse(options, cleanArgs);
		}

		private String[] cleanArgs(String[] args) {
			List cleaned = new ArrayList();

			StringBuffer currentArg = null;

			for (int i = 0; i < args.length; i++) {
				String arg = args[i];

				// MavenLogger.info( "Processing raw arg: " + arg );

				boolean addedToBuffer = false;

				if (arg.startsWith("\"")) {
					// if we're in the process of building up another arg, push
					// it and start over.
					// this is for the case: "-Dfoo=bar "-Dfoo2=bar two" (note
					// the first unterminated quote)
					if (currentArg != null) {
						// MavenLogger.info( "Flushing last arg buffer: \'" +
						// currentArg + "\' to cleaned list." );
						cleaned.add(currentArg.toString());
					}

					// start building an argument here.
					currentArg = new StringBuffer(arg.substring(1));
					addedToBuffer = true;
				}

				// this has to be a separate "if" statement, to capture the case
				// of: "-Dfoo=bar"
				if (arg.endsWith("\"")) {
					String cleanArgPart = arg.substring(0, arg.length() - 1);

					// if we're building an argument, keep doing so.
					if (currentArg != null) {
						// if this is the case of "-Dfoo=bar", then we need to
						// adjust the buffer.
						if (addedToBuffer) {
							// MavenLogger.info(
							// "Adjusting argument already appended to the arg buffer."
							// );
							currentArg.setLength(currentArg.length() - 1);
						}
						// otherwise, we trim the trailing " and append to the
						// buffer.
						else {
							// MavenLogger.info( "Appending arg part: \'" +
							// cleanArgPart +
							// "\' with preceding space to arg buffer." );
							// TODO: introducing a space here...not sure what
							// else to do but collapse whitespace
							currentArg.append(' ').append(cleanArgPart);
						}

						// MavenLogger.info(
						// "Flushing completed arg buffer: \'" + currentArg +
						// "\' to cleaned list." );

						// we're done with this argument, so add it.
						cleaned.add(currentArg.toString());
					} else {
						// MavenLogger.info( "appending cleaned arg: \'" +
						// cleanArgPart + "\' directly to cleaned list." );
						// this is a simple argument...just add it.
						cleaned.add(cleanArgPart);
					}

					// MavenLogger.info( "Clearing arg buffer." );
					// the currentArg MUST be finished when this completes.
					currentArg = null;
					continue;
				}

				// if we haven't added this arg to the buffer, and we ARE
				// building an argument
				// buffer, then append it with a preceding space...again, not
				// sure what else to
				// do other than collapse whitespace.
				// NOTE: The case of a trailing quote is handled by nullifying
				// the arg buffer.
				if (!addedToBuffer) {
					// append to the argument we're building, collapsing
					// whitespace to a single space.
					if (currentArg != null) {
						// MavenLogger.info( "Append unquoted arg part: \'" +
						// arg + "\' to arg buffer." );
						currentArg.append(' ').append(arg);
					}
					// this is a loner, just add it directly.
					else {
						// MavenLogger.info( "Append unquoted arg part: \'" +
						// arg + "\' directly to cleaned list." );
						cleaned.add(arg);
					}
				}
			}

			// clean up.
			if (currentArg != null) {
				// MavenLogger.info( "Adding unterminated arg buffer: \'" +
				// currentArg + "\' to cleaned list." );
				cleaned.add(currentArg.toString());
			}

			int cleanedSz = cleaned.size();
			String[] cleanArgs = null;

			if (cleanedSz == 0) {
				// if we didn't have any arguments to clean, simply pass the
				// original array through
				cleanArgs = args;
			} else {
				// MavenLogger.info( "Cleaned argument list:\n" + cleaned );
				cleanArgs = (String[]) cleaned.toArray(new String[cleanedSz]);
			}

			return cleanArgs;
		}

		public void displayHelp() {
			MavenLogger.info("");

			HelpFormatter formatter = new HelpFormatter();
			formatter.printHelp("mvn [options] [<goal(s)>] [<phase(s)>]",
					"\nOptions:", options, "\n");
		}
	}
}
