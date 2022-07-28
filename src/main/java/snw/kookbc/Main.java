/*
 *     KookBC -- The Kook Bot Client & JKook API standard implementation for Java.
 *     Copyright (C) 2022 KookBC contributors
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU Affero General Public License as published
 *     by the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU Affero General Public License for more details.
 *
 *     You should have received a copy of the GNU Affero General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package snw.kookbc;

import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import snw.jkook.JKook;
import snw.jkook.config.InvalidConfigurationException;
import snw.jkook.config.file.YamlConfiguration;
import snw.kookbc.impl.CoreImpl;
import snw.kookbc.impl.KBCClient;
import snw.kookbc.impl.network.webhook.WebHookClient;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;

public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    private static final File kbcLocal = new File("kbc.yml");

    public static void main(String[] args) {
        try {
            System.exit(main0(args));
        } catch (Throwable e) {
            logger.error("Unexpected situation happened during the execution of main method!", e);
            System.exit(1);
        }
    }

    private static int main0(String[] args) {
        Thread.currentThread().setName("Main thread");

        // KBC accepts following arguments:
        // --bot-file <filename>  --  Use the filename as the Bot main file
        // --token <tokenValue>   --  Use the tokenValue as the token:
        // --no-jline (Optional)  --  Disable JLine reader and use Java-API based reader
        // --no-color             --  Disable color output
        // --help                 --  Get help and exit

        OptionParser parser = new OptionParser();
        OptionSpec<File> botFileOption = parser.accepts("bot-file", "The Bot archive file path.").withOptionalArg().ofType(File.class);
        OptionSpec<String> tokenOption = parser.accepts("token", "The token that will be used. (Unsafe, write token to token.txt instead.)").withOptionalArg();
        OptionSpec<Void> noJlineOption = parser.accepts("no-jline", "Provide it to disable JLine-based command reader.");
        OptionSpec<Void> noColorOption = parser.accepts("no-color", "Provide it to disable ANSI color codes.");
        OptionSpec<Void> helpOption = parser.accepts("help", "Get help and exit.");

        OptionSet options;
        try {
            options = parser.parse(args);
        } catch (OptionException e) {
            logger.error("Unable to parse argument. Is your argument correct?", e);
            return 1;
        }

        if (options == null || !options.hasOptions()) { // if it is null, then maybe the valid data is in kbc.yml
            options = parser.parse();
        }

        if (options.has(helpOption)) {
            try {
                parser.printHelpOn(System.out);
            } catch (IOException e) {
                logger.error("Unable to print help.");
            }
            return 0;
        }

        String token = options.valueOf(tokenOption);
        boolean noJline = options.has(noJlineOption);
        boolean noColor = options.has(noColorOption);

        if (noJline) {
            System.setProperty("terminal.jline", "false");
        }
        if (noColor) {
            System.setProperty("terminal.ansi", "false");
        }

        File botFile = options.valueOf(botFileOption);

        saveKBCConfig();
        File botDataFolder = new File("bot");
        if (!botDataFolder.isDirectory()) {
            botDataFolder.mkdir();
        }

        YamlConfiguration config = new YamlConfiguration();

        try {
            config.load(kbcLocal);
        } catch (FileNotFoundException ignored) {
        } catch (IOException | InvalidConfigurationException e) {
            logger.error("Cannot load kbc.yml", e);
        }

        String configBotFile = config.getString("bot-file");
        if (configBotFile != null) {
            File cBotFile = new File(configBotFile);
            if (cBotFile.exists() && cBotFile.isFile() && cBotFile.canRead()) {
                logger.debug("Got valid bot-file in kbc.yml.");
                if (botFile == null) {
                    logger.debug("The value of bot-file from command line is invalid. We will use the value from kbc.yml configuration.");
                    botFile = cBotFile;
                } else {
                    logger.debug("The value of bot-file from command line is OK, so we won't use the value from kbc.yml configuration.");
                }
            } else {
                logger.warn("Invalid bot-file value in kbc.yml.");
            }
        }

        String configToken = config.getString("token");
        if (configToken != null && !configToken.isEmpty()) {
            logger.debug("Got valid token in kbc.yml.");
            if (token == null || token.isEmpty()) {
                logger.debug("The value of token from command line is invalid. We will use the value from kbc.yml configuration.");
                token = configToken;
            } else {
                logger.debug("The value of token from command line is OK, so we won't use the value from kbc.yml configuration.");
            }
        } else {
            logger.warn("Invalid token value in kbc.yml.");
        }

        if (token == null) {
            logger.error("No token provided. Program cannot continue.");
            return 1;
        }

        if (!config.getBoolean("allow-help-ad", true)) {
            logger.warn("Detected allow-help-ad is false! :("); // why don't you support us?
        }

        if (botFile == null || !botFile.exists()) {
            logger.error("Unable to find Bot file.");
            return 1;
        }
        if (!botFile.isFile()) {
            logger.error("Unable to load Bot file. It is not a file. (Maybe it is a directory?)");
            return 1;
        }
        if (!botFile.canRead()) {
            logger.error("Unable to load Bot file. We don't have permission to read it.");
            return 1;
        }


        return main1(botFile, token, config, botDataFolder);
    }

    private static int main1(File botFile, String token, YamlConfiguration config, File botDataFolder) {
        RuntimeMXBean runtimeMX = ManagementFactory.getRuntimeMXBean();
        OperatingSystemMXBean osMX = ManagementFactory.getOperatingSystemMXBean();
        if (runtimeMX != null && osMX != null) {
            logger.debug("System information is following:");
            logger.debug("Java: {} ({} {})", runtimeMX.getSpecVersion(), runtimeMX.getVmName(), runtimeMX.getVmVersion());
            logger.debug("Host: {} {} (Architecture: {})", osMX.getName(), osMX.getVersion(), osMX.getArch());
        } else {
            logger.debug("Unable to read system info");
        }

        CoreImpl core = new CoreImpl(logger);
        JKook.setCore(core);
        KBCClient client;
        String mode = config.getString("mode");
        if (mode != null) {
            if (mode.equalsIgnoreCase("webhook")) {
                client = new WebHookClient(core, config, botDataFolder);
            } else {
                client = new KBCClient(core, config, botDataFolder);
            }
        } else {
            throw new IllegalArgumentException("Unknown network mode!");
        }
        KBCClient.setInstance(client);

        // make sure the things can stop correctly (e.g. Scheduler), but the crash makes no sense.
        Runtime.getRuntime().addShutdownHook(new Thread(client::shutdown, "JVM Shutdown Hook Thread"));

        try {
            client.start(botFile, token);
        } catch (Throwable e) {
            logger.error("Failed to start client", e);
            client.shutdown();
            return 1;
        }

        client.loop();
        return 0;
    }

    private static void saveKBCConfig() {
        try (final InputStream stream = Main.class.getResourceAsStream("/kbc.yml")) {
            if (stream == null) {
                throw new Error("Unable to find kbc.yml");
            }

            if (kbcLocal.exists()) {
                return;
            }
            kbcLocal.createNewFile();

            try (final FileOutputStream out = new FileOutputStream(kbcLocal)) {
                int index;
                byte[] bytes = new byte[1024];
                while ((index = stream.read(bytes)) != -1) {
                    out.write(bytes, 0, index);
                }
            }
        } catch (IOException e) {
            logger.warn("Cannot save kbc.yml because an error occurred", e);
        }
    }

}
