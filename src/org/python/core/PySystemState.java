// Copyright (c) Corporation for National Research Initiatives
package org.python.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLDecoder;
import java.security.AccessControlException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.python.Version;
import org.python.core.adapter.ClassicPyObjectAdapter;
import org.python.core.adapter.ExtensiblePyObjectAdapter;
import org.python.core.packagecache.PackageManager;
import org.python.core.packagecache.SysPackageManager;
import org.python.modules.Setup;
import org.python.modules.zipimport.zipimporter;

/**
 * The "sys" module.
 */
// xxx this should really be a module!
public class PySystemState extends PyObject
{
    public static final String PYTHON_CACHEDIR = "python.cachedir";
    public static final String PYTHON_CACHEDIR_SKIP = "python.cachedir.skip";
    protected static final String CACHEDIR_DEFAULT_NAME = "cachedir";
    
    public static final String JYTHON_JAR = "jython.jar";
    public static final String JYTHON_COMPLETE_JAR = "jython-complete.jar";

    private static final String JAR_URL_PREFIX = "jar:file:";
    private static final String JAR_SEPARATOR = "!";

    public static PyString version = new PyString(Version.getVersion());
    public static int hexversion = ((Version.PY_MAJOR_VERSION << 24) |
                                    (Version.PY_MINOR_VERSION << 16) |
                                    (Version.PY_MICRO_VERSION <<  8) |
                                    (Version.PY_RELEASE_LEVEL <<  4) |
                                    (Version.PY_RELEASE_SERIAL << 0));

    public static PyTuple version_info;

    public final static int maxunicode = 1114111;
    public static PyTuple subversion;
    /**
     * The copyright notice for this release.
     */
    // TBD: should we use \u00a9 Unicode c-inside-circle?
    public static PyObject copyright = Py.newString(
        "Copyright (c) 2000-2008, Jython Developers\n" +
        "All rights reserved.\n\n" +

        "Copyright (c) 2000 BeOpen.com.\n" +
        "All Rights Reserved.\n\n"+

        "Copyright (c) 2000 The Apache Software Foundation.  All rights\n" +
        "reserved.\n\n" +

        "Copyright (c) 1995-2000 Corporation for National Research "+
        "Initiatives.\n" +
        "All Rights Reserved.\n\n" +

        "Copyright (c) 1991-1995 Stichting Mathematisch Centrum, " +
        "Amsterdam.\n" +
        "All Rights Reserved.\n\n");

    private static Map<String,String> builtinNames;
    public static PyTuple builtin_module_names = null;

    public static PackageManager packageManager;
    public static File cachedir;
    
    private static PyList defaultPath;
    private static PyList defaultArgv;
    private static PyObject defaultExecutable;

    public static Properties registry; // = init_registry();
    public static PyObject prefix;
    public static PyObject exec_prefix = Py.EmptyString;

    private static boolean initialized = false;
    
    /** The arguments passed to this program on the command line. */
    public PyList argv = new PyList();

    public PyObject modules;
    public PyList path;
    public static PyObject builtins;

    public PyList meta_path;
    public PyList path_hooks;
    public PyObject path_importer_cache;

    public static PyString platform = new PyString("java");
    public static PyString byteorder = new PyString("big");

    public PyObject ps1 = new PyString(">>> ");
    public PyObject ps2 = new PyString("... ");

    public static int maxint = Integer.MAX_VALUE;
    public static int minint = Integer.MIN_VALUE;

    public PyObject executable;

    public static PyList warnoptions;

    private String currentWorkingDir;

    private PyObject environ;

    private ClassLoader classLoader = null;

    public PyObject stdout, stderr, stdin;
    public PyObject __stdout__, __stderr__, __stdin__;

    public PyObject __displayhook__, __excepthook__;

    public PyObject last_value = Py.None;
    public PyObject last_type = Py.None;
    public PyObject last_traceback = Py.None;

    public PyObject __name__ = new PyString("sys");
    
    public PyObject __dict__;

    private int recursionlimit = 1000;

