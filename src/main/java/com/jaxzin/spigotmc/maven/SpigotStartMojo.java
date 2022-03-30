package com.jaxzin.spigotmc.maven;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

/**
 * <p>
 * This goal is similar to the spigotmc:run goal, EXCEPT that it is designed to
 * be bound to an execution inside your pom, rather than being run from the
 * command line.
 * </p>
 * <p>
 * When using it, be careful to ensure that you bind it to a phase in which all
 * necessary generated files and classes for the bukkit plugin will have been
 * created. If you run it from the command line, then also ensure that all
 * necessary generated files and classes for the bukkit plugin already exist.
 * </p>
 */
@Mojo(name = "start", defaultPhase = LifecyclePhase.PRE_INTEGRATION_TEST, requiresDependencyResolution = ResolutionScope.TEST)
public class SpigotStartMojo extends AbstractMojo {

    @Parameter(property = "start.version")
    private String versions;
    @Parameter(property = "start.works")
    private String works;
    @Parameter(property = "start.error")
    private String error;
    @Parameter(property = "start.filename")
    private String filename;
    @Parameter(property = "start.foldername")
    private String folderName;


    public static Process spigotProcess;
    private File baseFolder = null;

    public void execute() throws MojoExecutionException, MojoFailureException {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (spigotProcess != null && spigotProcess.isAlive()) {
                try {
                    new SpigotStopMojo().execute();
                } catch (MojoExecutionException | MojoFailureException e) {
                    e.printStackTrace();
                }
            }
        }));
        if (versions == null || versions.isEmpty())
            throw new MojoFailureException("No Version(s) configured!");
        if (works == null || works.isEmpty())
            throw new MojoFailureException("No Success lookup message configured!");
        if (error == null || error.isEmpty())
            throw new MojoFailureException("No Error lookup message configured!");
        if (filename == null || filename.isEmpty())
            throw new MojoFailureException("No Plugin filename configured!");

        if (new File("").getName().equalsIgnoreCase(folderName)) { // Still a stupid hack
            baseFolder = new File("pom.xml").getParentFile();
        } else {
            File folder = new File(folderName);
            if (folder.exists() && folder.isDirectory()) {
                baseFolder = folder;
            }
        }

        if (baseFolder == null) {
            throw new MojoFailureException("Unable to find the projects folder!");
        }

        String[] targetVersions = versions.replace("\n", "").split(",");
        for (int i = 0; i < targetVersions.length; i++) targetVersions[i] = targetVersions[i].trim();
        getLog().info("Testing the following Versions: " + Arrays.toString(targetVersions));


        File spigotWorkingDir = new File(baseFolder, "target/it/spigotmc");
        spigotWorkingDir.mkdirs();
        //Delete worlds
        deleteWorlds(spigotWorkingDir);
        //Copy plugin
        copyPlugin(spigotWorkingDir);

        Map<String, Exception> errors = new HashMap<>();

        for (String version : targetVersions) {
            version = version.trim();
            try {
                testVersion(version);
            } catch (Exception ex) {
                errors.put(version, ex);
                getLog().error("Error in version '" + version + "'!", ex);
            }
        }
        deleteFolders(spigotWorkingDir);

        if (!errors.isEmpty()) {
            throw new MojoFailureException(errors.size() + " Version(s) had problems!");
        }

    }

    private void copyPlugin(File spigotWorkingDir) throws MojoFailureException {
        File pluginFolder = new File(spigotWorkingDir, "plugins");
        pluginFolder.mkdirs();

        File pluginFile = new File(new File(baseFolder, "target"), filename);
        if (!pluginFile.exists()) {
            throw new MojoFailureException("Plugin file not found at '" + pluginFile.getAbsolutePath() + "'");
        }
        try {
            Files.copy(pluginFile.toPath(), new File(pluginFolder, pluginFile.getName()).toPath());
        } catch (IOException e) {
            throw new MojoFailureException("Error moving Plugin file '" + pluginFile.getAbsolutePath() + "' to '" + new File(pluginFolder, pluginFile.getName()).getAbsolutePath() + "' because: " + e.getMessage(), e);
        }
    }

    private void deleteWorlds(final File spigotWorkingDir){
        getLog().info("Deleting worlds!");
        deleteFolders(new File(spigotWorkingDir, "world"));
        deleteFolders(new File(spigotWorkingDir, "world_nether"));
        deleteFolders(new File(spigotWorkingDir, "world_the_end"));
    }

    private void acceptEula(final File spigotWorkingDir) throws FileNotFoundException {
        File eulaFile = new File(spigotWorkingDir, "eula.txt");
        PrintWriter out = new PrintWriter(eulaFile);
        out.println("eula=true");
        out.close();
    }

    private String[] getRunArgs(final String outFilePath) {
        if (System.getProperty("os.name").contains("Windows")) {
            return new String[]{"cmd.exe", "/C",
                    "java -jar " + outFilePath + " nogui"};
        }
        return new String[]{"/bin/bash", "-c",
                "java -jar " + outFilePath + " nogui"};
    }

    private File getSpigot(String version, final File spigotWorkingDir) throws IOException {
        String url = "https://cdn.getbukkit.org/spigot/spigot-" + version + ".jar";
        if (version.startsWith("http")) {
            url = version;
            version = "custom";
        }
        File outFile = new File(spigotWorkingDir, "spigot-" + version + ".jar");
        if (outFile.exists()) {
            Files.delete(outFile.toPath());
        }
        URLConnection con = new URL(url).openConnection();
        con.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 6.2; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/83.0.4103.121 Safari/537.36 OPR/67.0.2245.46");
        try (BufferedInputStream in = new BufferedInputStream(con.getInputStream());
             FileOutputStream fileOutputStream = new FileOutputStream(outFile.getAbsolutePath())) {
            byte[] dataBuffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = in.read(dataBuffer, 0, 1024)) != -1) {
                fileOutputStream.write(dataBuffer, 0, bytesRead);
            }
        }

        return outFile;
    }

    private void testVersion(String version) throws MojoExecutionException, MojoFailureException {
        getLog().info("Starting server '" + version + "' for testing...");
        try {
            // Create the working dir for spigot to run
            File spigotWorkingDir = new File(baseFolder, "target/it/spigotmc");
            spigotWorkingDir.mkdirs();

            // Accept the EULA so Spigot will run
            acceptEula(spigotWorkingDir);

            // Get spigot
            File outFile = getSpigot(version, spigotWorkingDir);

            // Get args
            final String[] args = getRunArgs(outFile.getAbsolutePath());

            spigotProcess = new ProcessBuilder(args).directory(spigotWorkingDir).redirectErrorStream(true).start();
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(spigotProcess.getOutputStream()))) {
                writer.write("stop\n");
            } catch (IOException e) {
                getLog().info(e);
            }
            BufferedReader in = new BufferedReader(new InputStreamReader(spigotProcess.getInputStream()));
            AtomicReference<Status> status = new AtomicReference<>(Status.WAITING);
            long start = System.currentTimeMillis();
            Thread watcher = new Thread(() -> {
                try {
                    String read;
                    while (spigotProcess.isAlive()) {
                        read = in.readLine();
                        if (read != null) {
                            getLog().info("Spigot: " + read);
                            if (read.contains(works)) {
                                spigotProcess.destroyForcibly();
                                status.set(Status.OK);
                                return;
                            }
                            if (read.contains(error)) {
                                status.set(Status.ERROR);
                                return;
                            }
                            if (read.contains("FAILED TO BIND TO PORT")) {
                                status.set(Status.ERROR);
                                return;
                            }
                            if (read.contains("This crash report")) {
                                status.set(Status.ERROR);
                                return;
                            }
                            if (read.contains("Done")) {
                                status.set(Status.WAITING);
                                return;
                            }
                        }
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            });
            watcher.start();
            while (spigotProcess.isAlive() && status.get() == Status.WAITING && (System.currentTimeMillis() - start) < 240000) {
                Thread.sleep(1000);
            }
            getLog().info("Status: " + status.get());
            if (status.get() == Status.WAITING) {
                throw new MojoFailureException("Nothing happened!");
            }
            if (status.get() == Status.ERROR) {
                throw new MojoFailureException("Plugin did not start successfully!");
            }
            new SpigotStopMojo().execute();
        } catch (Throwable t) {
            new SpigotStopMojo().execute();
            throw new MojoFailureException("Unable to start Spigot server.", t);
        }
    }

    private void deleteFolders(File folder) {
        if (!folder.exists()) return;
        try (Stream<Path> walk = Files.walk(folder.toPath())) {
            walk.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        } catch (IOException e) {
            getLog().warn(e);
        }
    }

    private enum Status {
        WAITING, ERROR, OK
    }

}