    public PySystemState() {
        initialize();
        modules = new PyStringMap();

        argv = (PyList)defaultArgv.repeat(1);
        path = (PyList)defaultPath.repeat(1);
        path.append(Py.newString("__classpath__"));
        executable = defaultExecutable;

        meta_path = new PyList();
        path_hooks = new PyList();
        path_hooks.append(new JavaImporter());
        path_hooks.append(zipimporter.TYPE);
        path_importer_cache = new PyDictionary();

        currentWorkingDir = new File("").getAbsolutePath();
        initEnviron();

        // Set up the initial standard ins and outs
        String mode = Options.unbuffered ? "b" : "";
        int buffering = Options.unbuffered ? 0 : 1;
        __stdout__ = stdout = new PyFile(System.out, "<stdout>", "w" + mode, buffering, false);
        __stderr__ = stderr = new PyFile(System.err, "<stderr>", "w" + mode, 0, false);
        __stdin__ = stdin = new PyFile(System.in, "<stdin>", "r" + mode, buffering, false);
        __displayhook__ = new PySystemStateFunctions("displayhook", 10, 1, 1);
        __excepthook__ = new PySystemStateFunctions("excepthook", 30, 3, 3);

        if(builtins == null){
            builtins = new PyStringMap();
            __builtin__.fillWithBuiltins(builtins);
        }
        PyModule __builtin__ = new PyModule("__builtin__", builtins);
        modules.__setitem__("__builtin__", __builtin__);
        __dict__ = new PyStringMap();
        __dict__.invoke("update", getType().fastGetDict());
        __dict__.__setitem__("displayhook", __displayhook__);
        __dict__.__setitem__("excepthook", __excepthook__);
    }
    
    void reload() throws PyIgnoreMethodTag {
        __dict__.invoke("update", getType().fastGetDict());
    }

    // xxx fix this accessors
    public PyObject __findattr_ex__(String name) {
        if (name == "exc_value") {
            PyException exc = Py.getThreadState().exception;
            if (exc == null) return null;
            return exc.value;
        }
        if (name == "exc_type") {
            PyException exc = Py.getThreadState().exception;
            if (exc == null) return null;
            return exc.type;
        }
        if (name == "exc_traceback") {
            PyException exc = Py.getThreadState().exception;
            if (exc == null) return null;
            return exc.traceback;
        }
        if (name == "warnoptions") {
            if (warnoptions == null)
                warnoptions = new PyList();
            return warnoptions;
        }

        PyObject ret = super.__findattr_ex__(name);
        if (ret != null) {
            if (ret instanceof PyMethod) {
        	if (__dict__.__finditem__(name) instanceof PyReflectedFunction)
        	    return ret; // xxx depends on nonstandard __dict__
            } else if (ret == PyAttributeDeleted.INSTANCE) {
        	return null;
            }
            else return ret;
        }

        return __dict__.__finditem__(name);
    }

    public void __setattr__(String name, PyObject value) {
        if (name == "__dict__" || name == "__class__")
            throw Py.TypeError("readonly attribute");
        PyObject ret = getType().lookup(name); // xxx fix fix fix
        if (ret != null && ret.jtryset(this, value)) {
            return;
        }
        __dict__.__setitem__(name, value);
    }

    public void __delattr__(String name) {
        if (name == "__dict__" || name == "__class__")
            throw Py.TypeError("readonly attribute");
        PyObject ret = getType().lookup(name); // xxx fix fix fix
        if (ret != null) {
            ret.jtryset(this, PyAttributeDeleted.INSTANCE);
        }
        try {
            __dict__.__delitem__(name);
        } catch (PyException pye) { // KeyError
            if (ret == null) {
        	throw Py.AttributeError(name);
            }
        }
    }

    // xxx
    public void __rawdir__(PyDictionary accum) {
        accum.update(__dict__);
    }

    public String toString() {
        return "<module '" + __name__ + "' (built-in)>";
    }

    public int getrecursionlimit() {
        return recursionlimit;
    }

    public void setrecursionlimit(int recursionlimit) {
        if(recursionlimit <= 0) {
            throw Py.ValueError("Recursion limit must be positive");
        }
        this.recursionlimit = recursionlimit;
    }

    public void settrace(PyObject tracefunc) {
        ThreadState ts = Py.getThreadState();
        if (tracefunc == Py.None) {
            ts.tracefunc = null;
        } else {
            ts.tracefunc = new PythonTraceFunction(tracefunc);
        }
    }

    public void setprofile(PyObject profilefunc) {
        ThreadState ts = Py.getThreadState();
        if (profilefunc == Py.None) {
            ts.profilefunc = null;
        } else {
            ts.profilefunc = new PythonTraceFunction(profilefunc);
        }
    }

    public PyString getdefaultencoding() {
        return new PyString(codecs.getDefaultEncoding());
    }

    public void setdefaultencoding(String encoding) {
        codecs.setDefaultEncoding(encoding);
    }

    public PyObject getfilesystemencoding() {
        return Py.None;
    }

    /**
     * Initialize the environ dict from System.getenv.
     *
     */
    public void initEnviron() {
        environ = new PyDictionary();
        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            environ.__setitem__(Py.newString(entry.getKey()), Py.newString(entry.getValue()));
        }
    }

    public PyObject getEnviron() {
        return environ;
    }

    /**
     * Change the current working directory to the specified path.
     *
     * path is assumed to be absolute and canonical (via
     * os.path.realpath).
     *
     * @param path a path String
     */
    public void setCurrentWorkingDir(String path) {
        currentWorkingDir = path;
    }

    /**
     * Return a string representing the current working directory.
     *
     * @return a path String
     */
    public String getCurrentWorkingDir() {
        return currentWorkingDir;
    }

    /**
     * Resolve a path. Returns the full path taking the current
     * working directory into account.
     *
     * @param path a path String
     * @return a resolved path String
     */
    public String getPath(String path) {
        if (path == null || new File(path).isAbsolute()) {
            return path;
        }
        return new File(getCurrentWorkingDir(), path).getPath();
    }

    public void callExitFunc() throws PyIgnoreMethodTag {
        PyObject exitfunc = __findattr__("exitfunc");
        if (exitfunc != null) {
            try {
                exitfunc.__call__();
            } catch (PyException exc) {
                if (!Py.matchException(exc, Py.SystemExit)) {
                    Py.println(stderr,
                               Py.newString("Error in sys.exitfunc:"));
                }
                Py.printException(exc);
            }
        }
        Py.flushLine();
    }

    public ClassLoader getClassLoader() {
        return classLoader;
    }

    public void setClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    private static String findRoot(Properties preProperties,
                                     Properties postProperties,
                                     String jarFileName)
    {
        String root = null;
        try {
            if (postProperties != null)
                root = postProperties.getProperty("python.home");
            if (root == null)
                root = preProperties.getProperty("python.home");
            if (root == null)
                root = preProperties.getProperty("install.root");

            determinePlatform(preProperties);
        } catch (Exception exc) {
            return null;
        }
        // If install.root is undefined find JYTHON_JAR in class.path
        if (root == null) {
            String classpath = preProperties.getProperty("java.class.path");
            if (classpath != null) {
                String lowerCaseClasspath = classpath.toLowerCase();
                int jarIndex = lowerCaseClasspath.indexOf(JYTHON_COMPLETE_JAR);
                if (jarIndex < 0) {
                    jarIndex = lowerCaseClasspath.indexOf(JYTHON_JAR);
                }
                if (jarIndex >= 0) {
                    int start = classpath.lastIndexOf(java.io.File.pathSeparator, jarIndex) + 1;
                    root = classpath.substring(start, jarIndex);
                } else {
                    // in case JYTHON_JAR is referenced from a MANIFEST inside another jar on the classpath
                    root = jarFileName;
                }
            }
        }
        if (root != null) {
            File rootFile = new File(root);
            try {
                root = rootFile.getCanonicalPath();
            } catch (IOException ioe) {
                root = rootFile.getAbsolutePath();
            }
        }
        return root;
    }

    public static void determinePlatform(Properties props) {
        String version = props.getProperty("java.version");
        if (version == null) {
            version = "???";
        }
        String lversion = version.toLowerCase();
        if (lversion.startsWith("java")) {
            version = version.substring(4, version.length());
        }
        if (lversion.startsWith("jdk") || lversion.startsWith("jre")) {
            version = version.substring(3, version.length());
        }
        if (version.equals("12")) {
            version = "1.2";
        }
        platform = new PyString("java" + version);
    }

    private static void initRegistry(Properties preProperties, Properties postProperties, 
                                       boolean standalone, String jarFileName)
    {
        if (registry != null) {
            Py.writeError("systemState", "trying to reinitialize registry");
            return;
        }
        if (preProperties == null) {
            preProperties = getBaseProperties();
        }

        registry = preProperties;
        String prefix = findRoot(preProperties, postProperties, jarFileName);
        String exec_prefix = prefix;

        // Load the default registry
        if (prefix != null) {
            if (prefix.length() == 0) {
                prefix = exec_prefix = ".";
            }
            try {
                addRegistryFile(new File(prefix, "registry"));
                File homeFile = new File(registry.getProperty("user.home"),
                                         ".jython");
                addRegistryFile(homeFile);
            } catch (Exception exc) {
            }
        }
        if (prefix != null) {
            PySystemState.prefix = Py.newString(prefix);
        }
        if (exec_prefix != null) {
            PySystemState.exec_prefix = Py.newString(exec_prefix);
        }
        try {
            String jythonpath = System.getenv("JYTHONPATH");
            if (jythonpath != null) {
                registry.setProperty("python.path", jythonpath);
            }
        } catch (SecurityException e) {
        }
        if (postProperties != null) {
            registry.putAll(postProperties);
        }
        if (standalone) {
            // set default standalone property (if not yet set)
            if (!registry.containsKey(PYTHON_CACHEDIR_SKIP)) {
                registry.put(PYTHON_CACHEDIR_SKIP, "true");
            }
        }
        // Set up options from registry
        Options.setFromRegistry();
    }

    private static void addRegistryFile(File file) {
        if (file.exists()) {
            if (!file.isDirectory()) {
                registry = new Properties(registry);
                try {
                    FileInputStream fp = new FileInputStream(file);
                    try {
                        registry.load(fp);
                    } finally {
                        fp.close();
                    }
                } catch (IOException e) {
                    System.err.println("couldn't open registry file: " + file.toString());
                }
            } else {
                System.err.println("warning: " + file.toString() + " is a directory, not a file");
            }
        }
    }

    public static Properties getBaseProperties(){
        try{
            return System.getProperties();
        }catch(AccessControlException ace){
            return new Properties();
        }
    }

    public static synchronized void initialize() {
        initialize(null, null, new String[] {""});
    }

    public static synchronized void initialize(Properties preProperties,
                                               Properties postProperties,
                                               String[] argv)
    {
        initialize(preProperties, postProperties, argv, null);
    }

    public static synchronized void initialize(Properties preProperties,
                                               Properties postProperties,
                                               String[] argv,
                                               ClassLoader classLoader)
    {
        initialize(preProperties, postProperties, argv, classLoader, new ClassicPyObjectAdapter());
    }

    public static synchronized void initialize(Properties preProperties,
                                               Properties postProperties,
                                               String[] argv,
                                               ClassLoader classLoader,
                                               ExtensiblePyObjectAdapter adapter) {
        if (initialized) {
            return;
        }
        initialized = true;
        Py.setAdapter(adapter);
        boolean standalone = false;
        String jarFileName = getJarFileName();
        if (jarFileName != null) {
            standalone = isStandalone(jarFileName);
        }
        // initialize the Jython registry
        initRegistry(preProperties, postProperties, standalone, jarFileName);
        // other initializations
        initBuiltins(registry);
        initStaticFields();
        // Initialize the path (and add system defaults)
        defaultPath = initPath(registry, standalone, jarFileName);
        defaultArgv = initArgv(argv);
        defaultExecutable = initExecutable(registry);
        // Set up the known Java packages
        initPackages(registry);
        // Finish up standard Python initialization...
        Py.defaultSystemState = new PySystemState();
        Py.setSystemState(Py.defaultSystemState);
        if (classLoader != null)
            Py.defaultSystemState.setClassLoader(classLoader);
        Py.initClassExceptions(PySystemState.builtins);
        // Make sure that Exception classes have been loaded
        new PySyntaxError("", 1, 1, "", "");
    }

    private static void initStaticFields() {
        Py.None = new PyNone();
        Py.NotImplemented = new PyNotImplemented();
        Py.NoKeywords = new String[0];
        Py.EmptyObjects = new PyObject[0];

        Py.EmptyTuple = new PyTuple(Py.EmptyObjects);
        Py.EmptyFrozenSet = new PyFrozenSet();
        Py.NoConversion = new PySingleton("Error");
        Py.Ellipsis = new PyEllipsis();

        Py.Zero = new PyInteger(0);
        Py.One = new PyInteger(1);

        Py.False = new PyBoolean(false);
        Py.True = new PyBoolean(true);

        Py.EmptyString = new PyString("");
        Py.Newline = new PyString("\n");
        Py.Space = new PyString(" ");

        // Setup standard wrappers for stdout and stderr...
        Py.stderr = new StderrWrapper();
        Py.stdout = new StdoutWrapper();

        String s;
        if(Version.PY_RELEASE_LEVEL == 0x0A)
            s = "alpha";
        else if(Version.PY_RELEASE_LEVEL == 0x0B)
            s = "beta";
        else if(Version.PY_RELEASE_LEVEL == 0x0C)
            s = "candidate";
        else if(Version.PY_RELEASE_LEVEL == 0x0F)
            s = "final";
        else if(Version.PY_RELEASE_LEVEL == 0xAA)
            s = "snapshot";
        else
            throw new RuntimeException("Illegal value for PY_RELEASE_LEVEL: " +
                                       Version.PY_RELEASE_LEVEL);
        version_info = new PyTuple(Py.newInteger(Version.PY_MAJOR_VERSION),
                                   Py.newInteger(Version.PY_MINOR_VERSION),
                                   Py.newInteger(Version.PY_MICRO_VERSION),
                                   Py.newString(s),
                                   Py.newInteger(Version.PY_RELEASE_SERIAL));
        subversion = new PyTuple(Py.newString("Jython"), Py.newString(Version.BRANCH),
                                 Py.newString(Version.SVN_REVISION));
    }

    public static boolean isPackageCacheEnabled() {
        return cachedir != null;
    }

    private static void initCacheDirectory(Properties props) {
        String skip = props.getProperty(PYTHON_CACHEDIR_SKIP, "false");
        if (skip.equalsIgnoreCase("true")) {
            cachedir = null;
            return;
        }
        cachedir = new File(props.getProperty(PYTHON_CACHEDIR, CACHEDIR_DEFAULT_NAME));
        if (!cachedir.isAbsolute()) {
            cachedir = new File(prefix == null ? null : prefix.toString(), cachedir.getPath());
        }
    }

    private static void initPackages(Properties props) {
        initCacheDirectory(props);
        File pkgdir;
        if (cachedir != null) {
            pkgdir = new File(cachedir, "packages");
        } else {
            pkgdir = null;
        }
        packageManager = new SysPackageManager(pkgdir, props);
    }

    private static PyList initArgv(String[] args) {
        PyList argv = new PyList();
        if (args != null) {
            for (int i=0; i<args.length; i++) {
                argv.append(new PyString(args[i]));
            }
        }
        return argv;
    }

    /**
     * Determine the default sys.executable value from the
     * registry. Returns Py.None is no executable can be found.
     *
     * @param props a Properties registry
     * @return a PyObject path string or Py.None
     */
    private static PyObject initExecutable(Properties props) {
        String executable = props.getProperty("python.executable");
        if (executable == null) {
            return Py.None;
        }

        File executableFile = new File(executable);
        try {
            executableFile = executableFile.getCanonicalFile();
        } catch (IOException ioe) {
            executableFile = executableFile.getAbsoluteFile();
        }
        if (!executableFile.isFile()) {
            return Py.None;
        }
        return new PyString(executableFile.getPath());
    }

    private static void addBuiltin(String name) {
        String classname;
        String modname;

        int colon = name.indexOf(':');
        if (colon != -1) {
            // name:fqclassname
            modname = name.substring(0, colon).trim();
            classname = name.substring(colon+1, name.length()).trim();
            if (classname.equals("null"))
                // name:null, i.e. remove it
                classname = null;
        }
        else {
            modname = name.trim();
            classname = "org.python.modules." + modname;
        }
        if (classname != null)
            builtinNames.put(modname, classname);
        else
            builtinNames.remove(modname);
    }

    private static void initBuiltins(Properties props) {
        builtinNames = new HashMap<String,String>();

        // add the oddball builtins that are specially handled
        builtinNames.put("__builtin__", "");
        builtinNames.put("sys", "");

        // add builtins specified in the Setup.java file
        for (int i=0; i < Setup.builtinModules.length; i++)
            addBuiltin(Setup.builtinModules[i]);

        // add builtins specified in the registry file
        String builtinprop = props.getProperty("python.modules.builtin", "");
        StringTokenizer tok = new StringTokenizer(builtinprop, ",");
        while (tok.hasMoreTokens())
            addBuiltin(tok.nextToken());

        int n = builtinNames.size();
        PyObject [] built_mod = new PyObject[n];
        int i = 0;
        for (String key : builtinNames.keySet()) {
            built_mod[i++] = Py.newString(key);
        }
        builtin_module_names = new PyTuple(built_mod);
    }

    public static String getBuiltin(String name) {
        return (String)builtinNames.get(name);
    }

    private static PyList initPath(Properties props, boolean standalone, String jarFileName) {
        PyList path = new PyList();
        addPaths(path, props.getProperty("python.path", ""));
        if (prefix != null) {
            String libpath = new File(prefix.toString(), "Lib").toString();
            path.append(new PyString(libpath));
        }
        if (standalone) {
            // standalone jython: add the /Lib directory inside JYTHON_JAR to the path
            addPaths(path, jarFileName + "/Lib");
        }
        
        return path;
    }
    
    /**
     * Check if we are in standalone mode.
     * 
     * @param jarFileName The name of the jar file
     * 
     * @return <code>true</code> if we have a standalone .jar file, <code>false</code> otherwise.
     */
    private static boolean isStandalone(String jarFileName) {
        boolean standalone = false;
        if (jarFileName != null) {
            JarFile jarFile = null;
            try {
                jarFile = new JarFile(jarFileName);
                JarEntry jarEntry = jarFile.getJarEntry("Lib/os.py");
                standalone = jarEntry != null;
            } catch (IOException ioe) {
            } finally {
                if (jarFile != null) {
                    try {
                        jarFile.close();
                    } catch (IOException e) {
                    }
                }
            }
        }
        return standalone;
    }

    /**
     * @return the full name of the jar file containing this class, <code>null</code> if not available.
     */
    private static String getJarFileName() {
        String jarFileName = null;
        Class thisClass = PySystemState.class;
        String fullClassName = thisClass.getName();
        String className = fullClassName.substring(fullClassName.lastIndexOf(".") + 1);
        URL url = thisClass.getResource(className + ".class");
        // we expect an URL like jar:file:/install_dir/jython.jar!/org/python/core/PySystemState.class
        if (url != null) {
            try {
                String urlString = URLDecoder.decode(url.toString());
                int jarSeparatorIndex = urlString.indexOf(JAR_SEPARATOR);
                if (urlString.startsWith(JAR_URL_PREFIX) && jarSeparatorIndex > 0) {
                    jarFileName = urlString.substring(JAR_URL_PREFIX.length(), jarSeparatorIndex);
                }
            } catch (Exception e) {
            }
        }
        return jarFileName;
    }

    private static void addPaths(PyList path, String pypath) {
        StringTokenizer tok = new StringTokenizer(pypath,
                                                  java.io.File.pathSeparator);
        while  (tok.hasMoreTokens())
            path.append(new PyString(tok.nextToken().trim()));
    }

    public static PyJavaPackage add_package(String n) {
        return add_package(n, null);
    }

    public static PyJavaPackage add_package(String n, String contents) {
        return packageManager.makeJavaPackage(n, contents, null);
    }

    /**
     * Add a classpath directory to the list of places that are searched
     * for java packages.
     * <p>
     * <b>Note</b>. Classes found in directory and subdirectory are not
     * made available to jython by this call. It only makes the java
     * package found in the directory available. This call is mostly
     * usefull if jython is embedded in an application that deals with
     * its own classloaders. A servlet container is a very good example.
     * Calling add_classdir("<context>/WEB-INF/classes") makes the java
     * packages in WEB-INF classes available to jython import. However the
     * actual classloading is completely handled by the servlet container's
     * context classloader.
     */
    public static void add_classdir(String directoryPath) {
        packageManager.addDirectory(new File(directoryPath));
    }

    /**
     * Add a .jar & .zip directory to the list of places that are searched
     * for java .jar and .zip files. The .jar and .zip files found will not
     * be cached.
     * <p>
     * <b>Note</b>. Classes in .jar and .zip files found in the directory
     * are not made available to jython by this call. See the note for
     * add_classdir(dir) for more details.
     *
     * @param directoryPath The name of a directory.
     *
     * @see #add_classdir
     */
    public static void add_extdir(String directoryPath) {
        packageManager.addJarDir(directoryPath, false);
    }

    /**
     * Add a .jar & .zip directory to the list of places that are searched
     * for java .jar and .zip files.
     * <p>
     * <b>Note</b>. Classes in .jar and .zip files found in the directory
     * are not made available to jython by this call. See the note for
     * add_classdir(dir) for more details.
     *
     * @param directoryPath The name of a directory.
     * @param cache         Controls if the packages in the zip and jar
     *                      file should be cached.
     *
     * @see #add_classdir
     */
    public static void add_extdir(String directoryPath, boolean cache) {
        packageManager.addJarDir(directoryPath, cache);
    }

    /**
     * Resolve a path. Returns the full path taking the current
     * working directory into account.
     *
     * Like getPath but called statically. The current PySystemState
     * is only consulted for the current working directory when it's
     * necessary (when the path is relative).
     *
     * @param path a path String
     * @return a resolved path String
     */
    public static String getPathLazy(String path) {
        if (path == null || new File(path).isAbsolute()) {
            return path;
        }
        return new File(Py.getSystemState().getCurrentWorkingDir(), path).getPath();
    }

    // Not public by design. We can't rebind the displayhook if
    // a reflected function is inserted in the class dict.

    static void displayhook(PyObject o) {
        /* Print value except if None */
        /* After printing, also assign to '_' */
        /* Before, set '_' to None to avoid recursion */
        if (o == Py.None)
             return;

        PySystemState sys = Py.getThreadState().systemState;
        PySystemState.builtins.__setitem__("_", Py.None);
        Py.stdout.println(o.__repr__());
        PySystemState.builtins.__setitem__("_", o);
    }

    static void excepthook(PyObject type, PyObject val, PyObject tb) {
        Py.displayException(type, val, tb, null);
    }

    /**
     * Exit a Python program with the given status.
     *
     * @param status the value to exit with
     * @exception Py.SystemExit always throws this exception.
     * When caught at top level the program will exit.
     */
    public static void exit(PyObject status) {
        throw new PyException(Py.SystemExit, status);
    }

    /**
     * Exit a Python program with the status 0.
     */
    public static void exit() {
        exit(Py.None);
    }

    public static PyTuple exc_info() {
        PyException exc = Py.getThreadState().exception;
        if(exc == null)
            return new PyTuple(Py.None, Py.None, Py.None);
        return new PyTuple(exc.type, exc.value, exc.traceback);
    }

    public static void exc_clear() {
        Py.getThreadState().exception = null;
    }

    public static PyFrame _getframe() {
        return _getframe(-1);
    }

    public static PyFrame _getframe(int depth) {
        PyFrame f = Py.getFrame();

        while (depth > 0 && f != null) {
            f = f.f_back;
            --depth;
        }
        if (f == null)
             throw Py.ValueError("call stack is not deep enough");
        return f;
    }
}

class PySystemStateFunctions extends PyBuiltinFunctionSet
{
    PySystemStateFunctions(String name, int index, int minargs, int maxargs) {
        super(name, index, minargs, maxargs);
    }

    public PyObject __call__(PyObject arg) {
        switch (index) {
        case 10:
            PySystemState.displayhook(arg);
            return Py.None;
        default:
            throw info.unexpectedCall(1, false);
        }
    }
    public PyObject __call__(PyObject arg1, PyObject arg2, PyObject arg3) {
        switch (index) {
        case 30:
            PySystemState.excepthook(arg1, arg2, arg3);
            return Py.None;
        default:
            throw info.unexpectedCall(3, false);
        }
    }
}

/**
 * Value of a class or instance variable when the corresponding
 * attribute is deleted.  Used only in PySystemState for now.
 */
class PyAttributeDeleted extends PyObject {
    final static PyAttributeDeleted INSTANCE = new PyAttributeDeleted();
    private PyAttributeDeleted() {}
    public String toString() { return ""; }
    public Object __tojava__(Class c) {
        if (c == PyObject.class)
            return this;
        // we can't quite "delete" non-PyObject attributes; settle for
        // null or nothing
        if (c.isPrimitive())
            return Py.NoConversion;
        return null;
    }
}
